import express from 'express';
import crypto from 'crypto';
import { query } from '../services/db.js';
import { encrypt, decrypt } from '../services/crypto.js';
import { monitorEngine } from '../services/monitor.js';
import { sshPool } from '../services/sshPool.js';
import { getSessionToken, setSessionToken } from '../services/websocket.js';

const router = express.Router();

// Middleware: Require Application to be Unlocked & Session Token Valid
export function requireAuth(req, res, next) {
  if (!monitorEngine.isUnlocked()) {
    return res.status(401).json({ error: 'LOCKED', message: 'Hệ thống đang khóa. Vui lòng nhập Master Key.' });
  }

  const token = req.headers['authorization'] || req.query.token;
  const activeToken = getSessionToken();

  if (!activeToken || token !== activeToken) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Phiên làm việc không hợp lệ hoặc đã hết hạn.' });
  }

  next();
}

// 1. Get lock status
router.get('/status', async (req, res) => {
  try {
    const hasConfig = await query.get("SELECT 1 FROM system_config WHERE key = 'validation_string'");
    res.json({
      unlocked: monitorEngine.isUnlocked(),
      initialized: !!hasConfig
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 2. Unlock/Initialize application
router.post('/unlock', async (req, res) => {
  const { masterKey } = req.body;
  if (!masterKey) {
    return res.status(400).json({ error: 'Master Key là bắt buộc' });
  }

  try {
    const valString = await query.get("SELECT value FROM system_config WHERE key = 'validation_string'");

    if (!valString) {
      // First run: Initialize Master Key
      const encryptedVal = encrypt('vps_monitor_ok', masterKey);
      await query.run("INSERT INTO system_config (key, value) VALUES ('validation_string', ?)", [encryptedVal]);
      
      monitorEngine.setMasterKey(masterKey);
      
      // Generate active session token
      const token = crypto.randomBytes(32).toString('hex');
      setSessionToken(token);
      
      // Start monitoring background task
      monitorEngine.start();

      return res.json({ success: true, token, initialized: true, message: 'Khởi tạo Master Key thành công!' });
    } else {
      // Subsequent runs: Validate Master Key
      try {
        const decrypted = decrypt(valString.value, masterKey);
        if (decrypted === 'vps_monitor_ok') {
          monitorEngine.setMasterKey(masterKey);
          
          const token = crypto.randomBytes(32).toString('hex');
          setSessionToken(token);
          
          monitorEngine.start();

          return res.json({ success: true, token, initialized: true });
        } else {
          return res.status(401).json({ success: false, message: 'Master Key không chính xác' });
        }
      } catch (err) {
        return res.status(401).json({ success: false, message: 'Master Key không chính xác' });
      }
    }
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 3. List servers (Authenticated)
router.get('/servers', requireAuth, async (req, res) => {
  try {
    // Exclude password and keys for security
    const servers = await query.all(
      'SELECT id, name, host, port, username, auth_type, os_type, status, created_at, updated_at FROM servers ORDER BY name ASC'
    );
    res.json(servers);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4. Add a server
router.post('/servers', requireAuth, async (req, res) => {
  const { name, host, port, username, auth_type, password, ssh_key, passphrase, os_type } = req.body;

  if (!name || !host || !username || !auth_type || !os_type) {
    return res.status(400).json({ error: 'Thiếu thông tin bắt buộc' });
  }

  try {
    const id = crypto.randomUUID();
    const salt = crypto.randomBytes(16).toString('hex');
    const masterKey = monitorEngine.masterKey;

    let encryptedPassword = null;
    let encryptedSshKey = null;
    let passphraseEncrypted = null;

    if (auth_type === 'password') {
      if (!password) return res.status(400).json({ error: 'Mật khẩu là bắt buộc với kiểu auth_type = password' });
      encryptedPassword = encrypt(password, masterKey);
    } else if (auth_type === 'ssh_key') {
      if (!ssh_key) return res.status(400).json({ error: 'SSH Key là bắt buộc với kiểu auth_type = ssh_key' });
      encryptedSshKey = encrypt(ssh_key, masterKey);
      if (passphrase) {
        passphraseEncrypted = encrypt(passphrase, masterKey);
      }
    }

    await query.run(
      `INSERT INTO servers (id, name, host, port, username, auth_type, encrypted_password, encrypted_ssh_key, passphrase_encrypted, salt, os_type, status)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [id, name, host, parseInt(port) || 22, username, auth_type, encryptedPassword, encryptedSshKey, passphraseEncrypted, salt, os_type, 'unknown']
    );

    res.status(201).json({ id, name, host, port, username, auth_type, os_type, status: 'unknown' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 5. Test server connection (during create or update, or from dashboard)
router.post('/servers/test-connection', requireAuth, async (req, res) => {
  const { host, port, username, auth_type, password, ssh_key, passphrase } = req.body;

  if (!host || !username || !auth_type) {
    return res.status(400).json({ error: 'Thiếu thông tin kết nối' });
  }

  try {
    await sshPool.testConnection({
      host,
      port,
      username,
      auth_type,
      password,
      ssh_key,
      passphrase
    }, monitorEngine.masterKey);

    res.json({ success: true, message: 'Kết nối SSH thành công!' });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// 6. Test connection of saved server
router.post('/servers/:id/test', requireAuth, async (req, res) => {
  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    await sshPool.testConnection(server, monitorEngine.masterKey);
    await query.run('UPDATE servers SET status = "online", updated_at = CURRENT_TIMESTAMP WHERE id = ?', [server.id]);
    res.json({ success: true, message: 'Kết nối SSH thành công!' });
  } catch (err) {
    await query.run('UPDATE servers SET status = "offline", updated_at = CURRENT_TIMESTAMP WHERE id = ?', [req.params.id]);
    res.status(500).json({ success: false, error: err.message });
  }
});

// 7. Update a server
router.put('/servers/:id', requireAuth, async (req, res) => {
  const { name, host, port, username, auth_type, password, ssh_key, passphrase, os_type } = req.body;

  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    const masterKey = monitorEngine.masterKey;
    let encryptedPassword = server.encrypted_password;
    let encryptedSshKey = server.encrypted_ssh_key;
    let passphraseEncrypted = server.passphrase_encrypted;

    if (auth_type !== server.auth_type) {
      encryptedPassword = null;
      encryptedSshKey = null;
      passphraseEncrypted = null;
    }

    if (auth_type === 'password' && password) {
      encryptedPassword = encrypt(password, masterKey);
    } else if (auth_type === 'ssh_key') {
      if (ssh_key) {
        encryptedSshKey = encrypt(ssh_key, masterKey);
      }
      if (passphrase) {
        passphraseEncrypted = encrypt(passphrase, masterKey);
      }
    }

    // Close existing cached connection in case configuration changes
    sshPool.closeConnection(server.id);

    await query.run(
      `UPDATE servers 
       SET name = ?, host = ?, port = ?, username = ?, auth_type = ?, 
           encrypted_password = ?, encrypted_ssh_key = ?, passphrase_encrypted = ?, 
           os_type = ?, updated_at = CURRENT_TIMESTAMP 
       WHERE id = ?`,
      [
        name || server.name,
        host || server.host,
        parseInt(port) || server.port,
        username || server.username,
        auth_type || server.auth_type,
        encryptedPassword,
        encryptedSshKey,
        passphraseEncrypted,
        os_type || server.os_type,
        server.id
      ]
    );

    res.json({ success: true, message: 'Cập nhật cấu hình máy chủ thành công!' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 8. Delete a server
router.delete('/servers/:id', requireAuth, async (req, res) => {
  try {
    sshPool.closeConnection(req.params.id);
    await query.run('DELETE FROM servers WHERE id = ?', [req.params.id]);
    res.json({ success: true, message: 'Đã xóa máy chủ thành công.' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 9. Get server metrics history (24h)
router.get('/servers/:id/metrics', requireAuth, async (req, res) => {
  try {
    const metrics = await query.all(
      `SELECT cpu_usage, ram_used, ram_total, disk_used, disk_total, uptime, timestamp 
       FROM server_metrics 
       WHERE server_id = ? AND timestamp >= datetime('now', '-24 hours') 
       ORDER BY timestamp ASC`,
      [req.params.id]
    );
    res.json(metrics);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 10. Run connection speed test (SFTP Upload/Download)
router.post('/servers/:id/speedtest', requireAuth, async (req, res) => {
  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    // Establish/get connection
    const conn = await sshPool.getConnection(server, monitorEngine.masterKey);

    conn.sftp(async (err, sftp) => {
      if (err) return res.status(500).json({ error: `Không thể mở SFTP: ${err.message}` });

      const testFileName = 'vpsmonitor_speedtest.tmp';
      const testSizeMB = 2; // 2MB is fast but enough to compute transfer speed
      const testBuffer = crypto.randomBytes(testSizeMB * 1024 * 1024);

      try {
        // Measure UPLOAD
        const uploadStart = Date.now();
        await new Promise((resolve, reject) => {
          sftp.writeFile(testFileName, testBuffer, (writeErr) => {
            if (writeErr) reject(writeErr);
            else resolve();
          });
        });
        const uploadDuration = (Date.now() - uploadStart) / 1000; // seconds
        const uploadSpeed = Math.round((testSizeMB / uploadDuration) * 100) / 100; // MB/s

        // Measure DOWNLOAD
        const downloadStart = Date.now();
        await new Promise((resolve, reject) => {
          sftp.readFile(testFileName, (readErr, data) => {
            if (readErr) reject(readErr);
            else resolve();
          });
        });
        const downloadDuration = (Date.now() - downloadStart) / 1000;
        const downloadSpeed = Math.round((testSizeMB / downloadDuration) * 100) / 100;

        // Cleanup
        await new Promise((resolve) => {
          sftp.unlink(testFileName, () => resolve());
        });

        res.json({
          success: true,
          uploadSpeed,   // MB/s
          downloadSpeed,  // MB/s
          pingMs: Math.round((uploadDuration * 1000) / 10) // rough estimation of handshake RTT
        });
      } catch (speedErr) {
        // cleanup file in case of failure
        sftp.unlink(testFileName, () => {});
        res.status(500).json({ error: `Đo tốc độ thất bại: ${speedErr.message}` });
      }
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ================= TELEGRAM CONFIG ENDPOINTS =================

// 11. Get Telegram alert config
router.get('/config/telegram', requireAuth, async (req, res) => {
  try {
    const enabled = await query.get("SELECT value FROM system_config WHERE key = 'telegram_enabled'");
    const botToken = await query.get("SELECT value FROM system_config WHERE key = 'telegram_bot_token'");
    const chatId = await query.get("SELECT value FROM system_config WHERE key = 'telegram_chat_id'");
    const alertCpu = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_cpu'");
    const alertRam = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_ram'");
    const alertDisk = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_disk'");
    const alertStatus = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_status'");

    const masterKey = monitorEngine.masterKey;

    res.json({
      enabled: enabled ? enabled.value === 'true' : false,
      bot_token: botToken ? decrypt(botToken.value, masterKey) : '',
      chat_id: chatId ? decrypt(chatId.value, masterKey) : '',
      alert_cpu: alertCpu ? parseInt(alertCpu.value) || 90 : 90,
      alert_ram: alertRam ? parseInt(alertRam.value) || 90 : 90,
      alert_disk: alertDisk ? parseInt(alertDisk.value) || 90 : 90,
      alert_status: alertStatus ? alertStatus.value === 'true' : true
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 12. Save Telegram alert config
router.post('/config/telegram', requireAuth, async (req, res) => {
  const { enabled, bot_token, chat_id, alert_cpu, alert_ram, alert_disk, alert_status } = req.body;

  try {
    const masterKey = monitorEngine.masterKey;

    const saveKey = async (key, val) => {
      await query.run("INSERT INTO system_config (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", [key, val]);
    };

    await saveKey('telegram_enabled', enabled ? 'true' : 'false');
    await saveKey('telegram_alert_cpu', String(parseInt(alert_cpu) || 90));
    await saveKey('telegram_alert_ram', String(parseInt(alert_ram) || 90));
    await saveKey('telegram_alert_disk', String(parseInt(alert_disk) || 90));
    await saveKey('telegram_alert_status', alert_status ? 'true' : 'false');

    if (bot_token) {
      const encryptedToken = encrypt(bot_token, masterKey);
      await saveKey('telegram_bot_token', encryptedToken);
    }
    if (chat_id) {
      const encryptedChatId = encrypt(chat_id, masterKey);
      await saveKey('telegram_chat_id', encryptedChatId);
    }

    res.json({ success: true, message: 'Cấu hình Telegram Alert đã được lưu.' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 13. Test Telegram alert connection
router.post('/config/telegram/test', requireAuth, async (req, res) => {
  const { bot_token, chat_id } = req.body;
  if (!bot_token || !chat_id) {
    return res.status(400).json({ error: 'Cần Token Bot và Chat ID để gửi tin thử nghiệm' });
  }

  try {
    const https = await import('https');
    const data = JSON.stringify({
      chat_id,
      text: '🔔 <b>VPSMonitor Test Alert</b>\n\nKết nối Telegram Bot thành công! Bạn sẽ nhận được cảnh báo trạng thái máy chủ tại đây.',
      parse_mode: 'HTML'
    });

    const options = {
      hostname: 'api.telegram.org',
      port: 443,
      path: `/bot${bot_token}/sendMessage`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data)
      }
    };

    const reqPost = https.request(options, (response) => {
      let body = '';
      response.on('data', (chunk) => body += chunk);
      response.on('end', () => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          res.json({ success: true, message: 'Gửi tin thử nghiệm thành công!' });
        } else {
          res.status(response.statusCode).json({ error: `Telegram API error: ${response.statusCode} - ${body}` });
        }
      });
    });

    reqPost.on('error', (err) => {
      res.status(500).json({ error: `Lỗi kết nối tới Telegram: ${err.message}` });
    });

    reqPost.write(data);
    reqPost.end();
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ================= SFTP FILE MANAGER ENDPOINTS =================

// 14. List files/directories
router.get('/servers/:id/sftp/list', requireAuth, async (req, res) => {
  const remotePath = req.query.path || '/';

  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    const conn = await sshPool.getConnection(server, monitorEngine.masterKey);
    conn.sftp((err, sftp) => {
      if (err) return res.status(500).json({ error: `Không thể kết nối SFTP: ${err.message}` });

      sftp.readdir(remotePath, (readErr, list) => {
        if (readErr) {
          return res.status(500).json({ error: `Không thể đọc thư mục: ${readErr.message}` });
        }

        const items = list.map(item => {
          const mode = item.attrs.mode;
          const isDirectory = (mode & 0o170000) === 0o040000;
          const isFile = (mode & 0o170000) === 0o100000;
          const isLink = (mode & 0o170000) === 0o120000;

          return {
            name: item.filename,
            size: item.attrs.size,
            mtime: item.attrs.mtime * 1000,
            isDirectory,
            isFile,
            isLink
          };
        });

        res.json({
          currentPath: remotePath,
          items: items.sort((a, b) => {
            if (a.isDirectory && !b.isDirectory) return -1;
            if (!a.isDirectory && b.isDirectory) return 1;
            return a.name.localeCompare(b.name);
          })
        });
      });
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 15. Download file
router.get('/servers/:id/sftp/download', requireAuth, async (req, res) => {
  const { path: remotePath } = req.query;
  if (!remotePath) {
    return res.status(400).json({ error: 'Thiếu đường dẫn tệp' });
  }

  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    const conn = await sshPool.getConnection(server, monitorEngine.masterKey);
    conn.sftp((err, sftp) => {
      if (err) return res.status(500).json({ error: `Không thể kết nối SFTP: ${err.message}` });

      sftp.stat(remotePath, (statErr, stats) => {
        if (statErr) {
          return res.status(404).json({ error: `Không tìm thấy file: ${statErr.message}` });
        }

        const filename = remotePath.split('/').pop() || 'download';
        res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(filename)}"`);
        res.setHeader('Content-Type', 'application/octet-stream');
        res.setHeader('Content-Length', stats.size);

        const readStream = sftp.createReadStream(remotePath);
        readStream.on('error', (streamErr) => {
          console.error('SFTP Download stream error:', streamErr);
          if (!res.headersSent) {
            res.status(500).json({ error: `Lỗi đọc file SFTP: ${streamErr.message}` });
          }
        });

        readStream.pipe(res);
      });
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 16. Upload file (streamed, low RAM)
router.post('/servers/:id/sftp/upload', requireAuth, async (req, res) => {
  const { path: remotePath } = req.query;
  if (!remotePath) {
    return res.status(400).json({ error: 'Thiếu đường dẫn lưu tệp trên server' });
  }

  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    const conn = await sshPool.getConnection(server, monitorEngine.masterKey);
    conn.sftp((err, sftp) => {
      if (err) return res.status(500).json({ error: `Không thể kết nối SFTP: ${err.message}` });

      const writeStream = sftp.createWriteStream(remotePath);
      
      writeStream.on('close', () => {
        res.json({ success: true, message: 'Tải lên file thành công!' });
      });

      writeStream.on('error', (streamErr) => {
        res.status(500).json({ error: `Lỗi ghi file SFTP: ${streamErr.message}` });
      });

      req.pipe(writeStream);
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 17. Delete file or directory
router.delete('/servers/:id/sftp/delete', requireAuth, async (req, res) => {
  const { path: remotePath, type } = req.query;
  if (!remotePath) {
    return res.status(400).json({ error: 'Thiếu đường dẫn cần xóa' });
  }

  try {
    const server = await query.get('SELECT * FROM servers WHERE id = ?', [req.params.id]);
    if (!server) return res.status(404).json({ error: 'Không tìm thấy server' });

    const conn = await sshPool.getConnection(server, monitorEngine.masterKey);
    conn.sftp((err, sftp) => {
      if (err) return res.status(500).json({ error: `Không thể kết nối SFTP: ${err.message}` });

      if (type === 'directory') {
        sftp.rmdir(remotePath, (rmErr) => {
          if (rmErr) {
            return res.status(500).json({ error: `Không thể xóa thư mục (Lưu ý: thư mục phải rỗng): ${rmErr.message}` });
          }
          res.json({ success: true, message: 'Đã xóa thư mục thành công' });
        });
      } else {
        sftp.unlink(remotePath, (unErr) => {
          if (unErr) {
            return res.status(500).json({ error: `Không thể xóa tệp: ${unErr.message}` });
          }
          res.json({ success: true, message: 'Đã xóa tệp thành công' });
        });
      }
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

export default router;
