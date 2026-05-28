import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { initDb } from './services/db.js';
import serverRouter from './routes/servers.js';
import { initWebSocket } from './services/websocket.js';
import { monitorEngine } from './services/monitor.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const server = http.createServer(app);

app.use(express.json());

// Serve static frontend files
app.use(express.static(path.join(__dirname, '../public')));

// API routes
app.use('/api', serverRouter);

// Fallback for Single Page Application
app.use((req, res) => {
  res.sendFile(path.join(__dirname, '../public/index.html'));
});

const PORT = process.env.PORT || 3000;

async function bootstrap() {
  try {
    // 1. Initialize SQLite Database
    await initDb();

    // 2. Initialize WebSockets
    const wss = initWebSocket(server);

    server.on('upgrade', (request, socket, head) => {
      const url = new URL(request.url, `http://${request.headers.host}`);
      if (url.pathname === '/ws') {
        wss.handleUpgrade(request, socket, head, (ws) => {
          wss.emit('connection', ws, request);
        });
      } else {
        socket.destroy();
      }
    });

    // 3. Start Server
    server.listen(PORT, () => {
      console.log(`==================================================`);
      console.log(`🚀 VPSMonitor running on http://localhost:${PORT}`);
      console.log(`==================================================`);
    });

  } catch (err) {
    console.error('Failed to bootstrap VPSMonitor:', err);
    process.exit(1);
  }
}

// Graceful shutdown
const shutdown = () => {
  console.log('Shutting down server gracefully...');
  monitorEngine.stop();
  server.close(() => {
    console.log('HTTP and WebSocket server closed.');
    process.exit(0);
  });
};

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

bootstrap();
