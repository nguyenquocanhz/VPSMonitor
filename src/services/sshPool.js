import { Client } from 'ssh2';
import { decrypt } from './crypto.js';

class SshPool {
  constructor() {
    this.connections = new Map(); // serverId -> ssh2 Client instance
  }

  /**
   * Get or establish an active SSH connection for a server.
   * @param {object} server - The database server object
   * @param {string} masterKey - The decryption master key
   * @returns {Promise<Client>}
   */
  getConnection(server, masterKey) {
    if (this.connections.has(server.id)) {
      return Promise.resolve(this.connections.get(server.id));
    }

    return new Promise((resolve, reject) => {
      const conn = new Client();
      
      const config = {
        host: server.host,
        port: server.port || 22,
        username: server.username,
        keepaliveInterval: 10000, // Send keep-alive packet every 10 seconds
        keepaliveCountMax: 3,     // Disconnect after 3 failed keep-alives
        readyTimeout: 15000       // Timeout for handshake
      };

      try {
        if (server.auth_type === 'password') {
          config.password = decrypt(server.encrypted_password, masterKey);
        } else if (server.auth_type === 'ssh_key') {
          config.privateKey = decrypt(server.encrypted_ssh_key, masterKey);
          if (server.passphrase_encrypted) {
            config.passphrase = decrypt(server.passphrase_encrypted, masterKey);
          }
        } else {
          return reject(new Error('Unknown authentication type'));
        }
      } catch (err) {
        return reject(new Error(`Failed to decrypt credentials: ${err.message}`));
      }

      conn.on('ready', () => {
        this.connections.set(server.id, conn);
        resolve(conn);
      });

      conn.on('error', (err) => {
        this.connections.delete(server.id);
        reject(err);
      });

      conn.on('close', () => {
        this.connections.delete(server.id);
      });

      conn.connect(config);
    });
  }

  /**
   * Executes a command on the target server and returns stdout.
   * @param {object} server 
   * @param {string} command 
   * @param {string} masterKey 
   * @returns {Promise<string>}
   */
  async runCommand(server, command, masterKey) {
    const conn = await this.getConnection(server, masterKey);
    return new Promise((resolve, reject) => {
      conn.exec(command, (err, stream) => {
        if (err) return reject(err);
        
        let stdout = '';
        let stderr = '';
        
        stream.on('close', (code) => {
          if (code !== 0) {
            reject(new Error(`Command exited with code ${code}. Stderr: ${stderr}`));
          } else {
            resolve(stdout);
          }
        });
        
        stream.on('data', (data) => {
          stdout += data.toString();
        });
        
        stream.stderr.on('data', (data) => {
          stderr += data.toString();
        });
      });
    });
  }

  /**
   * Tests a connection with raw config before saving it.
   * @param {object} serverConfig - Server configurations (may contain plain credentials)
   * @param {string} masterKey 
   * @returns {Promise<boolean>}
   */
  testConnection(serverConfig, masterKey) {
    return new Promise((resolve, reject) => {
      const conn = new Client();
      const config = {
        host: serverConfig.host,
        port: parseInt(serverConfig.port) || 22,
        username: serverConfig.username,
        readyTimeout: 10000
      };

      try {
        if (serverConfig.auth_type === 'password') {
          config.password = serverConfig.encrypted_password 
            ? decrypt(serverConfig.encrypted_password, masterKey)
            : serverConfig.password;
        } else if (serverConfig.auth_type === 'ssh_key') {
          config.privateKey = serverConfig.encrypted_ssh_key
            ? decrypt(serverConfig.encrypted_ssh_key, masterKey)
            : serverConfig.ssh_key;
          
          const passphrase = serverConfig.passphrase_encrypted
            ? decrypt(serverConfig.passphrase_encrypted, masterKey)
            : serverConfig.passphrase;
          if (passphrase) {
            config.passphrase = passphrase;
          }
        } else {
          return reject(new Error('Unknown authentication type'));
        }
      } catch (err) {
        return reject(new Error(`Failed to decrypt credentials: ${err.message}`));
      }

      conn.on('ready', () => {
        conn.end();
        resolve(true);
      });

      conn.on('error', (err) => {
        reject(err);
      });

      conn.connect(config);
    });
  }

  /**
   * Closes a connection by Server ID.
   * @param {string} serverId 
   */
  closeConnection(serverId) {
    if (this.connections.has(serverId)) {
      const conn = this.connections.get(serverId);
      try { conn.end(); } catch (e) {}
      this.connections.delete(serverId);
    }
  }

  /**
   * Closes all active connections in the pool.
   */
  closeAll() {
    for (const conn of this.connections.values()) {
      try { conn.end(); } catch (e) {}
    }
    this.connections.clear();
  }
}

export const sshPool = new SshPool();
