package com.example.vpsmonitor.ui.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpsmonitor.data.VpsMetrics
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.ssh.LINUX_POLL_CMD
import com.example.vpsmonitor.ssh.WINDOWS_POLL_CMD
import com.example.vpsmonitor.ssh.parseMetrics
import com.example.vpsmonitor.ssh.runSshCommand
import kotlinx.coroutines.delay

@Composable
fun MultiServerTab(
  servers: List<VpsServer>,
  onServerSelect: (VpsServer) -> Unit,
  onTerminalClick: (VpsServer) -> Unit,
  onFilesClick: (VpsServer) -> Unit,
  onAddServerClick: () -> Unit
) {
  var globalRefreshTrigger by remember { mutableStateOf(0) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Header Section
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Active Nodes Overview",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
          Text(
            text = "Monitoring ${servers.size} server${if (servers.size > 1) "s" else ""} in parallel",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
          )
        }
        
        Row {
          IconButton(
            onClick = { globalRefreshTrigger++ },
            modifier = Modifier.size(36.dp)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh All", tint = Color(0xFF38BDF8))
          }
          
          Spacer(modifier = Modifier.width(8.dp))
          
          IconButton(
            onClick = onAddServerClick,
            modifier = Modifier.size(36.dp).background(Color(0xFF1E293B), RoundedCornerShape(18.dp))
          ) {
            Icon(Icons.Default.Add, contentDescription = "Add Server", tint = Color.White)
          }
        }
      }
    }

    // Servers List
    items(servers, key = { it.id }) { server ->
      ServerStatusCard(
        server = server,
        refreshTrigger = globalRefreshTrigger,
        onClick = { onServerSelect(server) },
        onTerminalClick = { onTerminalClick(server) },
        onFilesClick = { onFilesClick(server) }
      )
    }
  }
}

@Composable
fun ServerStatusCard(
  server: VpsServer,
  refreshTrigger: Int,
  onClick: () -> Unit,
  onTerminalClick: () -> Unit,
  onFilesClick: () -> Unit
) {
  var metrics by remember { mutableStateOf(VpsMetrics()) }
  var isConnecting by remember { mutableStateOf(false) }
  var isError by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf("") }
  var localRefreshTrigger by remember { mutableStateOf(0) }

  // Background polling loop for this specific server card
  LaunchedEffect(server, refreshTrigger, localRefreshTrigger) {
    isConnecting = true
    isError = false
    val cmd = if (server.osType == "linux") LINUX_POLL_CMD else WINDOWS_POLL_CMD
    val result = runSshCommand(server, cmd)
    isConnecting = false

    if (result.second.isNotBlank() && result.first.isBlank()) {
      isError = true
      errorMsg = result.second
    } else {
      isError = false
      metrics = parseMetrics(result.first, server.osType)
    }

    // Polling loop every 10 seconds
    while (true) {
      delay(10000)
      val autoResult = runSshCommand(server, cmd)
      if (autoResult.first.isNotBlank()) {
        isError = false
        metrics = parseMetrics(autoResult.first, server.osType)
      } else if (autoResult.second.isNotBlank()) {
        isError = true
        errorMsg = autoResult.second
      }
    }
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp)
      .clickable { onClick() },
    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
    shape = RoundedCornerShape(12.dp)
  ) {
    Column(
      modifier = Modifier.padding(16.dp).fillMaxWidth()
    ) {
      // Header: Name, Host, OS Icon, and Status
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = if (server.osType == "linux") "🐧" else "🪟",
            fontSize = 20.sp,
            modifier = Modifier.padding(end = 8.dp)
          )
          Column {
            Text(
              text = server.name,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              fontSize = 15.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = server.host,
              fontSize = 11.sp,
              color = Color(0xFF94A3B8)
            )
          }
        }

        // Status Indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .clip(RoundedCornerShape(5.dp))
              .background(
                if (isConnecting) Color(0xFFF59E0B)
                else if (isError) Color(0xFFF43F5E)
                else Color(0xFF10B981)
              )
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (isConnecting) "Thử..." else if (isError) "Offline" else "Online",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isConnecting) Color(0xFFF59E0B) else if (isError) Color(0xFFF43F5E) else Color(0xFF10B981)
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      if (isError) {
        // Quick fail message
        Text(
          text = "Lỗi kết nối: ${errorMsg.take(60)}${if (errorMsg.length > 60) "..." else ""}",
          color = Color(0xFFF43F5E),
          fontSize = 11.sp,
          modifier = Modifier.padding(bottom = 8.dp)
        )
      } else {
        // CPU, RAM, Disk summary bars
        val ramPct = if (metrics.ramTotal > 0) (metrics.ramUsed / metrics.ramTotal * 100.0).toFloat() else 0f
        val diskPct = if (metrics.diskTotal > 0) (metrics.diskUsed / metrics.diskTotal).toFloat() else 0f

        // CPU Bar
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("CPU Usage", fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text("${metrics.cpuUsage}%", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
          }
          Spacer(modifier = Modifier.height(4.dp))
          LinearProgressIndicator(
            progress = { (metrics.cpuUsage / 100f).toFloat() },
            color = Color(0xFF38BDF8),
            trackColor = Color(0xFF334155),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
          )
        }

        // RAM Bar
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Memory (RAM)", fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text("${ramPct.toInt()}%", fontSize = 11.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
          }
          Spacer(modifier = Modifier.height(4.dp))
          LinearProgressIndicator(
            progress = { ramPct / 100f },
            color = Color(0xFF10B981),
            trackColor = Color(0xFF334155),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
          )
        }

        // Disk Bar
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Disk Storage", fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text("${(diskPct * 100).toInt()}%", fontSize = 11.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
          }
          Spacer(modifier = Modifier.height(4.dp))
          LinearProgressIndicator(
            progress = { diskPct },
            color = Color(0xFFF59E0B),
            trackColor = Color(0xFF334155),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Bottom Row: Quick Action Buttons & Click Indicator
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row {
          // Terminal button
          TextButton(
            onClick = onTerminalClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
          ) {
            Text("💻 Terminal", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
          }
          
          Spacer(modifier = Modifier.width(8.dp))
          
          // Files button
          TextButton(
            onClick = onFilesClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
          ) {
            Text("📁 Files (SFTP)", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
          }
        }

        // Detailed view link
        Text(
          text = "Chi tiết 📊",
          fontSize = 11.sp,
          color = Color(0xFF94A3B8),
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}
