package com.example.vpsmonitor.ui.main

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavKey
import com.example.vpsmonitor.data.PreferenceManager
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.network.getAppSignatureSHA256
import com.example.vpsmonitor.ui.dialogs.AddServerDialog
import com.example.vpsmonitor.ui.dialogs.EditServerDialog
import com.example.vpsmonitor.ui.tabs.DashboardTab
import com.example.vpsmonitor.ui.tabs.LanScanTab
import com.example.vpsmonitor.ui.tabs.MultiServerTab
import com.example.vpsmonitor.ui.tabs.SftpExplorerTab
import com.example.vpsmonitor.ui.tabs.SshTerminalTab
import com.example.vpsmonitor.ssh.runSshTestConnection
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  
  // 1. Run migration from standard plaintext prefs to secure prefs (OPSec requirement)
  LaunchedEffect(Unit) {
    PreferenceManager.migrateToSecure(context)
  }

  val sharedPreferences = remember {
    PreferenceManager.getSecurePrefs(context)
  }

  // App launch policy state check
  var policyAccepted by remember {
    mutableStateOf(sharedPreferences.getBoolean("policy_accepted", false))
  }

  // State Lists
  var serversList by remember {
    mutableStateOf(PreferenceManager.getSavedServers(sharedPreferences))
  }

  var selectedServerId by remember {
    mutableStateOf(sharedPreferences.getString("active_server_id", "") ?: "")
  }

  val activeServer = remember(serversList, selectedServerId) {
    if (serversList.size == 1) {
      serversList.firstOrNull()
    } else {
      serversList.firstOrNull { it.id == selectedServerId }
    }
  }

  // Ensure that if we only have 1 server, selectedServerId is set to it
  LaunchedEffect(serversList) {
    if (serversList.size == 1) {
      val singleSrv = serversList.first()
      if (selectedServerId != singleSrv.id) {
        selectedServerId = singleSrv.id
        sharedPreferences.edit().putString("active_server_id", singleSrv.id).apply()
      }
    }
  }

  // Active Tab Index: 0 = Dashboard, 1 = SFTP Files, 2 = Remote Terminal, 3 = LAN Scan
  var activeTab by remember { mutableStateOf(0) }

  // Overlay control states
  var showSidebar by remember { mutableStateOf(false) }
  var showAddDialog by remember { mutableStateOf(false) }
  var showEditServer by remember { mutableStateOf<VpsServer?>(null) }
  var showDeleteConfirm by remember { mutableStateOf<VpsServer?>(null) }

  // Subnet scan IP prefill
  var prefillIpAddress by remember { mutableStateOf("") }
  var showLanScanDialog by remember { mutableStateOf(false) }

  // Migration from old single-server format (if any legacy format)
  LaunchedEffect(Unit) {
    val oldUrl = sharedPreferences.getString("server_url", "") ?: ""
    if (oldUrl.isNotBlank() && serversList.isEmpty()) {
      var host = oldUrl.replace("http://", "").replace("https://", "")
      val portIdx = host.indexOf(':')
      var port = 22
      if (portIdx != -1) {
        port = host.substring(portIdx + 1).toIntOrNull() ?: 22
        host = host.substring(0, portIdx)
      }
      val newSrv = VpsServer(
        name = "Default Server",
        host = host,
        port = port,
        username = "root",
        password = "", 
        osType = "linux"
      )
      val migrated = listOf(newSrv)
      PreferenceManager.saveServers(sharedPreferences, migrated)
      serversList = migrated
      selectedServerId = newSrv.id
      sharedPreferences.edit().putString("active_server_id", newSrv.id).remove("server_url").apply()
    }
  }

  if (!policyAccepted) {
    PolicyScreen(
      onAccepted = {
        sharedPreferences.edit().putBoolean("policy_accepted", true).apply()
        policyAccepted = true
      }
    )
  } else {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0F172A))
    ) {
      if (serversList.isEmpty()) {
        OnboardingScreen(
          prefillHost = prefillIpAddress,
          onServerAdded = { srv ->
            val newList = serversList + srv
            PreferenceManager.saveServers(sharedPreferences, newList)
            serversList = newList
            selectedServerId = srv.id
            sharedPreferences.edit().putString("active_server_id", srv.id).apply()
          },
          onOpenLanScan = {
            showLanScanDialog = true
          }
        )
      } else {
        Scaffold(
          topBar = {
            TopAppBar(
              title = {
                Column {
                  Text(
                    text = if (activeTab == 3) "MẠNG LAN" else (activeServer?.name ?: "VPS MONITOR"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                  )
                  Text(
                    text = if (activeTab == 3) "Subnet Device Discovery" 
                           else if (activeServer != null) activeServer.host 
                           else "Multi-Server Overview",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                  )
                }
              },
              navigationIcon = {
                if (selectedServerId.isNotEmpty() && serversList.size > 1) {
                  IconButton(onClick = {
                    selectedServerId = ""
                    sharedPreferences.edit().putString("active_server_id", "").apply()
                  }) {
                    Icon(
                      imageVector = Icons.Default.ArrowBack,
                      contentDescription = "Back to Overview",
                      tint = Color(0xFF38BDF8)
                    )
                  }
                } else {
                  IconButton(onClick = { showSidebar = true }) {
                    Icon(
                      imageVector = Icons.Default.Menu,
                      contentDescription = "Open Sidebar",
                      tint = Color(0xFF38BDF8)
                    )
                  }
                }
              },
              colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1E293B)
              )
            )
          },
          bottomBar = {
            NavigationBar(
              containerColor = Color(0xFF1E293B)
            ) {
              NavigationBarItem(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                icon = { Text("📊", fontSize = 20.sp) },
                label = { Text("Dashboard", color = Color.White) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = Color(0xFF38BDF8),
                  unselectedIconColor = Color(0xFF94A3B8),
                  indicatorColor = Color(0xFF0F172A)
                )
              )
              NavigationBarItem(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                icon = { Text("📁", fontSize = 20.sp) },
                label = { Text("Files (SFTP)", color = Color.White) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = Color(0xFF38BDF8),
                  unselectedIconColor = Color(0xFF94A3B8),
                  indicatorColor = Color(0xFF0F172A)
                )
              )
              NavigationBarItem(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                icon = { Text("💻", fontSize = 20.sp) },
                label = { Text("Terminal", color = Color.White) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = Color(0xFF38BDF8),
                  unselectedIconColor = Color(0xFF94A3B8),
                  indicatorColor = Color(0xFF0F172A)
                )
              )
              NavigationBarItem(
                selected = activeTab == 3,
                onClick = { activeTab = 3 },
                icon = { Text("🌐", fontSize = 20.sp) },
                label = { Text("LAN Scan", color = Color.White) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = Color(0xFF38BDF8),
                  unselectedIconColor = Color(0xFF94A3B8),
                  indicatorColor = Color(0xFF0F172A)
                )
              )
            }
          },
          containerColor = Color(0xFF0F172A)
        ) { innerPadding ->
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(innerPadding)
          ) {
            AnimatedContent(
              targetState = activeTab,
              transitionSpec = {
                fadeIn() togetherWith fadeOut()
              },
              label = "TabSwitcher"
            ) { targetTab ->
              when (targetTab) {
                0 -> {
                  if (activeServer == null) {
                    MultiServerTab(
                      servers = serversList,
                      onServerSelect = { srv ->
                        selectedServerId = srv.id
                        sharedPreferences.edit().putString("active_server_id", srv.id).apply()
                      },
                      onTerminalClick = { srv ->
                        selectedServerId = srv.id
                        sharedPreferences.edit().putString("active_server_id", srv.id).apply()
                        activeTab = 2
                      },
                      onFilesClick = { srv ->
                        selectedServerId = srv.id
                        sharedPreferences.edit().putString("active_server_id", srv.id).apply()
                        activeTab = 1
                      },
                      onAddServerClick = {
                        prefillIpAddress = ""
                        showAddDialog = true
                      }
                    )
                  } else {
                    DashboardTab(server = activeServer)
                  }
                }
                1 -> {
                  if (activeServer != null) {
                    SftpExplorerTab(server = activeServer)
                  } else {
                    PlaceholderSelectServer("Quản lý tệp tin (SFTP)")
                  }
                }
                2 -> {
                  if (activeServer != null) {
                    SshTerminalTab(server = activeServer)
                  } else {
                    PlaceholderSelectServer("Dòng lệnh Terminal")
                  }
                }
                3 -> LanScanTab(onSelectDevice = { ip ->
                  prefillIpAddress = ip
                  showAddDialog = true
                })
              }
            }
          }
        }
      }

      if (showSidebar) {
        SidebarDrawer(
          servers = serversList,
          activeId = selectedServerId,
          onClose = { showSidebar = false },
          onServerSelect = { srvId ->
            selectedServerId = srvId
            sharedPreferences.edit().putString("active_server_id", srvId).apply()
            showSidebar = false
          },
          onAddClick = {
            prefillIpAddress = ""
            showAddDialog = true
            showSidebar = false
          },
          onEditClick = { srv ->
            showEditServer = srv
            showSidebar = false
          },
          onDeleteClick = { srv ->
            showDeleteConfirm = srv
            showSidebar = false
          }
        )
      }

      if (showAddDialog) {
        AddServerDialog(
          prefillHost = prefillIpAddress,
          onDismiss = { showAddDialog = false },
          onServerAdded = { srv ->
            val newList = serversList + srv
            PreferenceManager.saveServers(sharedPreferences, newList)
            serversList = newList
            selectedServerId = srv.id
            sharedPreferences.edit().putString("active_server_id", srv.id).apply()
            showAddDialog = false
          }
        )
      }

      showEditServer?.let { serverToEdit ->
        EditServerDialog(
          server = serverToEdit,
          onDismiss = { showEditServer = null },
          onServerUpdated = { updatedSrv ->
            val newList = serversList.map { if (it.id == updatedSrv.id) updatedSrv else it }
            PreferenceManager.saveServers(sharedPreferences, newList)
            serversList = newList
            showEditServer = null
          }
        )
      }

      showDeleteConfirm?.let { serverToDelete ->
        AlertDialog(
          onDismissRequest = { showDeleteConfirm = null },
          title = { Text("Delete server configuration?") },
          text = { Text("Are you sure you want to delete '${serverToDelete.name}'? This action cannot be undone.") },
          confirmButton = {
            TextButton(
              onClick = {
                val newList = serversList.filter { it.id != serverToDelete.id }
                PreferenceManager.saveServers(sharedPreferences, newList)
                serversList = newList
                if (selectedServerId == serverToDelete.id) {
                  selectedServerId = newList.firstOrNull()?.id ?: ""
                  sharedPreferences.edit().putString("active_server_id", selectedServerId).apply()
                }
                showDeleteConfirm = null
              }
            ) {
              Text("Delete", color = Color(0xFFEF4444))
            }
          },
          dismissButton = {
            TextButton(onClick = { showDeleteConfirm = null }) {
              Text("Cancel")
            }
          }
        )
      }

      if (showLanScanDialog) {
        Dialog(
          onDismissRequest = { showLanScanDialog = false },
          properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(Color(0xFF0F172A))
              .safeDrawingPadding()
              .padding(16.dp)
          ) {
            Column(modifier = Modifier.fillMaxSize()) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text("Scan Local Area Network (LAN)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = { showLanScanDialog = false }) {
                  Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
              }
              Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LanScanTab(
                  onSelectDevice = { ip ->
                    prefillIpAddress = ip
                    showLanScanDialog = false
                  }
                )
              }
            }
          }
        }
      }
    }
  }
}

// ------------------------------------------------------------------------
// POLICY / TERMS OF SERVICE CONSENT SCREEN
// ------------------------------------------------------------------------
@Composable
fun PolicyScreen(
  onAccepted: () -> Unit
) {
  var isChecked by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF0F172A))
      .safeDrawingPadding()
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      modifier = Modifier.fillMaxWidth().wrapContentHeight(),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
      Column(
        modifier = Modifier.padding(20.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "ĐIỀU KHOẢN SỬ DỤNG & CHÍNH SÁCH BẢO MẬT",
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF38BDF8),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .padding(12.dp)
        ) {
          val scroll = rememberScrollState()
          Column(
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(scroll)
          ) {
            Text(
              text = "Chào mừng bạn đến với ứng dụng di động VPSMonitor Mobile (Phiên bản độc lập).\n\n" +
                "1. Cam kết Bảo mật Dữ liệu:\n" +
                "Ứng dụng VPSMonitor được thiết kế để kết nối trực tiếp từ thiết bị di động của bạn tới máy chủ VPS cá nhân qua giao thức SSH và SFTP mã hóa. Mọi dữ liệu nhạy cảm bao gồm IP, Port, Username và Mật khẩu SSH được mã hóa phần cứng AES-256 an toàn lưu trữ cục bộ trên thiết bị của bạn. Chúng tôi KHÔNG thu thập, không chuyển giao và không lưu trữ bất kỳ mật khẩu hoặc thông tin máy chủ nào của bạn lên bất kỳ máy chủ bên thứ ba nào.\n\n" +
                "2. Trách nhiệm người dùng:\n" +
                "Người dùng tự chịu hoàn toàn trách nhiệm bảo mật thiết bị di động của mình nhằm tránh việc truy cập trái phép vào ứng dụng. Bạn đồng ý tự chịu mọi rủi ro liên quan đến cấu hình hệ thống, sửa đổi file qua SFTP, hoặc thực thi lệnh shell trong Terminal của máy chủ VPS của bạn thông qua ứng dụng này.\n\n" +
                "3. Bản quyền phần mềm:\n" +
                "Ứng dụng được phát triển và duy trì bởi nhà phát triển Nguyễn Quốc Anh (nqatech). Bằng việc bấm nút Chấp nhận ở bên dưới, bạn đồng ý với các điều khoản nêu trên.",
              fontSize = 11.sp,
              color = Color(0xFFCBD5E1),
              lineHeight = 16.sp
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth().clickable { isChecked = !isChecked },
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = isChecked,
            onCheckedChange = { isChecked = it },
            colors = CheckboxDefaults.colors(
              checkedColor = Color(0xFF0EA5E9),
              uncheckedColor = Color(0xFF475569)
            )
          )
          Text(
            text = "Tôi đã đọc, hiểu và đồng ý chấp nhận tất cả điều khoản sử dụng và chính sách trên.",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(start = 4.dp),
            lineHeight = 15.sp
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
          onClick = onAccepted,
          enabled = isChecked,
          modifier = Modifier.fillMaxWidth().height(48.dp),
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF0EA5E9),
            disabledContainerColor = Color(0xFF334155)
          )
        ) {
          Text("Đồng Ý & Tiếp Tục", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

// ------------------------------------------------------------------------
// ONBOARDING SETUP SCREEN
// ------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
  prefillHost: String,
  onServerAdded: (VpsServer) -> Unit,
  onOpenLanScan: () -> Unit
) {
  var name by remember { mutableStateOf("") }
  var host by remember { mutableStateOf(prefillHost) }
  var port by remember { mutableStateOf("22") }
  var username by remember { mutableStateOf("root") }
  var password by remember { mutableStateOf("") }
  var authType by remember { mutableStateOf("password") }
  var privateKey by remember { mutableStateOf("") }
  var passphrase by remember { mutableStateOf("") }
  var osType by remember { mutableStateOf("linux") }
  var strictHostKeyChecking by remember { mutableStateOf(false) }

  var isTesting by remember { mutableStateOf(false) }
  var testError by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(prefillHost) {
    if (prefillHost.isNotBlank()) {
      host = prefillHost
      if (name.isBlank()) name = "Local VPS"
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF0F172A))
      .safeDrawingPadding()
      .padding(20.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "VPS MONITOR",
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF38BDF8),
          modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
          text = "Connect a VPS server directly over SSH to begin real-time resource tracking.",
          fontSize = 12.sp,
          color = Color(0xFF94A3B8),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Display Name", color = Color(0xFF94A3B8)) },
          placeholder = { Text("My Linux Server") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
          )
        )

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
          OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host (IP/Domain)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            modifier = Modifier.weight(0.7f).padding(end = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White, unfocusedTextColor = Color.White,
              focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
            )
          )

          OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port", color = Color(0xFF94A3B8)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.3f),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White, unfocusedTextColor = Color.White,
              focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
            )
          )
        }

        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("Username", color = Color(0xFF94A3B8)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
          )
        )

        // Auth Type Selector
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .padding(4.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (authType == "password") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { authType = "password" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Mật khẩu", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (authType == "key") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { authType = "key" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Khóa SSH (PEM)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
        }

        if (authType == "password") {
          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password", color = Color(0xFF94A3B8)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White, unfocusedTextColor = Color.White,
              focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
            )
          )
        } else {
          OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it },
            label = { Text("Private Key (PEM)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(bottom = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White, unfocusedTextColor = Color.White,
              focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
            )
          )

          OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase (Tùy chọn)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("Để trống nếu không có") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White, unfocusedTextColor = Color.White,
              focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569)
            )
          )
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .padding(4.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (osType == "linux") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "linux" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Linux VPS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (osType == "windows") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "windows" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Windows Server", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
        }

        // StrictHostKeyChecking Switch
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = strictHostKeyChecking,
            onCheckedChange = { strictHostKeyChecking = it },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0EA5E9), uncheckedColor = Color(0xFF475569))
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Verify SSH Host Key (MitM Protection)", color = Color.White, fontSize = 11.sp)
        }

        testError?.let { err ->
          Text(
            text = "Connection error: $err",
            color = Color(0xFFEF4444),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 12.dp)
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Button(
            onClick = onOpenLanScan,
            modifier = Modifier.weight(0.45f).height(46.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF38BDF8))
          ) {
            Icon(Icons.Default.Search, contentDescription = "Scan LAN", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Scan LAN", color = Color(0xFF38BDF8), fontSize = 12.sp)
          }

          Spacer(modifier = Modifier.width(10.dp))

          Button(
            onClick = {
              if (name.isBlank() || host.isBlank()) {
                testError = "Please fill in all required fields."
                return@Button
              }
              if (authType == "password" && password.isBlank()) {
                testError = "Please fill in the password."
                return@Button
              }
              if (authType == "key" && privateKey.isBlank()) {
                testError = "Please fill in the private key (PEM)."
                return@Button
              }
              val parsedPort = port.toIntOrNull() ?: 22
              val srv = VpsServer(
                name = name.trim(),
                host = host.trim(),
                port = parsedPort,
                username = username.trim(),
                password = if (authType == "password") password else "",
                authType = authType,
                privateKey = if (authType == "key") privateKey else "",
                passphrase = if (authType == "key") passphrase else "",
                osType = osType,
                strictHostKeyChecking = strictHostKeyChecking
              )

              scope.launch {
                isTesting = true
                testError = null
                val err = runSshTestConnection(srv)
                isTesting = false
                if (err == null) {
                  onServerAdded(srv)
                } else {
                  testError = err
                }
              }
            },
            enabled = !isTesting,
            modifier = Modifier.weight(0.55f).height(46.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
          ) {
            if (isTesting) {
              CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Text("Connect", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

// ------------------------------------------------------------------------
// SIDEBAR DRAWER OVERLAY
// ------------------------------------------------------------------------
@Composable
fun SidebarDrawer(
  servers: List<VpsServer>,
  activeId: String,
  onClose: () -> Unit,
  onServerSelect: (String) -> Unit,
  onAddClick: () -> Unit,
  onEditClick: (VpsServer) -> Unit,
  onDeleteClick: (VpsServer) -> Unit
) {
  val context = LocalContext.current
  val signature = remember { getAppSignatureSHA256(context) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0x99000000))
      .clickable { onClose() }
  ) {
    Box(
      modifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth(0.85f)
        .background(Color(0xFF0F172A))
        .clickable(enabled = false) {}
        .padding(16.dp)
        .safeDrawingPadding()
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("VPS MONITOR", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
          IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
          }
        }

        Text(
          "Servers List",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF94A3B8),
          modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
          items(servers) { server ->
            val isActive = server.id == activeId
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onServerSelect(server.id) },
              colors = CardDefaults.cardColors(
                containerColor = if (isActive) Color(0xFF1E293B) else Color(0xFF0F172A)
              ),
              border = if (isActive) BorderStroke(1.dp, Color(0xFF38BDF8)) else null
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = if (server.osType == "linux") "🐧" else "🪟",
                  fontSize = 20.sp,
                  modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    server.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                  )
                  Text(
                    server.host,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                  )
                }

                IconButton(onClick = { onEditClick(server) }, modifier = Modifier.size(32.dp)) {
                  Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                }

                IconButton(onClick = { onDeleteClick(server) }, modifier = Modifier.size(32.dp)) {
                  Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                }
              }
            }
          }

          item {
            Button(
              onClick = onAddClick,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF38BDF8))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Add VPS Server", color = Color.White)
            }
          }
        }

        HorizontalDivider(color = Color(0xFF334155), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

        // Info and developer section
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .padding(12.dp)
        ) {
          Text("App Info & Developer", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), modifier = Modifier.padding(bottom = 6.dp))
          Text("Nhà phát triển: Nguyễn Quốc Anh", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
          Text("Brand: nqatech (nguyenquocanhz)", fontSize = 11.sp, color = Color(0xFFCBD5E1))
          Text("Email: support@nqatech.com", fontSize = 11.sp, color = Color(0xFFCBD5E1))
          Text("Version: 1.3.0 (Secure Build)", fontSize = 11.sp, color = Color(0xFF94A3B8))
          Spacer(modifier = Modifier.height(6.dp))
          Text("App SHA-256 Signature (Encrypted Vault):", fontSize = 10.sp, color = Color(0xFF94A3B8))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
              .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = signature.take(18) + "...",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFF10B981),
              modifier = Modifier.weight(1f)
            )
            Icon(
              imageVector = Icons.Default.Share,
              contentDescription = "Copy Signature",
              tint = Color(0xFF38BDF8),
              modifier = Modifier
                .size(16.dp)
                .clickable {
                  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                  val clip = android.content.ClipData.newPlainText("APK Signature", signature)
                  clipboard.setPrimaryClip(clip)
                  Toast.makeText(context, "Signature copied!", Toast.LENGTH_SHORT).show()
                }
            )
          }
        }
      }
    }
  }
}

@Composable
fun PlaceholderSelectServer(title: String) {
  Box(
    modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("💻", fontSize = 48.sp)
      Spacer(modifier = Modifier.height(16.dp))
      Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Vui lòng chọn một máy chủ từ màn hình Dashboard để sử dụng tính năng này.",
        color = Color(0xFF94A3B8),
        fontSize = 13.sp,
        textAlign = TextAlign.Center
      )
    }
  }
}
