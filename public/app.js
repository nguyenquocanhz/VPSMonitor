// ================= GLOBAL STATE =================
let unlocked = false;
let sessionToken = null;
let servers = [];
let selectedServerId = null;
let currentView = 'grid'; // 'grid' or 'detail'
let currentSftpPath = '/';
let ws = null;
let reconnectTimeout = null;

// Chart Instances
let cpuChart = null;
let ramChart = null;
let historyChart = null;

// Sliding window data for real-time charts (last 20 points)
const MAX_REALTIME_POINTS = 20;
let cpuRealtimeData = Array(MAX_REALTIME_POINTS).fill(0);
let ramRealtimeData = Array(MAX_REALTIME_POINTS).fill(0);
let realtimeLabels = Array(MAX_REALTIME_POINTS).fill('');

// ================= DOM ELEMENTS =================
const unlockScreen = document.getElementById('unlock-screen');
const appScreen = document.getElementById('app-screen');
const unlockForm = document.getElementById('unlock-form');
const masterKeyInput = document.getElementById('master-key-input');
const unlockError = document.getElementById('unlock-error');

const currentViewTitle = document.getElementById('current-view-title');
const dashboardView = document.getElementById('dashboard-view');
const gridView = document.getElementById('grid-view');
const serverGrid = document.getElementById('server-grid');
const emptyState = document.getElementById('empty-state');

// Navigation Links
const menuHome = document.getElementById('menu-home');
const menuAddServer = document.getElementById('menu-add-server');
const menuTelegram = document.getElementById('menu-telegram');
const btnBackToGrid = document.getElementById('btn-back-to-grid');

// Server Modal Elements
const serverModal = document.getElementById('server-modal');
const serverForm = document.getElementById('server-form');
const modalTitle = document.getElementById('modal-title');
const modalError = document.getElementById('modal-error');
const serverIdInput = document.getElementById('server-id-input');
const serverNameInput = document.getElementById('server-name-input');
const serverOsInput = document.getElementById('server-os-input');
const serverHostInput = document.getElementById('server-host-input');
const serverPortInput = document.getElementById('server-port-input');
const serverUserInput = document.getElementById('server-user-input');
const serverAuthInput = document.getElementById('server-auth-input');
const serverPasswordInput = document.getElementById('server-password-input');
const serverSshKeyInput = document.getElementById('server-sshkey-input');
const serverPassphraseInput = document.getElementById('server-passphrase-input');

const authPasswordGroup = document.getElementById('auth-password-group');
const authSshkeyGroup = document.getElementById('auth-sshkey-group');

const btnCloseModal = document.getElementById('btn-close-modal');
const btnCancelModal = document.getElementById('btn-cancel-modal');
const btnTestConnection = document.getElementById('btn-test-connection');
const btnLockApp = document.getElementById('btn-lock-app');
const btnToggleSidebar = document.getElementById('btn-toggle-sidebar');
const sidebarOverlay = document.getElementById('sidebar-overlay');
const sidebarEl = document.querySelector('.sidebar');

// Telegram Modal Elements
const telegramModal = document.getElementById('telegram-modal');
const telegramForm = document.getElementById('telegram-form');
const telegramEnabledInput = document.getElementById('telegram-enabled-input');
const telegramTokenInput = document.getElementById('telegram-token-input');
const telegramChatIdInput = document.getElementById('telegram-chatid-input');
const telegramCpuInput = document.getElementById('telegram-cpu-input');
const telegramRamInput = document.getElementById('telegram-ram-input');
const telegramDiskInput = document.getElementById('telegram-disk-input');
const telegramStatusInput = document.getElementById('telegram-status-input');
const telegramError = document.getElementById('telegram-error');
const telegramSuccess = document.getElementById('telegram-success');
const btnTestTelegram = document.getElementById('btn-test-telegram');
const btnCancelTelegram = document.getElementById('btn-cancel-telegram');
const btnCloseTelegramModal = document.getElementById('btn-close-telegram-modal');

// Server Details Elements
const detailStatusBadge = document.getElementById('detail-status-badge');
const detailServerName = document.getElementById('detail-server-name');
const detailServerHost = document.getElementById('detail-server-host');
const detailServerOs = document.getElementById('detail-server-os');
const detailServerUptime = document.getElementById('detail-server-uptime');
const networkLatency = document.getElementById('network-latency');
const btnSpeedtest = document.getElementById('btn-speedtest');
const btnEditServer = document.getElementById('btn-edit-server');
const btnDeleteServer = document.getElementById('btn-delete-server');

// Metric Elements
const cpuPctText = document.getElementById('cpu-pct');
const ramPctText = document.getElementById('ram-pct');
const ramUsedText = document.getElementById('ram-used');
const ramTotalText = document.getElementById('ram-total');
const diskPctText = document.getElementById('disk-pct-text');
const diskDetailText = document.getElementById('disk-detail-text');
const cpuRadialBar = document.getElementById('cpu-radial-bar');
const ramRadialBar = document.getElementById('ram-radial-bar');
const diskProgressBar = document.getElementById('disk-progress-bar');

// Speedtest Elements
const speedtestBanner = document.getElementById('speedtest-banner');
const speedUpload = document.getElementById('speed-upload');
const speedDownload = document.getElementById('speed-download');
const speedLatency = document.getElementById('speed-latency');
const btnCloseSpeedtest = document.getElementById('btn-close-speedtest');

// SFTP Tab Elements
const sftpTab = document.getElementById('sftp-tab');
const sftpBreadcrumb = document.getElementById('sftp-breadcrumb');
const btnSftpRefresh = document.getElementById('btn-sftp-refresh');
const btnSftpUpload = document.getElementById('btn-sftp-upload');
const sftpUploadInput = document.getElementById('sftp-upload-input');
const sftpFileList = document.getElementById('sftp-file-list');

// ================= APP INITIALIZATION =================
document.addEventListener('DOMContentLoaded', () => {
  // Inject global CSS for spinner
  const style = document.createElement('style');
  style.id = 'global-spinner-style';
  style.innerHTML = `
    @keyframes spin { 100% { transform:rotate(360deg); } }
    .spinner { border: 2px solid rgba(255,255,255,0.3); border-top-color: white; border-radius: 50%; width: 14px; height: 14px; animation: spin 1s linear infinite; display: inline-block; vertical-align: middle; margin-right: 6px; }
  `;
  document.head.appendChild(style);

  lucide.createIcons();
  checkLockStatus();
  setupEventListeners();
});

// Check lock status from server
async function checkLockStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    
    if (data.unlocked) {
      unlocked = true;
      sessionToken = localStorage.getItem('vps_session_token');
      if (sessionToken) {
        showAppScreen();
        fetchServers();
        initWebSocket();
      } else {
        showUnlockScreen(data.initialized);
      }
    } else {
      unlocked = false;
      showUnlockScreen(data.initialized);
    }
  } catch (err) {
    console.error('Lỗi khi kiểm tra trạng thái khóa:', err);
  }
}

// Show unlock page
function showUnlockScreen(initialized) {
  unlockScreen.classList.remove('hidden');
  appScreen.classList.add('hidden');
  
  const title = document.getElementById('unlock-title');
  const subtitle = document.getElementById('unlock-subtitle');
  const submitBtn = document.getElementById('unlock-submit-btn').querySelector('span');
  
  if (!initialized) {
    title.textContent = 'Khởi tạo VPSMonitor';
    subtitle.textContent = 'Thiết lập Master Key đầu tiên của bạn. Khóa này dùng để mã hóa thông tin tài khoản server vào cơ sở dữ liệu SQLite.';
    submitBtn.textContent = 'Khởi tạo hệ thống';
  } else {
    title.textContent = 'Kích hoạt VPSMonitor';
    subtitle.textContent = 'Nhập Master Key của bạn để giải mã cơ sở dữ liệu.';
    submitBtn.textContent = 'Mở khóa hệ thống';
  }
}

// Show main application
function showAppScreen() {
  unlockScreen.classList.add('hidden');
  appScreen.classList.remove('hidden');
  lucide.createIcons();
}

// ================= EVENT LISTENERS =================
function setupEventListeners() {
  // Unlock Form
  unlockForm.addEventListener('submit', handleUnlockSubmit);
  
  // Lock Button
  btnLockApp.addEventListener('click', () => {
    localStorage.removeItem('vps_session_token');
    window.location.reload();
  });

  // Navigation Links
  menuHome.addEventListener('click', (e) => {
    e.preventDefault();
    navigateToGridView();
  });
  
  menuAddServer.addEventListener('click', (e) => {
    e.preventDefault();
    if (sidebarEl && sidebarOverlay) {
      sidebarEl.classList.remove('sidebar-open');
      sidebarOverlay.classList.add('hidden');
    }
    openServerModal();
  });
  
  menuTelegram.addEventListener('click', (e) => {
    e.preventDefault();
    openTelegramModal();
  });

  btnBackToGrid.addEventListener('click', () => {
    navigateToGridView();
  });

  // Modal controls
  btnWelcomeAdd.addEventListener('click', () => openServerModal());
  btnCloseModal.addEventListener('click', closeServerModal);
  btnCancelModal.addEventListener('click', closeServerModal);
  
  // Toggle Auth Fields
  serverAuthInput.addEventListener('change', (e) => {
    toggleAuthFields(e.target.value);
  });
  
  // Submit Server Form
  serverForm.addEventListener('submit', handleServerFormSubmit);
  
  // Test Connection
  btnTestConnection.addEventListener('click', handleTestConnection);
  
  // Speedtest
  btnSpeedtest.addEventListener('click', runSpeedtest);
  btnCloseSpeedtest.addEventListener('click', () => speedtestBanner.classList.add('hidden'));

  // Edit & Delete Server
  btnEditServer.addEventListener('click', () => {
    const server = servers.find(s => s.id === selectedServerId);
    if (server) openServerModal(server);
  });
  
  btnDeleteServer.addEventListener('click', deleteServer);

  // Tabs Navigation
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const tabId = e.currentTarget.dataset.tab;
      switchTab(tabId);
    });
  });

  // SFTP Operations
  btnSftpRefresh.addEventListener('click', () => {
    loadSftpDirectory(currentSftpPath);
  });
  btnSftpUpload.addEventListener('click', () => {
    sftpUploadInput.click();
  });
  sftpUploadInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
      uploadSftpFiles(e.target.files);
    }
  });

  // Telegram Config Modal Controls
  telegramEnabledInput.addEventListener('change', (e) => {
    toggleTelegramDetails(e.target.checked);
  });
  btnCloseTelegramModal.addEventListener('click', closeTelegramModal);
  btnCancelTelegram.addEventListener('click', closeTelegramModal);
  btnTestTelegram.addEventListener('click', testTelegramConnection);
  telegramForm.addEventListener('submit', handleTelegramFormSubmit);

  // Mobile Sidebar Toggle
  if (btnToggleSidebar && sidebarOverlay && sidebarEl) {
    btnToggleSidebar.addEventListener('click', () => {
      sidebarEl.classList.toggle('sidebar-open');
      sidebarOverlay.classList.toggle('hidden');
    });
    sidebarOverlay.addEventListener('click', () => {
      sidebarEl.classList.remove('sidebar-open');
      sidebarOverlay.classList.add('hidden');
    });
  }
}

// ================= NAVIGATION VIEW SWITCHING =================
function navigateToGridView() {
  currentView = 'grid';
  selectedServerId = null;
  
  gridView.classList.remove('hidden');
  dashboardView.classList.add('hidden');
  
  menuHome.classList.add('active');
  menuTelegram.classList.remove('active');
  
  currentViewTitle.textContent = 'Tổng quan hệ thống';
  
  if (sidebarEl && sidebarOverlay) {
    sidebarEl.classList.remove('sidebar-open');
    sidebarOverlay.classList.add('hidden');
  }
  
  // Re-subscribe to all servers to retrieve real-time mini progress values
  if (ws && ws.readyState === WebSocket.OPEN) {
    servers.forEach(server => {
      ws.send(JSON.stringify({ type: 'subscribe', serverId: server.id }));
    });
  }
}

// ================= AUTHENTICATION (UNLOCK) =================
async function handleUnlockSubmit(e) {
  e.preventDefault();
  const masterKey = masterKeyInput.value;
  
  try {
    const res = await fetch('/api/unlock', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ masterKey })
    });
    
    const data = await res.json();
    if (res.ok && data.success) {
      sessionToken = data.token;
      localStorage.setItem('vps_session_token', sessionToken);
      unlocked = true;
      
      showAppScreen();
      fetchServers();
      initWebSocket();
    } else {
      unlockError.textContent = data.message || 'Master Key không hợp lệ.';
      unlockError.classList.remove('hidden');
    }
  } catch (err) {
    unlockError.textContent = 'Lỗi kết nối tới Server.';
    unlockError.classList.remove('hidden');
  }
}

// ================= WEBSOCKET REAL-TIME =================
function initWebSocket() {
  if (ws) {
    try { ws.close(); } catch(e) {}
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/ws`;
  
  ws = new WebSocket(wsUrl);
  
  ws.onopen = () => {
    console.log('Đã kết nối WebSocket.');
    ws.send(JSON.stringify({ type: 'auth', token: sessionToken }));
  };
  
  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      
      if (data.type === 'auth_success') {
        console.log('Xác thực WebSocket thành công.');
        if (currentView === 'detail' && selectedServerId) {
          ws.send(JSON.stringify({ type: 'subscribe', serverId: selectedServerId }));
        } else if (currentView === 'grid') {
          servers.forEach(s => {
            ws.send(JSON.stringify({ type: 'subscribe', serverId: s.id }));
          });
        }
        return;
      }
      
      if (data.type === 'metrics') {
        // Update detail view if selected
        if (currentView === 'detail' && data.serverId === selectedServerId) {
          updateRealtimeUI(data.metrics);
        }
        
        // Update mini metrics on grid cards
        const cpuBar = document.getElementById(`mini-cpu-bar-${data.serverId}`);
        const cpuText = document.getElementById(`mini-cpu-text-${data.serverId}`);
        const ramBar = document.getElementById(`mini-ram-bar-${data.serverId}`);
        const ramText = document.getElementById(`mini-ram-text-${data.serverId}`);
        
        if (cpuBar) cpuBar.style.width = `${data.metrics.cpu_usage}%`;
        if (cpuText) cpuText.textContent = `${data.metrics.cpu_usage}%`;
        if (ramBar) {
          const ramPct = Math.round((data.metrics.ram_used / data.metrics.ram_total) * 100) || 0;
          ramBar.style.width = `${ramPct}%`;
          if (ramText) ramText.textContent = `${ramPct}%`;
        }
      }
      
      if (data.type === 'status') {
        updateServerStatusInUI(data.serverId, data.status);
      }
      
      if (data.type === 'error') {
        console.error('WebSocket Error message:', data.message);
      }
    } catch (err) {
      console.error('Lỗi phân tích WebSocket frame:', err);
    }
  };
  
  ws.onclose = () => {
    console.log('WebSocket bị đóng. Đang thử kết nối lại sau 3 giây...');
    clearTimeout(reconnectTimeout);
    reconnectTimeout = setTimeout(initWebSocket, 3000);
  };
  
  ws.onerror = (err) => {
    console.error('WebSocket gặp sự cố:', err);
  };
}

// ================= SERVER MANAGEMENT (CRUD) =================
async function fetchServers() {
  try {
    const res = await fetch('/api/servers', {
      headers: { 'Authorization': sessionToken }
    });
    
    if (res.status === 401 || res.status === 403) {
      localStorage.removeItem('vps_session_token');
      window.location.reload();
      return;
    }
    
    servers = await res.json();
    renderServerGrid();
  } catch (err) {
    console.error('Lỗi khi tải danh sách server:', err);
    serverGrid.innerHTML = '<div class="loading-placeholder">Lỗi khi tải danh sách.</div>';
  }
}

// Render server grid list (Home overview)
function renderServerGrid() {
  if (servers.length === 0) {
    serverGrid.classList.add('hidden');
    emptyState.classList.remove('hidden');
    return;
  }
  
  emptyState.classList.add('hidden');
  serverGrid.classList.remove('hidden');
  serverGrid.innerHTML = '';
  
  servers.forEach(server => {
    const card = document.createElement('div');
    card.className = 'server-card';
    card.dataset.id = server.id;
    
    const statusClass = server.status === 'online' ? 'online' : (server.status === 'auth_failed' ? 'auth_failed' : 'offline');
    const osIcon = server.os_type === 'windows' ? 'monitor' : 'server';
    
    card.innerHTML = `
      <div class="server-card-header">
        <div class="server-card-title">
          <span id="status-dot-${server.id}" class="status-dot ${statusClass}"></span>
          <h4>${escapeHtml(server.name)}</h4>
        </div>
        <div class="os-icon">
          <i data-lucide="${osIcon}"></i>
        </div>
      </div>
      <div class="server-card-body">
        <div class="meta-info">
          <i data-lucide="globe"></i>
          <span>${escapeHtml(server.host)}:${server.port}</span>
        </div>
        <div class="mini-metrics">
          <div class="mini-metric">
            <div class="metric-lbl">
              <span>CPU</span>
              <span id="mini-cpu-text-${server.id}">--%</span>
            </div>
            <div class="mini-progress-bg">
              <div id="mini-cpu-bar-${server.id}" class="mini-progress-bar" style="width: 0%"></div>
            </div>
          </div>
          <div class="mini-metric">
            <div class="metric-lbl">
              <span>RAM</span>
              <span id="mini-ram-text-${server.id}">--%</span>
            </div>
            <div class="mini-progress-bg">
              <div id="mini-ram-bar-${server.id}" class="mini-progress-bar" style="width: 0%"></div>
            </div>
          </div>
        </div>
      </div>
      <div class="server-card-footer">
        <button class="btn btn-secondary btn-view-detail">Xem chi tiết</button>
      </div>
    `;
    
    // Bind card click to selectServer
    card.addEventListener('click', (e) => {
      // Do not trigger if clicking buttons specifically, though detail is target path
      selectServer(server.id);
    });
    
    serverGrid.appendChild(card);
  });
  
  lucide.createIcons();
  
  // Send WS subscriptions for all server cards to update their mini resource bars
  if (ws && ws.readyState === WebSocket.OPEN && currentView === 'grid') {
    servers.forEach(server => {
      ws.send(JSON.stringify({ type: 'subscribe', serverId: server.id }));
    });
  }
}

// Select a server to view detailed dashboard
async function selectServer(serverId) {
  const previousServerId = selectedServerId;
  selectedServerId = serverId;
  
  const server = servers.find(s => s.id === serverId);
  if (!server) return;
  
  currentView = 'detail';
  
  // Transition HTML Sections
  gridView.classList.add('hidden');
  dashboardView.classList.remove('hidden');
  menuHome.classList.remove('active');
  
  // Setup detailed header
  currentViewTitle.textContent = `Giám sát: ${server.name}`;
  detailServerName.textContent = server.name;
  detailServerHost.textContent = `${server.host}:${server.port}`;
  detailServerOs.textContent = server.os_type === 'windows' ? 'Windows Server 2019+' : 'Linux (Ubuntu/CentOS/Alma)';
  
  updateServerStatusBadge(server.status);
  
  // Hide speedtest banner in case it was left open
  speedtestBanner.classList.add('hidden');
  
  // Switch to performance tab by default
  switchTab('performance-tab');
  
  // Clear real-time charts data
  cpuRealtimeData.fill(0);
  ramRealtimeData.fill(0);
  
  // Initialize Real-time Charts if not created
  initRealtimeCharts();
  
  // Load 24h metrics history
  await loadMetricsHistory(serverId);

  // Manage WS Subscriptions: unsubscribe from others, subscribe to selected
  if (ws && ws.readyState === WebSocket.OPEN) {
    servers.forEach(s => {
      if (s.id !== serverId) {
        ws.send(JSON.stringify({ type: 'unsubscribe', serverId: s.id }));
      }
    });
    ws.send(JSON.stringify({ type: 'subscribe', serverId }));
  }
}

function updateServerStatusBadge(status) {
  detailStatusBadge.className = 'badge';
  if (status === 'online') {
    detailStatusBadge.classList.add('badge-online');
    detailStatusBadge.textContent = 'Online';
  } else if (status === 'auth_failed') {
    detailStatusBadge.classList.add('badge-warning');
    detailStatusBadge.textContent = 'Auth Error';
  } else {
    detailStatusBadge.classList.add('badge-offline');
    detailStatusBadge.textContent = 'Offline';
  }
}

// Handle status updates broadcasted via WS
function updateServerStatusInUI(serverId, status) {
  const sIdx = servers.findIndex(s => s.id === serverId);
  if (sIdx !== -1) {
    servers[sIdx].status = status;
  }
  
  // Update card status dot
  const cardDot = document.getElementById(`status-dot-${serverId}`);
  if (cardDot) {
    const statusClass = status === 'online' ? 'online' : (status === 'auth_failed' ? 'auth_failed' : 'offline');
    cardDot.className = `status-dot ${statusClass}`;
  }

  // Update detail view if selected
  if (serverId === selectedServerId) {
    updateServerStatusBadge(status);
  }
}

// ================= METRICS DATA DISPLAY =================
function updateRealtimeUI(metrics) {
  // 1. Update text values
  cpuPctText.textContent = `${metrics.cpu_usage}%`;
  
  const ramUsed = parseFloat(metrics.ram_used) || 0;
  const ramTotal = parseFloat(metrics.ram_total) || 0;
  const ramPct = ramTotal > 0 ? Math.round((ramUsed / ramTotal) * 100) : 0;
  
  ramPctText.textContent = `${ramPct}%`;
  ramUsedText.textContent = `${ramUsed.toFixed(1)} GB`;
  ramTotalText.textContent = `${ramTotal.toFixed(1)} GB`;
  
  const diskUsed = parseFloat(metrics.disk_used) || 0;
  const diskTotal = parseFloat(metrics.disk_total) || 0;
  const diskPct = diskTotal > 0 ? Math.round((diskUsed / diskTotal) * 100) : 0;
  
  diskPctText.textContent = `Đã dùng: ${diskPct}%`;
  diskDetailText.textContent = `${diskUsed.toFixed(1)} GB / ${diskTotal.toFixed(1)} GB`;
  detailServerUptime.textContent = metrics.uptime;
  
  // 2. Update radial progress bars
  setRadialProgress(cpuRadialBar, metrics.cpu_usage);
  setRadialProgress(ramRadialBar, ramPct);
  diskProgressBar.style.width = `${diskPct}%`;
  
  // 3. Update real-time charts data
  cpuRealtimeData.push(metrics.cpu_usage);
  cpuRealtimeData.shift();
  
  ramRealtimeData.push(ramPct);
  ramRealtimeData.shift();
  
  // Update without costly animations for minimal GPU usage
  if (cpuChart) cpuChart.update('none');
  if (ramChart) ramChart.update('none');
}

// SVG radial progress bar helper
function setRadialProgress(circleElement, pct) {
  if (!circleElement) return;
  const radius = circleElement.r.baseVal.value;
  const circumference = 2 * Math.PI * radius; // ~213.6 for r=34
  const offset = circumference - (Math.min(100, Math.max(0, pct)) / 100) * circumference;
  circleElement.style.strokeDashoffset = offset;
}

// Fetch historical metrics (24h) and plot history chart
async function loadMetricsHistory(serverId) {
  try {
    const res = await fetch(`/api/servers/${serverId}/metrics`, {
      headers: { 'Authorization': sessionToken }
    });
    const history = await res.json();
    
    plotHistoryChart(history);
  } catch (err) {
    console.error('Lỗi khi tải lịch sử metrics:', err);
  }
}

// ================= SPEEDTEST =================
async function runSpeedtest() {
  if (!selectedServerId) return;
  
  btnSpeedtest.disabled = true;
  const originalHtml = btnSpeedtest.innerHTML;
  btnSpeedtest.innerHTML = '<i class="spinner"></i> <span>Đang đo...</span>';

  try {
    const res = await fetch(`/api/servers/${selectedServerId}/speedtest`, {
      method: 'POST',
      headers: { 'Authorization': sessionToken }
    });
    
    const data = await res.json();
    if (res.ok && data.success) {
      speedUpload.textContent = data.uploadSpeed;
      speedDownload.textContent = data.downloadSpeed;
      speedLatency.textContent = data.pingMs;
      
      networkLatency.textContent = `${data.pingMs} ms`;
      
      speedtestBanner.classList.remove('hidden');
    } else {
      alert(`Đo tốc độ thất bại: ${data.error}`);
    }
  } catch (err) {
    alert('Lỗi kết nối hệ thống khi đo tốc độ.');
  } finally {
    btnSpeedtest.disabled = false;
    btnSpeedtest.innerHTML = originalHtml;
  }
}

// ================= SERVER CREATE/EDIT FORM =================
function openServerModal(server = null) {
  serverModal.classList.remove('hidden');
  modalError.classList.add('hidden');
  
  if (server) {
    // Edit mode
    modalTitle.textContent = 'Chỉnh sửa cấu hình máy chủ';
    serverIdInput.value = server.id;
    serverNameInput.value = server.name;
    serverHostInput.value = server.host;
    serverPortInput.value = server.port;
    serverUserInput.value = server.username;
    serverOsInput.value = server.os_type;
    serverAuthInput.value = server.auth_type;
    
    serverPasswordInput.value = '';
    serverSshKeyInput.value = '';
    serverPassphraseInput.value = '';
    
    toggleAuthFields(server.auth_type);
  } else {
    // Add mode
    modalTitle.textContent = 'Thêm máy chủ mới';
    serverForm.reset();
    serverIdInput.value = '';
    serverPortInput.value = '22';
    toggleAuthFields('password');
  }
  lucide.createIcons();
}

// Close Server Modal
function closeServerModal() {
  serverModal.classList.add('hidden');
  serverForm.reset();
}

function toggleAuthFields(authType) {
  if (authType === 'password') {
    authPasswordGroup.classList.remove('hidden');
    authSshkeyGroup.classList.add('hidden');
    serverPasswordInput.required = !serverIdInput.value; // Required only on creation
    serverSshKeyInput.required = false;
  } else {
    authPasswordGroup.classList.add('hidden');
    authSshkeyGroup.classList.remove('hidden');
    serverPasswordInput.required = false;
    serverSshKeyInput.required = !serverIdInput.value; // Required only on creation
  }
}

// Submit server save
async function handleServerFormSubmit(e) {
  e.preventDefault();
  
  const id = serverIdInput.value;
  const payload = {
    name: serverNameInput.value,
    host: serverHostInput.value,
    port: parseInt(serverPortInput.value) || 22,
    username: serverUserInput.value,
    os_type: serverOsInput.value,
    auth_type: serverAuthInput.value
  };
  
  if (payload.auth_type === 'password') {
    if (serverPasswordInput.value) payload.password = serverPasswordInput.value;
  } else {
    if (serverSshKeyInput.value) payload.ssh_key = serverSshKeyInput.value;
    if (serverPassphraseInput.value) payload.passphrase = serverPassphraseInput.value;
  }

  const url = id ? `/api/servers/${id}` : '/api/servers';
  const method = id ? 'PUT' : 'POST';

  try {
    const res = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': sessionToken
      },
      body: JSON.stringify(payload)
    });
    
    const data = await res.json();
    if (res.ok) {
      closeServerModal();
      await fetchServers();
      if (!id && data.id) {
        selectServer(data.id);
      }
    } else {
      modalError.textContent = data.error || 'Có lỗi xảy ra khi lưu.';
      modalError.classList.remove('hidden');
    }
  } catch (err) {
    modalError.textContent = 'Lỗi kết nối tới Server.';
    modalError.classList.remove('hidden');
  }
}

// Test credentials connection
async function handleTestConnection() {
  btnTestConnection.disabled = true;
  const originalHtml = btnTestConnection.innerHTML;
  btnTestConnection.innerHTML = '<span>Checking...</span>';

  const payload = {
    host: serverHostInput.value,
    port: parseInt(serverPortInput.value) || 22,
    username: serverUserInput.value,
    auth_type: serverAuthInput.value
  };

  if (payload.auth_type === 'password') {
    payload.password = serverPasswordInput.value;
  } else {
    payload.ssh_key = serverSshKeyInput.value;
    payload.passphrase = serverPassphraseInput.value;
  }

  const id = serverIdInput.value;
  if (id) {
    const origServer = servers.find(s => s.id === id);
    if (origServer && origServer.auth_type === payload.auth_type) {
      // Just test connection of already saved server configurations
      try {
        const res = await fetch(`/api/servers/${id}/test`, {
          method: 'POST',
          headers: { 'Authorization': sessionToken }
        });
        const data = await res.json();
        btnTestConnection.disabled = false;
        btnTestConnection.innerHTML = originalHtml;
        alert(res.ok ? 'Kết nối thành công!' : `Kết nối thất bại: ${data.error}`);
        return;
      } catch (err) {
        btnTestConnection.disabled = false;
        btnTestConnection.innerHTML = originalHtml;
        alert('Lỗi kiểm tra kết nối.');
        return;
      }
    }
  }

  try {
    const res = await fetch('/api/servers/test-connection', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': sessionToken
      },
      body: JSON.stringify(payload)
    });
    
    const data = await res.json();
    alert(res.ok ? 'Kết nối thành công!' : `Kết nối thất bại: ${data.error}`);
  } catch (err) {
    alert('Lỗi kiểm tra kết nối.');
  } finally {
    btnTestConnection.disabled = false;
    btnTestConnection.innerHTML = originalHtml;
  }
}

// Delete Server
async function deleteServer() {
  if (!selectedServerId) return;
  const server = servers.find(s => s.id === selectedServerId);
  if (!server) return;
  
  if (!confirm(`Bạn có chắc chắn muốn xóa máy chủ "${server.name}"? Lịch sử giám sát cũng sẽ bị xóa bỏ.`)) {
    return;
  }
  
  try {
    const res = await fetch(`/api/servers/${selectedServerId}`, {
      method: 'DELETE',
      headers: { 'Authorization': sessionToken }
    });
    
    if (res.ok) {
      selectedServerId = null;
      navigateToGridView();
      await fetchServers();
    } else {
      const data = await res.json();
      alert(`Xóa thất bại: ${data.error}`);
    }
  } catch (err) {
    alert('Lỗi kết nối khi xóa máy chủ.');
  }
}

// ================= TELEGRAM CONFIGURATION =================
async function openTelegramModal() {
  telegramError.classList.add('hidden');
  telegramSuccess.classList.add('hidden');
  telegramModal.classList.remove('hidden');
  
  // Update sidebar selection
  document.querySelectorAll('.menu-item').forEach(el => el.classList.remove('active'));
  menuTelegram.classList.add('active');
  
  if (sidebarEl && sidebarOverlay) {
    sidebarEl.classList.remove('sidebar-open');
    sidebarOverlay.classList.add('hidden');
  }

  try {
    const res = await fetch('/api/config/telegram', {
      headers: { 'Authorization': sessionToken }
    });
    
    if (res.ok) {
      const config = await res.json();
      telegramEnabledInput.checked = config.enabled;
      telegramTokenInput.value = config.bot_token || '';
      telegramChatIdInput.value = config.chat_id || '';
      telegramCpuInput.value = config.alert_cpu || 90;
      telegramRamInput.value = config.alert_ram || 90;
      telegramDiskInput.value = config.alert_disk || 90;
      telegramStatusInput.checked = config.alert_status;
      
      toggleTelegramDetails(config.enabled);
    }
  } catch (err) {
    console.error('Lỗi tải cấu hình Telegram:', err);
  }
}

function closeTelegramModal() {
  telegramModal.classList.add('hidden');
  if (currentView === 'grid') {
    menuHome.classList.add('active');
    menuTelegram.classList.remove('active');
  }
}

function toggleTelegramDetails(enabled) {
  const detailsGroup = document.getElementById('telegram-details-group');
  if (enabled) {
    detailsGroup.classList.remove('hidden');
  } else {
    detailsGroup.classList.add('hidden');
  }
}

async function handleTelegramFormSubmit(e) {
  e.preventDefault();
  telegramError.classList.add('hidden');
  telegramSuccess.classList.add('hidden');
  
  const enabled = telegramEnabledInput.checked;
  const botToken = telegramTokenInput.value;
  const chatId = telegramChatIdInput.value;
  const cpuLimit = parseInt(telegramCpuInput.value) || 90;
  const ramLimit = parseInt(telegramRamInput.value) || 90;
  const diskLimit = parseInt(telegramDiskInput.value) || 90;
  const statusAlert = telegramStatusInput.checked;
  
  if (enabled && (!botToken || !chatId)) {
    telegramError.textContent = 'Vui lòng điền đầy đủ Token Bot và Chat ID khi kích hoạt.';
    telegramError.classList.remove('hidden');
    return;
  }
  
  try {
    const res = await fetch('/api/config/telegram', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': sessionToken
      },
      body: JSON.stringify({
        enabled,
        bot_token: botToken,
        chat_id: chatId,
        alert_cpu: cpuLimit,
        alert_ram: ramLimit,
        alert_disk: diskLimit,
        alert_status: statusAlert
      })
    });
    
    if (res.ok) {
      telegramSuccess.textContent = 'Lưu cấu hình thành công!';
      telegramSuccess.classList.remove('hidden');
      setTimeout(() => {
        closeTelegramModal();
      }, 1500);
    } else {
      const data = await res.json();
      telegramError.textContent = data.error || 'Lỗi khi lưu cấu hình.';
      telegramError.classList.remove('hidden');
    }
  } catch (err) {
    telegramError.textContent = 'Lỗi kết nối tới Server.';
    telegramError.classList.remove('hidden');
  }
}

async function testTelegramConnection() {
  const botToken = telegramTokenInput.value;
  const chatId = telegramChatIdInput.value;
  telegramError.classList.add('hidden');
  telegramSuccess.classList.add('hidden');
  
  if (!botToken || !chatId) {
    alert('Vui lòng điền Bot Token và Chat ID trước khi test.');
    return;
  }
  
  btnTestTelegram.disabled = true;
  const originalHtml = btnTestTelegram.innerHTML;
  btnTestTelegram.innerHTML = '<span>Đang gửi...</span>';
  
  try {
    const res = await fetch('/api/config/telegram/test', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': sessionToken
      },
      body: JSON.stringify({ bot_token: botToken, chat_id: chatId })
    });
    
    const data = await res.json();
    if (res.ok && data.success) {
      alert('Gửi tin nhắn thử nghiệm thành công! Hãy kiểm tra bot Telegram của bạn.');
    } else {
      alert(`Kiểm tra thất bại: ${data.error || 'Lỗi API'}`);
    }
  } catch (err) {
    alert('Lỗi kết nối hệ thống khi test.');
  } finally {
    btnTestTelegram.disabled = false;
    btnTestTelegram.innerHTML = originalHtml;
  }
}

// ================= TABS NAVIGATION CONTROLLER =================
function switchTab(tabId) {
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tabId);
  });
  
  document.querySelectorAll('.tab-content').forEach(content => {
    content.classList.toggle('hidden', content.id !== tabId);
  });
  
  if (tabId === 'sftp-tab') {
    currentSftpPath = '/';
    loadSftpDirectory('/');
  }
}

// ================= SFTP FILE TRAVERSAL & ACTIONS =================
function cleanPath(path) {
  let cleaned = path.replace(/\/+/g, '/');
  if (!cleaned.startsWith('/')) {
    cleaned = '/' + cleaned;
  }
  if (cleaned.length > 1 && cleaned.endsWith('/')) {
    cleaned = cleaned.slice(0, -1);
  }
  return cleaned;
}

async function loadSftpDirectory(path) {
  currentSftpPath = cleanPath(path);
  sftpFileList.innerHTML = '<tr><td colspan="4" class="loading-placeholder">Đang tải danh sách tệp tin...</td></tr>';
  
  updateSftpBreadcrumbs();
  
  try {
    const res = await fetch(`/api/servers/${selectedServerId}/sftp/list?path=${encodeURIComponent(currentSftpPath)}`, {
      headers: { 'Authorization': sessionToken }
    });
    
    if (!res.ok) {
      const data = await res.json();
      sftpFileList.innerHTML = `<tr><td colspan="4" class="error-msg text-center" style="padding: 20px; color: var(--status-error);">Lỗi: ${escapeHtml(data.error)}</td></tr>`;
      return;
    }
    
    const data = await res.json();
    const items = data.items;
    
    if (items.length === 0 && currentSftpPath === '/') {
      sftpFileList.innerHTML = '<tr><td colspan="4" class="loading-placeholder">Thư mục gốc trống.</td></tr>';
      return;
    }
    
    sftpFileList.innerHTML = '';
    
    // Add directory parent folder `..` to traverse upwards
    if (currentSftpPath !== '/' && currentSftpPath !== '') {
      const parentRow = document.createElement('tr');
      parentRow.innerHTML = `
        <td>
          <div class="sftp-item-name-cell" style="cursor: pointer;">
            <i data-lucide="corner-left-up" class="sftp-item-icon file"></i>
            <span>.. (Thư mục cha)</span>
          </div>
        </td>
        <td>--</td>
        <td>--</td>
        <td></td>
      `;
      
      parentRow.querySelector('.sftp-item-name-cell').addEventListener('click', () => {
        const parts = currentSftpPath.split('/').filter(p => p !== '');
        parts.pop();
        const parentPath = '/' + parts.join('/');
        loadSftpDirectory(parentPath);
      });
      
      sftpFileList.appendChild(parentRow);
    }
    
    items.forEach(item => {
      // Ignore directory pointers . and .. returned by shell
      if (item.name === '.' || item.name === '..') return;
      
      const row = document.createElement('tr');
      
      let icon = 'file';
      let iconClass = 'file';
      if (item.isDirectory) {
        icon = 'folder';
        iconClass = 'folder';
      } else if (item.isLink) {
        icon = 'link';
        iconClass = 'link';
      }
      
      const fullItemPath = cleanPath(currentSftpPath + '/' + item.name);
      
      row.innerHTML = `
        <td>
          <div class="sftp-item-name-cell" data-path="${escapeHtml(fullItemPath)}" data-type="${item.isDirectory ? 'directory' : 'file'}">
            <i data-lucide="${icon}" class="sftp-item-icon ${iconClass}"></i>
            <span>${escapeHtml(item.name)}</span>
          </div>
        </td>
        <td>${item.isDirectory ? '--' : formatBytes(item.size)}</td>
        <td>${formatDate(item.mtime)}</td>
        <td>
          <div class="sftp-action-buttons">
            ${!item.isDirectory ? `
              <button class="btn btn-secondary btn-icon-only btn-sftp-download" data-path="${escapeHtml(fullItemPath)}" title="Tải xuống">
                <i data-lucide="download"></i>
              </button>
            ` : ''}
            <button class="btn btn-danger btn-icon-only btn-sftp-delete" data-path="${escapeHtml(fullItemPath)}" data-type="${item.isDirectory ? 'directory' : 'file'}" title="Xóa">
              <i data-lucide="trash-2"></i>
            </button>
          </div>
        </td>
      `;
      
      // Directory click traversal
      if (item.isDirectory) {
        row.querySelector('.sftp-item-name-cell').addEventListener('click', () => {
          loadSftpDirectory(fullItemPath);
        });
      }
      
      // Download handler
      if (!item.isDirectory) {
        row.querySelector('.btn-sftp-download').addEventListener('click', (e) => {
          const path = e.currentTarget.dataset.path;
          downloadSftpFile(path);
        });
      }
      
      // Delete handler
      row.querySelector('.btn-sftp-delete').addEventListener('click', (e) => {
        const path = e.currentTarget.dataset.path;
        const type = e.currentTarget.dataset.type;
        deleteSftpItem(path, type);
      });
      
      sftpFileList.appendChild(row);
    });
    
    lucide.createIcons({
      attrs: {
        'stroke-width': 2
      },
      nameAttr: 'data-lucide',
      nodeList: sftpFileList.querySelectorAll('[data-lucide]')
    });
    
  } catch (err) {
    console.error('Lỗi khi tải thư mục SFTP:', err);
    sftpFileList.innerHTML = '<tr><td colspan="4" class="error-msg text-center" style="padding:20px; color: var(--status-error);">Lỗi kết nối tải tệp tin.</td></tr>';
  }
}

function updateSftpBreadcrumbs() {
  sftpBreadcrumb.innerHTML = '';
  
  // Root pointer
  const rootSpan = document.createElement('span');
  rootSpan.className = 'sftp-breadcrumb-item';
  rootSpan.textContent = '/';
  rootSpan.addEventListener('click', () => loadSftpDirectory('/'));
  sftpBreadcrumb.appendChild(rootSpan);
  
  if (currentSftpPath === '/' || currentSftpPath === '') return;
  
  const parts = currentSftpPath.split('/').filter(p => p !== '');
  let builtPath = '';
  
  parts.forEach(part => {
    const separator = document.createElement('span');
    separator.className = 'sftp-breadcrumb-separator';
    separator.textContent = ' / ';
    sftpBreadcrumb.appendChild(separator);
    
    builtPath += '/' + part;
    const targetPath = builtPath; // Bind closure variable
    
    const span = document.createElement('span');
    span.className = 'sftp-breadcrumb-item';
    span.textContent = part;
    span.addEventListener('click', () => loadSftpDirectory(targetPath));
    sftpBreadcrumb.appendChild(span);
  });
}

function downloadSftpFile(path) {
  const url = `/api/servers/${selectedServerId}/sftp/download?path=${encodeURIComponent(path)}&token=${sessionToken}`;
  const a = document.createElement('a');
  a.href = url;
  a.download = path.split('/').pop() || 'download';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

async function uploadSftpFiles(files) {
  const originalHtml = btnSftpUpload.innerHTML;
  btnSftpUpload.disabled = true;

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    btnSftpUpload.innerHTML = `<i class="spinner"></i> <span>Tải lên (${i+1}/${files.length})...</span>`;
    
    const remotePath = cleanPath(currentSftpPath + '/' + file.name);
    
    try {
      const res = await fetch(`/api/servers/${selectedServerId}/sftp/upload?path=${encodeURIComponent(remotePath)}`, {
        method: 'POST',
        headers: {
          'Authorization': sessionToken
        },
        body: file // Direct streaming of raw file payload
      });
      
      const data = await res.json();
      if (!res.ok || !data.success) {
        alert(`Không thể tải lên file ${file.name}: ${data.error || 'Lỗi không xác định'}`);
      }
    } catch (err) {
      alert(`Lỗi kết nối tải lên file ${file.name}: ${err.message}`);
    }
  }

  btnSftpUpload.disabled = false;
  btnSftpUpload.innerHTML = originalHtml;
  sftpUploadInput.value = ''; // Reset input element
  loadSftpDirectory(currentSftpPath);
}

async function deleteSftpItem(path, type) {
  const name = path.split('/').pop();
  const typeLabel = type === 'directory' ? 'thư mục' : 'tệp tin';
  
  if (!confirm(`Bạn có chắc chắn muốn xóa ${typeLabel} "${name}"? Thư mục chỉ xóa được khi trống.`)) {
    return;
  }
  
  try {
    const res = await fetch(`/api/servers/${selectedServerId}/sftp/delete?path=${encodeURIComponent(path)}&type=${type}`, {
      method: 'DELETE',
      headers: { 'Authorization': sessionToken }
    });
    
    const data = await res.json();
    if (res.ok && data.success) {
      loadSftpDirectory(currentSftpPath);
    } else {
      alert(`Xóa thất bại: ${data.error || 'Lỗi API'}`);
    }
  } catch (err) {
    alert('Lỗi kết nối khi xóa tệp.');
  }
}

// ================= CHART CONFIGURATIONS (CHART.JS) =================
function initRealtimeCharts() {
  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: { display: false },
      y: {
        min: 0,
        max: 100,
        grid: { color: 'rgba(255, 255, 255, 0.05)' },
        ticks: { color: '#9ca3af', font: { family: 'Outfit', size: 9 } }
      }
    },
    elements: {
      point: { radius: 0 },
      line: { tension: 0.3 }
    }
  };

  if (cpuChart) cpuChart.destroy();
  if (ramChart) ramChart.destroy();

  const cpuCtx = document.getElementById('cpu-realtime-chart').getContext('2d');
  cpuChart = new Chart(cpuCtx, {
    type: 'line',
    data: {
      labels: realtimeLabels,
      datasets: [{
        data: cpuRealtimeData,
        borderColor: '#6366f1',
        borderWidth: 2,
        fill: false
      }]
    },
    options: chartOptions
  });

  const ramCtx = document.getElementById('ram-realtime-chart').getContext('2d');
  ramChart = new Chart(ramCtx, {
    type: 'line',
    data: {
      labels: realtimeLabels,
      datasets: [{
        data: ramRealtimeData,
        borderColor: '#a855f7',
        borderWidth: 2,
        fill: false
      }]
    },
    options: chartOptions
  });
}

function plotHistoryChart(history) {
  if (historyChart) historyChart.destroy();

  const labels = history.map(h => {
    const d = new Date(h.timestamp);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  });
  
  const cpuHistData = history.map(h => h.cpu_usage);
  const ramHistData = history.map(h => {
    const used = parseFloat(h.ram_used) || 0;
    const total = parseFloat(h.ram_total) || 0;
    return total > 0 ? Math.round((used / total) * 100) : 0;
  });

  const ctx = document.getElementById('history-chart').getContext('2d');
  
  const cpuGrad = ctx.createLinearGradient(0, 0, 0, 250);
  cpuGrad.addColorStop(0, 'rgba(99, 102, 241, 0.12)');
  cpuGrad.addColorStop(1, 'rgba(99, 102, 241, 0.0)');

  const ramGrad = ctx.createLinearGradient(0, 0, 0, 250);
  ramGrad.addColorStop(0, 'rgba(168, 85, 247, 0.12)');
  ramGrad.addColorStop(1, 'rgba(168, 85, 247, 0.0)');

  // Dynamically scale maxTicksLimit based on browser screen width for optimized mobile UX
  const screenWidth = window.innerWidth;
  const maxTicks = screenWidth < 480 ? 4 : (screenWidth < 768 ? 8 : 12);

  historyChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: 'Sử dụng CPU (%)',
          data: cpuHistData,
          borderColor: '#6366f1',
          borderWidth: 2,
          fill: true,
          backgroundColor: cpuGrad,
          tension: 0.2
        },
        {
          label: 'Sử dụng RAM (%)',
          data: ramHistData,
          borderColor: '#a855f7',
          borderWidth: 2,
          fill: true,
          backgroundColor: ramGrad,
          tension: 0.2
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: true,
          labels: { color: '#9ca3af', font: { family: 'Outfit' } }
        }
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { 
            color: '#9ca3af', 
            font: { family: 'Outfit', size: 10 },
            maxTicksLimit: maxTicks
          }
        },
        y: {
          min: 0,
          max: 100,
          grid: { color: 'rgba(255, 255, 255, 0.05)' },
          ticks: { color: '#9ca3af', font: { family: 'Outfit', size: 10 } }
        }
      },
      elements: {
        point: { radius: 0 }
      }
    }
  });
}

// ================= UTILITY FUNCTIONS =================
function escapeHtml(str) {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function formatBytes(bytes, decimals = 2) {
  if (!+bytes) return '0 Bytes';
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
}

function formatDate(timestamp) {
  if (!timestamp) return '--';
  const d = new Date(timestamp);
  return `${d.toLocaleDateString('vi-VN')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
