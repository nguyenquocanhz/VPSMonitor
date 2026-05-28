package com.example.vpsmonitor.ssh

import com.example.vpsmonitor.data.SftpFileItem
import com.example.vpsmonitor.data.VpsMetrics
import com.example.vpsmonitor.data.VpsNetworkInterface
import com.example.vpsmonitor.data.VpsProcess
import com.example.vpsmonitor.data.VpsServer
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

// Polling command constants
const val LINUX_POLL_CMD = "echo \"===CPU===\" && top -bn1 | grep -i \"cpu(s)\" | head -n 1 && echo \"===RAM===\" && free -m && echo \"===DISK===\" && df -m / && echo \"===UPTIME===\" && uptime -p && echo \"===PROCESSES===\" && ps -eo pid,%cpu,%mem,comm --sort=-%cpu | head -n 11 && echo \"===NETPACKETS===\" && cat /proc/net/dev | tail -n +3 && echo \"===TEMP===\" && (cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || cat /sys/class/thermal/thermal_zone1/temp 2>/dev/null || cat /sys/class/hwmon/hwmon*/temp1_input 2>/dev/null || echo 0)"

const val WINDOWS_POLL_CMD = "powershell -Command \"Write-Output '===CPU==='; (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average; Write-Output '===RAM==='; \$os = Get-CimInstance Win32_OperatingSystem; Write-Output \\\"\$([Math]::Round((\$os.TotalVisibleMemorySize - \$os.FreePhysicalMemory)/1024, 0)) \$([Math]::Round(\$os.TotalVisibleMemorySize/1024, 0))\\\"; Write-Output '===DISK==='; \$disk = Get-CimInstance Win32_LogicalDisk -Filter 'DeviceID=\\\"C:\\\"'; Write-Output \\\"\$([Math]::Round((\$disk.Size - \$disk.FreeSpace)/1GB, 1)) \$([Math]::Round(\$disk.Size/1GB, 1))\\\"; Write-Output '===UPTIME==='; \$uptime = (Get-Date) - \$os.LastBootUpTime; Write-Output \\\"\$([Math]::Floor(\$uptime.TotalDays))d \$(\$uptime.Hours)h \$(\$uptime.Minutes)m\\\"; Write-Output '===PROCESSES==='; Get-Process | Sort-Object CPU -Descending | Select-Object -First 10 | ForEach-Object { '{0} {1} {2} {3}' -f \$_.Id, [Math]::Round(\$_.CPU, 1), [Math]::Round(\$_.WorkingSet/1MB, 1), \$_.ProcessName }; Write-Output '===NETPACKETS==='; Get-NetAdapterStatistics | ForEach-Object { '{0} {1} {2} {3} {4} {5}' -f \$_.Name, \$_.ReceivedPackets, \$_.SentPackets, \$_.ReceivedBytes, \$_.SentBytes, (\$_.ReceivedPacketErrors + \$_.OutboundPacketErrors) }; Write-Output '===TEMP==='; \$t = Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty CurrentTemperature; if (\$t) { [Math]::Round((\$t - 2732)/10, 1) } else { 0 }\""

/**
 * Executes a command on the target server using the connection pool.
 */
suspend fun runSshCommand(server: VpsServer, command: String): Pair<String, String> = withContext(Dispatchers.IO) {
  var channel: ChannelExec? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("exec") as ChannelExec
    channel.setCommand(command)

    val outStream = ByteArrayOutputStream()
    val errStream = ByteArrayOutputStream()
    channel.outputStream = outStream
    channel.setErrStream(errStream)

    channel.connect(5000)

    val startTime = System.currentTimeMillis()
    while (!channel.isClosed && channel.isConnected && session.isConnected && System.currentTimeMillis() - startTime < 15000) {
      delay(100)
    }

    Pair(outStream.toString("UTF-8"), errStream.toString("UTF-8"))
  } catch (e: Exception) {
    // If command execution fails, close session from pool to avoid cache corruption
    SshSessionPool.closeSession(server.id)
    Pair("", e.toString())
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Tests connection with a brand new session (doesn't cache it).
 */
suspend fun runSshTestConnection(server: VpsServer): String? = withContext(Dispatchers.IO) {
  var session: Session? = null
  try {
    // We bypass the pool for testing raw connection
    val jsch = com.jcraft.jsch.JSch()
    session = jsch.getSession(server.username, server.host, server.port)
    if (server.authType == "key") {
      val privateKeyBytes = server.privateKey.trim().toByteArray(Charsets.UTF_8)
      val passphraseBytes = if (server.passphrase.isNotEmpty()) {
        server.passphrase.toByteArray(Charsets.UTF_8)
      } else {
        null
      }
      jsch.addIdentity(server.id, privateKeyBytes, null, passphraseBytes)
    } else {
      session.setPassword(server.password)
    }
    session.setConfig("StrictHostKeyChecking", if (server.strictHostKeyChecking) "yes" else "no")
    session.setTimeout(5000)
    session.connect(5000)
    null // Success
  } catch (e: Exception) {
    e.toString()
  } finally {
    try { session?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Lists directory contents using SFTP with the connection pool.
 */
suspend fun runSftpList(server: VpsServer, directory: String): Pair<List<SftpFileItem>?, String?> = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    val absPath = if (directory == "." || directory.isEmpty()) {
      channel.pwd()
    } else {
      directory
    }

    channel.cd(absPath)
    val vector = channel.ls(absPath)
    val list = mutableListOf<SftpFileItem>()
    for (item in vector) {
      val entry = item as? ChannelSftp.LsEntry ?: continue
      val name = entry.filename
      if (name == "." || name == "..") continue
      val attrs = entry.attrs
      val isDir = attrs.isDir
      val size = attrs.size
      val permissions = attrs.permissionsString
      val mTime = attrs.mTime.toLong() * 1000L

      list.add(
        SftpFileItem(
          name = name,
          path = if (absPath.endsWith("/")) "$absPath$name" else "$absPath/$name",
          size = size,
          isDir = isDir,
          permissions = permissions,
          lastModified = mTime
        )
      )
    }

    val sortedList = list.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    Pair(sortedList, absPath)
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    Pair(null, e.toString())
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Reads file contents using SFTP.
 */
suspend fun runSftpRead(server: VpsServer, filePath: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    val attrs = channel.stat(filePath)
    if (attrs.size > 100 * 1024) {
      return@withContext Pair(null, "File is too large (> 100 KB) to view inside the app.")
    }

    val inStream = channel.get(filePath)
    val outStream = ByteArrayOutputStream()
    inStream.use { input ->
      outStream.use { output ->
        input.copyTo(output)
      }
    }
    Pair(outStream.toString("UTF-8"), null)
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    Pair(null, e.toString())
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Writes content to file using SFTP.
 */
suspend fun runSftpWrite(server: VpsServer, filePath: String, content: String): String? = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 15000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    val bytes = content.toByteArray(charset("UTF-8"))
    val out = channel.put(filePath)
    out.write(bytes)
    out.flush()
    out.close()
    null
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    e.toString()
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Renames file/folder using SFTP.
 */
suspend fun runSftpRename(server: VpsServer, oldPath: String, newPath: String): String? = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    channel.rename(oldPath, newPath)
    null
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    e.toString()
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Changes file permissions using SFTP.
 */
suspend fun runSftpChmod(server: VpsServer, path: String, permissionsOctal: String): String? = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    val mode = Integer.parseInt(permissionsOctal, 8)
    channel.chmod(mode, path)
    null
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    e.toString()
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Deletes file/folder using SFTP.
 */
suspend fun runSftpDelete(server: VpsServer, path: String, isDir: Boolean): String? = withContext(Dispatchers.IO) {
  var channel: ChannelSftp? = null
  try {
    val session = SshSessionPool.getSession(server, 10000)
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(5000)

    if (isDir) {
      channel.rmdir(path)
    } else {
      channel.rm(path)
    }
    null
  } catch (e: Exception) {
    SshSessionPool.closeSession(server.id)
    e.toString()
  } finally {
    try { channel?.disconnect() } catch (e: Exception) {}
  }
}

/**
 * Parse output from SSH commands into VpsMetrics.
 */
fun parseMetrics(stdout: String, osType: String): VpsMetrics {
  var cpu = 0.0
  var ramUsed = 0.0
  var ramTotal = 0.0
  var diskUsed = 0.0
  var diskTotal = 0.0
  var uptime = "Unknown"
  var cpuTemp = 0.0
  val processes = mutableListOf<VpsProcess>()
  val netInterfaces = mutableListOf<VpsNetworkInterface>()

  try {
    if (osType == "linux") {
      val cpuSection = getSection(stdout, "===CPU===", "===RAM===")
      val idleMatch = Regex("([0-9.,]+)\\s*(?:id|idle)", RegexOption.IGNORE_CASE).find(cpuSection)
      if (idleMatch != null) {
        val idlePct = idleMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        cpu = Math.round((100.0 - idlePct) * 10.0) / 10.0
      }

      val ramSection = getSection(stdout, "===RAM===", "===DISK===")
      val memLine = ramSection.split("\n").firstOrNull { it.trim().startsWith("Mem:") }
      if (memLine != null) {
        val parts = memLine.trim().split(Regex("\\s+"))
        if (parts.size >= 3) {
          val totalMB = parts[1].toDoubleOrNull() ?: 0.0
          val usedMB = parts[2].toDoubleOrNull() ?: 0.0
          ramTotal = Math.round((totalMB / 1024.0) * 10.0) / 10.0
          ramUsed = Math.round((usedMB / 1024.0) * 10.0) / 10.0
        }
      }

      val diskSection = getSection(stdout, "===DISK===", "===UPTIME===")
      val lines = diskSection.split("\n")
      val diskLine = lines.firstOrNull { it.trim().contains(Regex("\\s+/\\s*$")) } ?: lines.getOrNull(1)
      if (diskLine != null) {
        val parts = diskLine.trim().split(Regex("\\s+"))
        if (parts.size >= 3) {
          val totalMB = parts[1].toDoubleOrNull() ?: 0.0
          val usedMB = parts[2].toDoubleOrNull() ?: 0.0
          diskTotal = Math.round((totalMB / 1024.0) * 10.0) / 10.0
          diskUsed = Math.round((usedMB / 1024.0) * 10.0) / 10.0
        }
      }

      val uptimeSection = getSection(stdout, "===UPTIME===", "===PROCESSES===")
      uptime = uptimeSection.trim().replace(Regex("^up\\s+"), "")
      if (uptime.isEmpty()) uptime = "Unknown"

      val procSection = getSection(stdout, "===PROCESSES===", "===NETPACKETS===")
      val procLines = procSection.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
      if (procLines.size > 1) {
        for (i in 1 until procLines.size) {
          val parts = procLines[i].split(Regex("\\s+"))
          if (parts.size >= 4) {
            val pid = parts[0]
            val cpuVal = parts[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            val memVal = parts[2].replace(',', '.').toDoubleOrNull() ?: 0.0
            val name = parts.drop(3).joinToString(" ")
            processes.add(VpsProcess(pid, name, cpuVal, memVal))
          }
        }
      }

      val netSection = getSection(stdout, "===NETPACKETS===", "===TEMP===")
      val netLines = netSection.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
      for (line in netLines) {
        val colonIdx = line.indexOf(':')
        if (colonIdx != -1) {
          val ifName = line.substring(0, colonIdx).trim()
          val rest = line.substring(colonIdx + 1).trim()
          val parts = rest.split(Regex("\\s+"))
          if (parts.size >= 12) {
            val rxBytes = parts[0].toLongOrNull() ?: 0L
            val rxPackets = parts[1].toLongOrNull() ?: 0L
            val rxErrors = (parts[2].toLongOrNull() ?: 0L) + (parts[3].toLongOrNull() ?: 0L)
            val txBytes = parts[8].toLongOrNull() ?: 0L
            val txPackets = parts[9].toLongOrNull() ?: 0L
            val txErrors = (parts[10].toLongOrNull() ?: 0L) + (parts[11].toLongOrNull() ?: 0L)

            netInterfaces.add(
              VpsNetworkInterface(
                name = ifName,
                rxPackets = rxPackets,
                txPackets = txPackets,
                rxBytes = rxBytes,
                txBytes = txBytes,
                errors = rxErrors + txErrors
              )
            )
          }
        }
      }

      val tempSection = getSection(stdout, "===TEMP===", "").trim()
      val rawTemp = tempSection.toDoubleOrNull() ?: 0.0
      cpuTemp = if (rawTemp > 1000) Math.round((rawTemp / 1000.0) * 10.0) / 10.0 else rawTemp
    } else {
      val cpuSection = getSection(stdout, "===CPU===", "===RAM===").trim()
      cpu = cpuSection.toDoubleOrNull()?.let { Math.round(it * 10.0) / 10.0 } ?: 0.0

      val ramSection = getSection(stdout, "===RAM===", "===DISK===").trim()
      val ramParts = ramSection.split(Regex("\\s+"))
      if (ramParts.size >= 2) {
        ramUsed = ramParts[0].toDoubleOrNull() ?: 0.0
        ramTotal = ramParts[1].toDoubleOrNull() ?: 0.0
      }

      val diskSection = getSection(stdout, "===DISK===", "===UPTIME===").trim()
      val diskParts = diskSection.split(Regex("\\s+"))
      if (diskParts.size >= 2) {
        diskUsed = diskParts[0].toDoubleOrNull() ?: 0.0
        diskTotal = diskParts[1].toDoubleOrNull() ?: 0.0
      }

      val uptimeSection = getSection(stdout, "===UPTIME===", "===PROCESSES===").trim()
      uptime = uptimeSection.ifEmpty { "Unknown" }

      val procSection = getSection(stdout, "===PROCESSES===", "===NETPACKETS===").trim()
      if (procSection.isNotEmpty()) {
        val procLines = procSection.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in procLines) {
          val parts = line.split(Regex("\\s+"))
          if (parts.size >= 4) {
            val pid = parts[0]
            val cpuVal = parts[1].toDoubleOrNull() ?: 0.0
            val memVal = parts[2].toDoubleOrNull() ?: 0.0
            val name = parts.drop(3).joinToString(" ")
            processes.add(VpsProcess(pid, name, cpuVal, memVal))
          }
        }
      }

      val netSection = getSection(stdout, "===NETPACKETS===", "===TEMP===").trim()
      if (netSection.isNotEmpty()) {
        val netLines = netSection.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in netLines) {
          val parts = line.split(Regex("\\s+"))
          if (parts.size >= 5) {
            val name = parts[0]
            val rxPackets = parts[1].toLongOrNull() ?: 0L
            val txPackets = parts[2].toLongOrNull() ?: 0L
            val rxBytes = parts[3].toLongOrNull() ?: 0L
            val txBytes = parts[4].toLongOrNull() ?: 0L
            val errors = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            netInterfaces.add(
              VpsNetworkInterface(
                name = name,
                rxPackets = rxPackets,
                txPackets = txPackets,
                rxBytes = rxBytes,
                txBytes = txBytes,
                errors = errors
              )
            )
          }
        }
      }

      val tempSection = getSection(stdout, "===TEMP===", "").trim()
      cpuTemp = tempSection.toDoubleOrNull() ?: 0.0
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }

  return VpsMetrics(cpu, ramUsed, ramTotal, diskUsed, diskTotal, uptime, processes, netInterfaces, cpuTemp)
}

private fun getSection(text: String, startHeader: String, endHeader: String): String {
  val startIndex = text.indexOf(startHeader)
  if (startIndex == -1) return ""
  val sectionStart = startIndex + startHeader.length
  if (endHeader.isEmpty()) {
    return text.substring(sectionStart)
  }
  val endIndex = text.indexOf(endHeader, sectionStart)
  if (endIndex == -1) {
    return text.substring(sectionStart)
  }
  return text.substring(sectionStart, endIndex)
}
