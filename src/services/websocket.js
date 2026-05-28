import { WebSocketServer } from 'ws';
import { monitorEngine, monitorEvents } from './monitor.js';

let wss;
let activeSessionToken = null;

export function getSessionToken() {
  return activeSessionToken;
}

export function setSessionToken(token) {
  activeSessionToken = token;
}

/**
 * Initializes the WebSocket server.
 * @param {object} server - HTTP Server instance
 */
export function initWebSocket(server) {
  wss = new WebSocketServer({ noServer: true });

  wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.authenticated = false;
    ws.subscriptions = new Set(); // serverIds this client is watching

    ws.on('pong', () => {
      ws.isAlive = true;
    });

    ws.on('message', (message) => {
      try {
        const data = JSON.parse(message);

        // 1. Authenticate WebSocket connection
        if (data.type === 'auth') {
          if (activeSessionToken && data.token === activeSessionToken) {
            ws.authenticated = true;
            ws.send(JSON.stringify({ type: 'auth_success' }));
          } else {
            ws.send(JSON.stringify({ type: 'error', message: 'Invalid or missing session token' }));
            ws.close();
          }
          return;
        }

        // Must be authenticated for further messages
        if (!ws.authenticated) {
          ws.send(JSON.stringify({ type: 'error', message: 'Unauthorized' }));
          ws.close();
          return;
        }

        // 2. Subscribe to server real-time updates
        if (data.type === 'subscribe') {
          const { serverId } = data;
          if (serverId && !ws.subscriptions.has(serverId)) {
            ws.subscriptions.add(serverId);
            monitorEngine.subscribe(serverId);
            ws.send(JSON.stringify({ type: 'subscribed', serverId }));
          }
        }

        // 3. Unsubscribe from server real-time updates
        if (data.type === 'unsubscribe') {
          const { serverId } = data;
          if (serverId && ws.subscriptions.has(serverId)) {
            ws.subscriptions.delete(serverId);
            monitorEngine.unsubscribe(serverId);
            ws.send(JSON.stringify({ type: 'unsubscribed', serverId }));
          }
        }
      } catch (err) {
        ws.send(JSON.stringify({ type: 'error', message: 'Invalid message format' }));
      }
    });

    ws.on('close', () => {
      // Automatically unsubscribe from all servers when client disconnects
      if (ws.authenticated) {
        for (const serverId of ws.subscriptions) {
          monitorEngine.unsubscribe(serverId);
        }
      }
    });
  });

  // Connection keep-alive check (ping/pong) every 30 seconds
  const interval = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (ws.isAlive === false) return ws.terminate();
      ws.isAlive = false;
      ws.ping();
    });
  }, 30000);

  wss.on('close', () => {
    clearInterval(interval);
  });

  // Listen to monitorEvents to broadcast metrics to active subscribers
  monitorEvents.on('metrics', (data) => {
    wss.clients.forEach((ws) => {
      if (ws.authenticated && ws.subscriptions.has(data.serverId)) {
        ws.send(JSON.stringify({
          type: 'metrics',
          serverId: data.serverId,
          metrics: data.metrics
        }));
      }
    });
  });

  // Listen to server connection status changes
  monitorEvents.on('status', (data) => {
    wss.clients.forEach((ws) => {
      if (ws.authenticated) {
        ws.send(JSON.stringify({
          type: 'status',
          serverId: data.serverId,
          status: data.status,
          error: data.error
        }));
      }
    });
  });

  return wss;
}
export { wss };
