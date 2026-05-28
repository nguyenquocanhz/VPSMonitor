package com.example.vpsmonitor.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpsmonitor.data.LanDevice
import com.example.vpsmonitor.network.getLocalIpAddress
import com.example.vpsmonitor.network.scanIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LanScanTab(
  onSelectDevice: (String) -> Unit = {}
) {
  var selfIpAddress by remember { mutableStateOf("") }
  var subnetRange by remember { mutableStateOf("Unknown") }
  val discoveredDevices = remember { mutableStateListOf<LanDevice>() }

  var isScanning by remember { mutableStateOf(false) }
  var scannedCount by remember { mutableStateOf(0) }
  var progressFloat by remember { mutableStateOf(0f) }
  val scope = rememberCoroutineScope()

  // Get local IP on initialization
  LaunchedEffect(Unit) {
    val localIp = getLocalIpAddress()
    if (localIp != null) {
      selfIpAddress = localIp
      val lastDot = localIp.lastIndexOf('.')
      if (lastDot != -1) {
        subnetRange = localIp.substring(0, lastDot + 1) + "0/24"
      }
    }
  }

  val startScan: () -> Unit = {
    if (!isScanning && selfIpAddress.isNotBlank()) {
      scope.launch {
        isScanning = true
        discoveredDevices.clear()
        scannedCount = 0
        progressFloat = 0f

        val prefix = selfIpAddress.substring(0, selfIpAddress.lastIndexOf('.') + 1)
        
        // Scan 254 IPs concurrently in coroutines
        val jobs = (1..254).map { i ->
          async(Dispatchers.IO) {
            val ip = "$prefix$i"
            val device = scanIp(ip, selfIpAddress)
            withContext(Dispatchers.Main) {
              scannedCount++
              progressFloat = scannedCount.toFloat() / 254f
              if (device != null) {
                discoveredDevices.add(device)
              }
            }
          }
        }
        jobs.awaitAll()
        isScanning = false
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp)
  ) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
      border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
      Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text("Local Network Scanner", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text("My Local IP", fontSize = 12.sp, color = Color(0xFF94A3B8))
          Text(selfIpAddress.ifBlank { "Disconnected" }, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text("Subnet Range", fontSize = 12.sp, color = Color(0xFF94A3B8))
          Text(subnetRange, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isScanning) {
          Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
              progress = { progressFloat },
              color = Color(0xFF38BDF8),
              trackColor = Color(0xFF475569),
              modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Scanning network: $scannedCount/254 IPs (${(progressFloat * 100).toInt()}%)",
              fontSize = 11.sp,
              color = Color(0xFF38BDF8),
              modifier = Modifier.align(Alignment.CenterHorizontally)
            )
          }
        } else {
          Button(
            onClick = startScan,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Default.Search, contentDescription = "Scan", tint = Color.White)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Bắt đầu quét mạng LAN", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }
      }
    }

    Text(
      text = "Đã tìm thấy ${discoveredDevices.size} thiết bị trực tuyến",
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = Color(0xFF94A3B8),
      modifier = Modifier.padding(bottom = 6.dp)
    )

    LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
      if (discoveredDevices.isEmpty()) {
        item {
          Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
              text = if (isScanning) "Đang tìm kiếm thiết bị..." else "Bấm nút bắt đầu để quét thiết bị.",
              color = Color(0xFF475569),
              fontSize = 13.sp
            )
          }
        }
      } else {
        items(discoveredDevices) { device ->
          Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (device.isSelf) Color(0xFF0B1F33) else Color(0xFF1E293B)
            ),
            border = if (device.isSshOpen) BorderStroke(1.dp, Color(0xFF10B981)) else null
          ) {
            Row(
              modifier = Modifier.padding(12.dp).fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = if (device.isRouter) "🌐" else if (device.isSelf) "📱" else "🖥️",
                fontSize = 22.sp,
                modifier = Modifier.padding(end = 12.dp)
              )
              
              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(device.ip, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  if (device.isSelf) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                      modifier = Modifier
                        .background(Color(0xFF0EA5E9), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                      Text("Self", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                  }
                  if (device.isSshOpen) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                      modifier = Modifier
                        .background(Color(0xFF10B981), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                      Text("SSH Open", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                  }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(device.hostname, fontSize = 12.sp, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
              }

              if (device.isSshOpen && !device.isSelf) {
                Button(
                  onClick = { onSelectDevice(device.ip) },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                  shape = RoundedCornerShape(6.dp),
                  modifier = Modifier.wrapContentSize()
                ) {
                  Text("Connect", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      }
    }
  }
}
