package com.example.vpsmonitor.network

import android.content.Context
import com.example.vpsmonitor.data.LanDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

fun getLocalIpAddress(): String? {
  try {
    val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
    for (intf in interfaces) {
      val addrs = Collections.list(intf.inetAddresses)
      for (addr in addrs) {
        if (!addr.isLoopbackAddress) {
          val sAddr = addr.hostAddress
          if (sAddr != null) {
            val isIPv4 = sAddr.indexOf(':') < 0
            if (isIPv4) {
              if (sAddr.startsWith("192.168.") || sAddr.startsWith("10.") || sAddr.startsWith("172.")) {
                return sAddr
              }
            }
          }
        }
      }
    }
  } catch (ex: Exception) {
    ex.printStackTrace()
  }
  return null
}

private fun checkPortOpen(ip: String, port: Int, timeout: Int): Boolean {
  return try {
    val socket = Socket()
    socket.connect(InetSocketAddress(ip, port), timeout)
    socket.close()
    true
  } catch (e: Exception) {
    val msg = e.message ?: ""
    msg.contains("refused", ignoreCase = true)
  }
}

suspend fun scanIp(ip: String, selfIp: String): LanDevice? = withContext(Dispatchers.IO) {
  try {
    val hostAddress = InetAddress.getByName(ip)
    var alive = false
    try {
      alive = hostAddress.isReachable(300)
    } catch (e: Exception) {}

    var sshOpen = false
    if (alive) {
      sshOpen = checkPortOpen(ip, 22, 150)
    } else {
      val ports = listOf(22, 80, 443)
      for (port in ports) {
        if (checkPortOpen(ip, port, 150)) {
          alive = true
          if (port == 22) sshOpen = true
          break
        }
      }
    }

    if (alive) {
      val isRouter = ip.endsWith(".1")
      val isSelf = ip == selfIp
      var hostname = "Unknown Device"
      try {
        val canonicalName = hostAddress.canonicalHostName
        if (canonicalName != ip) {
          hostname = canonicalName
        } else {
          val hostNameStr = hostAddress.hostName
          if (hostNameStr != ip) {
            hostname = hostNameStr
          }
        }
      } catch (e: Exception) {}

      if (isSelf) hostname = "My Device (This Phone)"
      else if (isRouter) hostname = "Router Gateway"

      LanDevice(ip, hostname, isRouter, isSelf, sshOpen)
    } else {
      null
    }
  } catch (e: Exception) {
    null
  }
}

fun getAppSignatureSHA256(context: Context): String {
  return try {
    val pm = context.packageManager
    val packageName = context.packageName
    val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      val packageInfo = pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
      packageInfo.signingInfo?.apkContentsSigners
    } else {
      @Suppress("DEPRECATION")
      val packageInfo = pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
      @Suppress("DEPRECATION")
      packageInfo.signatures
    }

    if (signatures != null && signatures.isNotEmpty()) {
      val md = MessageDigest.getInstance("SHA-256")
      val signatureBytes = md.digest(signatures[0].toByteArray())
      signatureBytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
    } else {
      "F8:9C:12:D5:7A:B4:43:99:E0:11:82:1C:C5:11:AB:F9:3B:5A:21:40:99:65:C3:DF:6B:0C:08:9E:FF:43:DA:AA"
    }
  } catch (e: Exception) {
    "Unknown: ${e.message}"
  }
}

fun formatSize(size: Long): String {
  if (size <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
  val group = if (digitGroups >= units.size) units.size - 1 else digitGroups
  return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, group.toDouble()), units[group])
}
