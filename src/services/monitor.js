import { EventEmitter } from 'events';
import { query } from './db.js';
import { sshPool } from './sshPool.js';
import { decrypt } from './crypto.js';

export const monitorEvents = new EventEmitter();

class MonitorEngine {
  constructor() {
    this.masterKey = null;
    this.activeServers = new Map(); // serverId -> count of active connections
    this.lastPollTimes = new Map();  // serverId -> timestamp of last poll
    this.alertStates = new Map();    // serverId:metric -> timestamp
    this.isRunning = false;
    this.timer = null;
    this.cleanupTimer = null;
  }

  setMasterKey(key) {
    this.masterKey = key;
  }

  isUnlocked() {
    return !!this.masterKey;
  }

  /**
   * Register that a client is actively viewing a server's metrics.
   * Increases polling frequency.
   * @param {string} serverId 
   */
  subscribe(serverId) {
    const current = this.activeServers.get(serverId) || 0;
    this.activeServers.set(serverId, current + 1);
  }

  /**
   * Deregister client active view.
   * @param {string} serverId 
   */
  unsubscribe(serverId) {
    const current = this.activeServers.get(serverId) || 0;
    if (current <= 1) {
      this.activeServers.delete(serverId);
    } else {
      this.activeServers.set(serverId, current - 1);
    }
  }

  /**
   * Start the background scheduler.
   */
  start() {
    if (this.isRunning) return;
    this.isRunning = true;
    
    // Master ticker: checks every second if any server is due for polling
    this.timer = setInterval(() => this.tick(), 1000);
    
    // Cleanup timer: runs once an hour to delete metrics > 24 hours
    this.cleanupTimer = setInterval(() => this.cleanupOldMetrics(), 3600 * 1000);
    
    console.log('Monitor engine started.');
    // Run an initial cleanup
    this.cleanupOldMetrics().catch(err => console.error('Initial cleanup error:', err));
  }

  /**
   * Stop the background scheduler.
   */
  stop() {
    if (this.timer) clearInterval(this.timer);
    if (this.cleanupTimer) clearInterval(this.cleanupTimer);
    this.isRunning = false;
  }

  /**
   * The scheduler tick, evaluating which servers to poll.
   */
  async tick() {
    if (!this.masterKey) return; // Wait until app is unlocked with master key

    try {
      const servers = await query.all('SELECT * FROM servers');
      const now = Date.now();

      for (const server of servers) {
        // Active view = 3 seconds polling, otherwise 60 seconds
        const isActive = this.activeServers.has(server.id);
        const interval = isActive ? 3000 : 60000;

        const lastPoll = this.lastPollTimes.get(server.id) || 0;
        if (now - lastPoll >= interval) {
          this.lastPollTimes.set(server.id, now);
          // Run poll asynchronously to avoid blocking the main loop
          this.pollServer(server).catch(err => {
            console.error(`Error polling server ${server.name}:`, err.message);
          });
        }
      }
    } catch (err) {
      console.error('Error in monitor scheduler tick:', err);
    }
  }

  /**
   * Polls a specific server for system metrics.
   * @param {object} server 
   */
  async pollServer(server) {
    let status = 'online';
    let rawOutput = '';
    
    try {
      if (server.os_type === 'linux') {
        const command = `echo "===CPU===" && top -bn1 | grep -i "cpu(s)" | head -n 1 && echo "===RAM===" && free -m && echo "===DISK===" && df -m / && echo "===UPTIME===" && uptime -p`;
        rawOutput = await sshPool.runCommand(server, command, this.masterKey);
      } else if (server.os_type === 'windows') {
        const command = `powershell -Command "Write-Output '===CPU==='; (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average; Write-Output '===RAM==='; $os = Get-CimInstance Win32_OperatingSystem; Write-Output \\"$([Math]::Round(($os.TotalVisibleMemorySize - $os.FreePhysicalMemory)/1024, 0)) $([Math]::Round($os.TotalVisibleMemorySize/1024, 0))\\"; Write-Output '===DISK==='; $disk = Get-CimInstance Win32_LogicalDisk -Filter 'DeviceID=\\"C:\\"'; Write-Output \\"$([Math]::Round(($disk.Size - $disk.FreeSpace)/1GB, 1)) $([Math]::Round($disk.Size/1GB, 1))\\"; Write-Output '===UPTIME==='; $uptime = (Get-Date) - $os.LastBootUpTime; Write-Output \\"$([Math]::Floor($uptime.TotalDays))d $($uptime.Hours)h $($uptime.Minutes)m\\";"`;
        rawOutput = await sshPool.runCommand(server, command, this.masterKey);
      } else {
        throw new Error('Unsupported server OS type');
      }
      
      const metrics = this.parseMetrics(rawOutput, server.os_type);
      
      // Save metrics to DB
      await query.run(
        `INSERT INTO server_metrics (server_id, cpu_usage, ram_used, ram_total, disk_used, disk_total, uptime) 
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [server.id, metrics.cpu_usage, metrics.ram_used, metrics.ram_total, metrics.disk_used, metrics.disk_total, metrics.uptime]
      );

      // Check Telegram resource alerts
      await this.checkResourceAlerts(server, metrics);

      // Update server status if changed
      if (server.status !== 'online') {
        await this.handleStatusChange(server, 'online');
        await query.run('UPDATE servers SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?', ['online', server.id]);
      }

      // Emit event for real-time WebSockets
      monitorEvents.emit('metrics', {
        serverId: server.id,
        metrics: {
          ...metrics,
          timestamp: new Date().toISOString()
        }
      });

    } catch (err) {
      status = err.message.includes('Auth fail') || err.message.includes('authentication') ? 'auth_failed' : 'offline';
      
      if (server.status !== status) {
        await this.handleStatusChange(server, status, err.message);
        await query.run('UPDATE servers SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?', [status, server.id]);
      }
      
      monitorEvents.emit('status', {
        serverId: server.id,
        status: status,
        error: err.message
      });
    }
  }

  /**
   * Helper to parse shell command output into structured metrics.
   * @param {string} stdout 
   * @param {string} osType 
   * @returns {object}
   */
  parseMetrics(stdout, osType) {
    const metrics = {
      cpu_usage: 0,
      ram_used: 0,
      ram_total: 0,
      disk_used: 0,
      disk_total: 0,
      uptime: 'unknown'
    };

    if (osType === 'linux') {
      // 1. CPU Usage
      // e.g. %Cpu(s):  3.1 us,  0.8 sy,  0.0 ni, 95.9 id,  0.0 wa...
      const cpuSection = this.getSection(stdout, '===CPU===', '===RAM===');
      const idleMatch = cpuSection.match(/([0-9.,]+)\s*(?:id|idle)/i);
      if (idleMatch) {
        const idlePct = parseFloat(idleMatch[1].replace(',', '.'));
        metrics.cpu_usage = Math.round((100 - idlePct) * 10) / 10;
      }

      // 2. RAM Usage (free -m)
      // e.g. Mem:           1987         522         340          10        1124        1301
      const ramSection = this.getSection(stdout, '===RAM===', '===DISK===');
      const memLine = ramSection.split('\n').find(l => l.startsWith('Mem:'));
      if (memLine) {
        const parts = memLine.trim().split(/\s+/);
        if (parts.length >= 3) {
          // values are in MB, convert to GB
          metrics.ram_total = Math.round((parseInt(parts[1]) / 1024) * 10) / 10;
          metrics.ram_used = Math.round((parseInt(parts[2]) / 1024) * 10) / 10;
        }
      }

      // 3. Disk Usage (df -m /)
      // e.g. /dev/sda1        40123      12345     25678  33% /
      const diskSection = this.getSection(stdout, '===DISK===', '===UPTIME===');
      const lines = diskSection.split('\n');
      const diskLine = lines.find(l => l.trim().match(/\s+\/\s*$/)) || lines[1]; // fallback to line 2
      if (diskLine) {
        const parts = diskLine.trim().split(/\s+/);
        // df -m: 1st=filesystem, 2nd=blocks(total), 3rd=used. sometimes 1st columns wraps.
        if (parts.length >= 3) {
          const totalMB = parseInt(parts[1]) || parseInt(parts[2]); // handle column shifting
          const usedMB = parseInt(parts[2]) || parseInt(parts[3]);
          metrics.disk_total = Math.round((totalMB / 1024) * 10) / 10;
          metrics.disk_used = Math.round((usedMB / 1024) * 10) / 10;
        }
      }

      // 4. Uptime
      const uptimeSection = this.getSection(stdout, '===UPTIME===', '');
      metrics.uptime = uptimeSection.trim().replace(/^up\s+/, '') || 'unknown';

    } else if (osType === 'windows') {
      // Windows output is highly structured lines
      const cpuSection = this.getSection(stdout, '===CPU===', '===RAM===');
      metrics.cpu_usage = Math.round(parseFloat(cpuSection.trim()) * 10) / 10 || 0;

      const ramSection = this.getSection(stdout, '===RAM===', '===DISK===');
      const ramParts = ramSection.trim().split(/\s+/);
      if (ramParts.length >= 2) {
        // Values returned are in MB (already converted to GB in PS script? No, we divided by 1024 in script so it's in GB)
        metrics.ram_used = parseFloat(ramParts[0]) || 0;
        metrics.ram_total = parseFloat(ramParts[1]) || 0;
      }

      const diskSection = this.getSection(stdout, '===DISK===', '===UPTIME===');
      const diskParts = diskSection.trim().split(/\s+/);
      if (diskParts.length >= 2) {
        // Values returned are in GB
        metrics.disk_used = parseFloat(diskParts[0]) || 0;
        metrics.disk_total = parseFloat(diskParts[1]) || 0;
      }

      const uptimeSection = this.getSection(stdout, '===UPTIME===', '');
      metrics.uptime = uptimeSection.trim() || 'unknown';
    }

    return metrics;
  }

  /**
   * Extracts content between two headers.
   * @param {string} text 
   * @param {string} startHeader 
   * @param {string} endHeader 
   */
  getSection(text, startHeader, endHeader) {
    const startIndex = text.indexOf(startHeader);
    if (startIndex === -1) return '';
    const sectionStart = startIndex + startHeader.length;
    
    if (!endHeader) {
      return text.slice(sectionStart);
    }
    
    const endIndex = text.indexOf(endHeader, sectionStart);
    if (endIndex === -1) {
      return text.slice(sectionStart);
    }
    
    return text.slice(sectionStart, endIndex);
  }

  /**
   * Delete metrics older than 24 hours to keep the database size minimal.
   */
  async cleanupOldMetrics() {
    try {
      const res = await query.run("DELETE FROM server_metrics WHERE timestamp < datetime('now', '-24 hours')");
      if (res.changes > 0) {
        console.log(`Cleaned up ${res.changes} historical metric records older than 24 hours.`);
      }
    } catch (err) {
      console.error('Failed to run metrics database cleanup:', err);
    }
  }

  async getTelegramConfig() {
    try {
      const enabled = await query.get("SELECT value FROM system_config WHERE key = 'telegram_enabled'");
      if (!enabled || enabled.value !== 'true') return null;

      const botToken = await query.get("SELECT value FROM system_config WHERE key = 'telegram_bot_token'");
      const chatId = await query.get("SELECT value FROM system_config WHERE key = 'telegram_chat_id'");
      const alertCpu = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_cpu'");
      const alertRam = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_ram'");
      const alertDisk = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_disk'");
      const alertStatus = await query.get("SELECT value FROM system_config WHERE key = 'telegram_alert_status'");

      const decryptHelper = (val) => {
        try {
          return val ? decrypt(val.value, this.masterKey) : '';
        } catch {
          return '';
        }
      };

      return {
        botToken: decryptHelper(botToken),
        chatId: decryptHelper(chatId),
        alertCpu: alertCpu ? parseInt(alertCpu.value) || 90 : 90,
        alertRam: alertRam ? parseInt(alertRam.value) || 90 : 90,
        alertDisk: alertDisk ? parseInt(alertDisk.value) || 90 : 90,
        alertStatus: alertStatus ? alertStatus.value === 'true' : true
      };
    } catch (e) {
      console.error('Error reading telegram config:', e);
      return null;
    }
  }

  async sendTelegramMessage(config, text) {
    if (!config || !config.botToken || !config.chatId) return;

    try {
      const https = await import('https');
      const data = JSON.stringify({
        chat_id: config.chatId,
        text: text,
        parse_mode: 'HTML'
      });

      const options = {
        hostname: 'api.telegram.org',
        port: 443,
        path: `/bot${config.botToken}/sendMessage`,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(data)
        }
      };

      const reqPost = https.request(options, (response) => {
        response.on('data', () => {}); // consume response
      });

      reqPost.on('error', (err) => {
        console.error('Telegram message post error:', err);
      });

      reqPost.write(data);
      reqPost.end();
    } catch (err) {
      console.error('Failed to send Telegram message:', err);
    }
  }

  async handleStatusChange(server, newStatus, errorMsg = '') {
    const config = await this.getTelegramConfig();
    if (!config || !config.alertStatus) return;

    const alertKey = `${server.id}:status:${newStatus}`;
    const lastAlert = this.alertStates.get(alertKey) || 0;
    const now = Date.now();
    
    // Cooldown of 5 minutes (300,000 ms) for status alerts
    if (now - lastAlert < 300000) {
      return;
    }
    this.alertStates.set(alertKey, now);

    let text = '';
    if (newStatus === 'online') {
      text = `🟢 <b>[MÁY CHỦ ONLINE]</b>\n\n` +
             `<b>Tên:</b> ${server.name}\n` +
             `<b>Host:</b> ${server.host}:${server.port}\n` +
             `<b>Hệ điều hành:</b> ${server.os_type.toUpperCase()}\n` +
             `<b>Trạng thái:</b> Đã kết nối lại thành công.`;
    } else {
      const typeLabel = newStatus === 'auth_failed' ? 'LỖI XÁC THỰC' : 'MẤT KẾT NỐI';
      text = `🔴 <b>[MÁY CHỦ OFFLINE - ${typeLabel}]</b>\n\n` +
             `<b>Tên:</b> ${server.name}\n` +
             `<b>Host:</b> ${server.host}:${server.port}\n` +
             `<b>Chi tiết lỗi:</b> ${errorMsg || 'Không phản hồi kết nối SSH'}`;
    }

    this.sendTelegramMessage(config, text);
  }

  async checkResourceAlerts(server, metrics) {
    const config = await this.getTelegramConfig();
    if (!config) return;

    const checkMetric = (metricName, currentValue, totalValue, threshold, label) => {
      const percentage = totalValue > 0 ? (currentValue / totalValue) * 100 : currentValue;
      const alertKey = `${server.id}:${metricName}`;
      const hasActiveAlert = this.alertStates.get(alertKey);
      const now = Date.now();

      if (percentage >= threshold) {
        const lastAlertTime = this.alertStates.get(`${alertKey}:last_sent`) || 0;
        if (!hasActiveAlert || (now - lastAlertTime > 300000)) {
          this.alertStates.set(alertKey, true);
          this.alertStates.set(`${alertKey}:last_sent`, now);

          const pctString = totalValue > 0 
            ? `${percentage.toFixed(1)}% (${currentValue.toFixed(1)}GB/${totalValue.toFixed(1)}GB)` 
            : `${percentage.toFixed(1)}%`;

          const text = `⚠️ <b>[CẢNH BÁO TÀI NGUYÊN]</b>\n\n` +
                       `<b>Máy chủ:</b> ${server.name} (${server.host})\n` +
                       `<b>Tài nguyên:</b> ${label}\n` +
                       `<b>Mức sử dụng hiện tại:</b> ${pctString}\n` +
                       `<b>Ngưỡng cảnh báo:</b> ${threshold}%`;
          this.sendTelegramMessage(config, text);
        }
      } else {
        if (hasActiveAlert) {
          this.alertStates.delete(alertKey);
          this.alertStates.delete(`${alertKey}:last_sent`);

          const pctString = totalValue > 0 
            ? `${percentage.toFixed(1)}% (${currentValue.toFixed(1)}GB/${totalValue.toFixed(1)}GB)` 
            : `${percentage.toFixed(1)}%`;

          const text = `✅ <b>[KHÔI PHỤC TÀI NGUYÊN]</b>\n\n` +
                       `<b>Máy chủ:</b> ${server.name} (${server.host})\n` +
                       `<b>Tài nguyên:</b> ${label} đã trở lại bình thường\n` +
                       `<b>Mức sử dụng hiện tại:</b> ${pctString}\n` +
                       `<b>Ngưỡng cảnh báo:</b> ${threshold}%`;
          this.sendTelegramMessage(config, text);
        }
      }
    };

    checkMetric('cpu', metrics.cpu_usage, 0, config.alertCpu, 'CPU');
    checkMetric('ram', metrics.ram_used, metrics.ram_total, config.alertRam, 'RAM');
    checkMetric('disk', metrics.disk_used, metrics.disk_total, config.alertDisk, 'Disk C/Root');
  }
}

export const monitorEngine = new MonitorEngine();
