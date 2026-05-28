package com.example.vpsmonitor.ui.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpsmonitor.data.VpsMetrics
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.network.formatSize
import com.example.vpsmonitor.ssh.LINUX_POLL_CMD
import com.example.vpsmonitor.ssh.WINDOWS_POLL_CMD
import com.example.vpsmonitor.ssh.parseMetrics
import com.example.vpsmonitor.ssh.runSshCommand
import kotlinx.coroutines.delay

@Composable
fun DashboardTab(server: VpsServer) {
  var metrics by remember { mutableStateOf(VpsMetrics()) }
  var isConnecting by remember { mutableStateOf(false) }
  var isError by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf("") }
  var refreshTrigger by remember { mutableStateOf(0) }

  // Rolling history for real-time line charts (15 points max)
  val maxHistoryPoints = 15
  val cpuHistory = remember { mutableStateListOf<Float>().apply { addAll(List(maxHistoryPoints) { 0f }) } }
  val ramHistory = remember { mutableStateListOf<Float>().apply { addAll(List(maxHistoryPoints) { 0f }) } }

  LaunchedEffect(server, refreshTrigger) {
    isConnecting = true
    isError = false
    val cmd = if (server.osType == "linux") LINUX_POLL_CMD else WINDOWS_POLL_CMD
    val result = runSshCommand(server, cmd)
    isConnecting = false
    
    if (result.second.isNotBlank() && result.first.isBlank()) {
      isError = true
      errorMsg = result.second
    } else {
      val parsed = parseMetrics(result.first, server.osType)
      metrics = parsed
      
      // Update charts history
      cpuHistory.removeAt(0)
      cpuHistory.add(parsed.cpuUsage.toFloat())
      
      val ramPct = if (parsed.ramTotal > 0) (parsed.ramUsed / parsed.ramTotal * 100.0).toFloat() else 0f
      ramHistory.removeAt(0)
      ramHistory.add(ramPct)
    }

    // Polling loop
    while (true) {
      delay(10000)
      val autoResult = runSshCommand(server, cmd)
      if (autoResult.first.isNotBlank()) {
        isError = false
        val parsed = parseMetrics(autoResult.first, server.osType)
        metrics = parsed
        
        cpuHistory.removeAt(0)
        cpuHistory.add(parsed.cpuUsage.toFloat())
        
        val ramPct = if (parsed.ramTotal > 0) (parsed.ramUsed / parsed.ramTotal * 100.0).toFloat() else 0f
        ramHistory.removeAt(0)
        ramHistory.add(ramPct)
      }
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // 1. Connection Status Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0x33FFFFFF))
      ) {
        Row(
          modifier = Modifier.padding(16.dp).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                  if (isConnecting) Color(0xFFF59E0B)
                  else if (isError) Color(0xFFF43F5E)
                  else Color(0xFF10B981)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (isConnecting) "Connecting..." else if (isError) "Offline" else "Online",
              fontWeight = FontWeight.Bold,
              color = Color.White,
              fontSize = 14.sp
            )
          }

          IconButton(
            onClick = { refreshTrigger++ },
            enabled = !isConnecting,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8))
          }
        }
      }
    }

    // Error message card
    if (isError) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0x33F43F5E)),
          border = BorderStroke(1.dp, Color(0xFFF43F5E))
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("Connection Failed", fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E))
            Spacer(modifier = Modifier.height(4.dp))
            Text(errorMsg, fontSize = 12.sp, color = Color.White)
          }
        }
      }
    }

    // 2. CPU Usage Card with Real-time Chart
    item {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
      ) {
        Column(
          modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("CPU Usage", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Text(
              text = "${metrics.cpuUsage}%",
              fontWeight = FontWeight.Bold,
              color = Color(0xFF38BDF8),
              fontSize = 15.sp
            )
          }
          
          Spacer(modifier = Modifier.height(12.dp))
          
          // Draw Canvas Line Chart
          LineChart(history = cpuHistory, color = Color(0xFF38BDF8))
          
          if (metrics.cpuTemp > 0.0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "CPU Temperature: ${metrics.cpuTemp}°C",
              fontSize = 11.sp,
              color = if (metrics.cpuTemp > 75.0) Color(0xFFF43F5E) else Color(0xFF94A3B8)
            )
          }
        }
      }
    }

    // 3. RAM Usage Card with Real-time Chart
    item {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
      ) {
        Column(
          modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
          val ramPct = if (metrics.ramTotal > 0) (metrics.ramUsed / metrics.ramTotal * 100.0).toFloat() else 0f
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Memory (RAM)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Text(
              text = "${metrics.ramUsed} GB / ${metrics.ramTotal} GB (${ramPct.toInt()}%)",
              color = Color(0xFF10B981),
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp
            )
          }
          
          Spacer(modifier = Modifier.height(12.dp))
          
          // Draw Canvas Line Chart
          LineChart(history = ramHistory, color = Color(0xFF10B981))
        }
      }
    }

    // 4. Disk Card (Root C/)
    item {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
      ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Disk Storage", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Text("${metrics.diskUsed} GB / ${metrics.diskTotal} GB", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
          Spacer(modifier = Modifier.height(10.dp))
          val diskPct = if (metrics.diskTotal > 0) (metrics.diskUsed / metrics.diskTotal).toFloat() else 0f
          LinearProgressIndicator(
            progress = { diskPct },
            color = Color(0xFFF59E0B),
            trackColor = Color(0xFF475569),
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "${(diskPct * 100).toInt()}% Used",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End)
          )
        }
      }
    }

    // 5. Network Interfaces Diagnostics Card
    if (metrics.networkInterfaces.isNotEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
          border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
          Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Network Diagnostics", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(10.dp))

            for (net in metrics.networkInterfaces) {
              Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(net.name, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 13.sp)
                  if (net.errors > 0) {
                    Text("⚠️ Errors: ${net.errors}", color = Color(0xFFF43F5E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                  } else {
                    Text("🟢 OK", color = Color(0xFF10B981), fontSize = 11.sp)
                  }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Column {
                    Text("RX: ${net.rxPackets} packets", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Text("    (${formatSize(net.rxBytes)})", fontSize = 9.sp, color = Color(0xFF94A3B8))
                  }
                  Column(horizontalAlignment = Alignment.End) {
                    Text("TX: ${net.txPackets} packets", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Text("    (${formatSize(net.txBytes)})", fontSize = 9.sp, color = Color(0xFF94A3B8))
                  }
                }
                HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp, modifier = Modifier.padding(top = 8.dp))
              }
            }
          }
        }
      }
    }

    // 6. Running Processes Card
    if (metrics.processes.isNotEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
          border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
          Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Top Processes", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
              modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("NAME", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.4f))
              Text("PID", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f), textAlign = TextAlign.End)
              Text("CPU%", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f), textAlign = TextAlign.End)
              Text(if (server.osType == "linux") "MEM%" else "RAM MB", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f), textAlign = TextAlign.End)
            }

            for (proc in metrics.processes.take(10)) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = proc.name,
                  fontSize = 11.sp,
                  color = Color.White,
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.weight(0.4f),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = proc.pid,
                  fontSize = 11.sp,
                  color = Color(0xFFCBD5E1),
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.weight(0.2f),
                  textAlign = TextAlign.End
                )
                Text(
                  text = "${proc.cpu}%",
                  fontSize = 11.sp,
                  color = if (proc.cpu > 50.0) Color(0xFFF43F5E) else if (proc.cpu > 20.0) Color(0xFFF59E0B) else Color(0xFF10B981),
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.weight(0.2f),
                  textAlign = TextAlign.End
                )
                Text(
                  text = if (server.osType == "linux") "${proc.mem}%" else "${proc.mem}M",
                  fontSize = 11.sp,
                  color = Color(0xFFCBD5E1),
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.weight(0.2f),
                  textAlign = TextAlign.End
                )
              }
            }
          }
        }
      }
    }

    // 7. System Info Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
      ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
          Text("System Information", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
          Spacer(modifier = Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Operating System", color = Color(0xFF94A3B8), fontSize = 13.sp)
            Text(server.osType.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Uptime", color = Color(0xFF94A3B8), fontSize = 13.sp)
            Text(metrics.uptime, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

/**
 * Premium Line Chart drawn via Canvas with gradients.
 */
@Composable
fun LineChart(history: List<Float>, color: Color) {
  Canvas(
    modifier = Modifier
      .fillMaxWidth()
      .height(130.dp)
      .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
      .padding(8.dp)
  ) {
    val width = size.width
    val height = size.height

    // 1. Draw horizontal grid dashed lines
    val gridCount = 4
    for (i in 1..gridCount) {
      val y = height * i / (gridCount + 1)
      drawLine(
        color = Color(0x11FFFFFF),
        start = androidx.compose.ui.geometry.Offset(0f, y),
        end = androidx.compose.ui.geometry.Offset(width, y),
        strokeWidth = 1f
      )
    }

    if (history.size < 2) return@Canvas

    val pointsCount = history.size
    val stepX = width / (pointsCount - 1)

    // 2. Build the smooth curve path
    val path = Path()
    val fillPath = Path()

    for (i in history.indices) {
      val value = history[i].coerceIn(0f, 100f)
      val x = i * stepX
      val y = height - (value / 100f) * height

      if (i == 0) {
        path.moveTo(x, y)
        fillPath.moveTo(x, height)
        fillPath.lineTo(x, y)
      } else {
        path.lineTo(x, y)
        fillPath.lineTo(x, y)
      }
      
      if (i == history.lastIndex) {
        fillPath.lineTo(x, height)
        fillPath.close()
      }
    }

    // 3. Draw gradient area fill under the line
    val gradientBrush = Brush.verticalGradient(
      colors = listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.0f)),
      startY = 0f,
      endY = height
    )
    drawPath(path = fillPath, brush = gradientBrush)

    // 4. Draw the main stroke line
    drawPath(
      path = path,
      color = color,
      style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    )
  }
}
