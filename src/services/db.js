import sqlite3 from 'sqlite3';
import path from 'path';

const dbPath = path.resolve('vps_monitor.db');
const db = new sqlite3.Database(dbPath);

// Promisified database operations
export const query = {
  run(sql, params = []) {
    return new Promise((resolve, reject) => {
      db.run(sql, params, function (err) {
        if (err) reject(err);
        else resolve({ lastID: this.lastID, changes: this.changes });
      });
    });
  },

  get(sql, params = []) {
    return new Promise((resolve, reject) => {
      db.get(sql, params, (err, row) => {
        if (err) reject(err);
        else resolve(row);
      });
    });
  },

  all(sql, params = []) {
    return new Promise((resolve, reject) => {
      db.all(sql, params, (err, rows) => {
        if (err) reject(err);
        else resolve(rows || []);
      });
    });
  }
};

/**
 * Initializes the SQLite database tables.
 */
export async function initDb() {
  await query.run(`
    CREATE TABLE IF NOT EXISTS system_config (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )
  `);

  await query.run(`
    CREATE TABLE IF NOT EXISTS servers (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      host TEXT NOT NULL,
      port INTEGER DEFAULT 22,
      username TEXT NOT NULL,
      auth_type TEXT NOT NULL,
      encrypted_password TEXT,
      encrypted_ssh_key TEXT,
      passphrase_encrypted TEXT,
      salt TEXT NOT NULL,
      os_type TEXT NOT NULL,
      status TEXT DEFAULT 'unknown',
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  await query.run(`
    CREATE TABLE IF NOT EXISTS server_metrics (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      server_id TEXT NOT NULL,
      cpu_usage REAL NOT NULL,
      ram_used REAL NOT NULL,
      ram_total REAL NOT NULL,
      disk_used REAL NOT NULL,
      disk_total REAL NOT NULL,
      uptime TEXT NOT NULL,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(server_id) REFERENCES servers(id) ON DELETE CASCADE
    )
  `);

  await query.run(`
    CREATE INDEX IF NOT EXISTS idx_metrics_timestamp ON server_metrics(timestamp)
  `);
  
  await query.run(`
    CREATE INDEX IF NOT EXISTS idx_metrics_server ON server_metrics(server_id)
  `);
  
  console.log('Database initialized successfully.');
}

export default db;
