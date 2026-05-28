package com.example.vpsmonitor.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.ssh.runSshCommand
import kotlinx.coroutines.launch

@Composable
fun SshTerminalTab(server: VpsServer) {
  var commandText by remember { mutableStateOf("") }
  val terminalLog = remember { mutableStateListOf<String>() }
  var isRunningCommand by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // Command history states
  val commandHistory = remember { mutableStateListOf<String>() }
  var historyIndex by remember { mutableStateOf(-1) }

  LaunchedEffect(server) {
    if (terminalLog.isEmpty()) {
      terminalLog.add("Welcome to VPSMonitor Remote Terminal")
      terminalLog.add("Target: ${server.username}@${server.host}:${server.port} (${server.osType})")
      terminalLog.add("Type a command below and click Send.")
      terminalLog.add("----------------------------------------")
    }
  }

  val executeCommand: (String) -> Unit = { cmd ->
    if (cmd.isNotBlank()) {
      val runCmd = cmd.trim()
      commandText = ""
      terminalLog.add("${server.username}@vps:~# $runCmd")

      // Add to history
      if (commandHistory.isEmpty() || commandHistory.last() != runCmd) {
        commandHistory.add(runCmd)
      }
      historyIndex = -1 // Reset history pointer

      scope.launch {
        isRunningCommand = true
        val result = runSshCommand(server, runCmd)
        isRunningCommand = false

        if (result.first.isNotBlank()) {
          terminalLog.add(result.first.trim())
        }
        if (result.second.isNotBlank()) {
          terminalLog.add("Error: " + result.second.trim())
        }
        if (result.first.isBlank() && result.second.isBlank()) {
          terminalLog.add("[Command returned no output]")
        }
      }
    }
  }

  val shortcuts = listOf(
    "ls -la",
    "df -h",
    "free -m",
    "top -b -n 1 | head -n 20",
    "docker ps",
    "uname -a",
    "systemctl status nginx"
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp)
  ) {
    // 1. Console display screen
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color(0xFF0B0F19), RoundedCornerShape(8.dp))
        .padding(12.dp)
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = false
      ) {
        items(terminalLog) { logLine ->
          Text(
            text = logLine,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (logLine.startsWith("${server.username}@vps:~#")) Color(0xFF38BDF8)
            else if (logLine.startsWith("Error:")) Color(0xFFF43F5E)
            else Color(0xFF10B981)
          )
        }
      }

      if (isRunningCommand) {
        CircularProgressIndicator(
          color = Color(0xFF38BDF8),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(24.dp),
          strokeWidth = 2.dp
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 2. SSH quick shortcuts bar
    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      items(shortcuts) { shortcut ->
        SuggestionChip(
          onClick = { executeCommand(shortcut) },
          label = { Text(shortcut, color = Color(0xFF38BDF8), fontSize = 11.sp) },
          colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFF1E293B))
        )
      }
    }

    // 3. Accessory keyboard row (Ctrl, Esc, Tab, Clear, Up, Down arrows)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      // Ctrl key
      Button(
        onClick = { commandText = "Ctrl+" + commandText },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 8.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(1f)
      ) {
        Text("Ctrl", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
      
      // Esc key
      Button(
        onClick = { executeCommand("exit") },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 8.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(1f)
      ) {
        Text("Esc", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }

      // Tab key
      Button(
        onClick = { commandText += "\t" },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 8.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(1f)
      ) {
        Text("Tab", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }

      // Clear key
      Button(
        onClick = { terminalLog.clear() },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 8.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(1.2f)
      ) {
        Text("Clear", color = Color(0xFFF43F5E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }

      // History UP arrow
      Button(
        onClick = {
          if (commandHistory.isNotEmpty()) {
            if (historyIndex == -1) {
              historyIndex = commandHistory.lastIndex
            } else {
              historyIndex = (historyIndex - 1).coerceAtLeast(0)
            }
            commandText = commandHistory[historyIndex]
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 6.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(0.8f)
      ) {
        Text("▲", color = Color(0xFF38BDF8), fontSize = 12.sp)
      }

      // History DOWN arrow
      Button(
        onClick = {
          if (historyIndex != -1) {
            if (historyIndex == commandHistory.lastIndex) {
              historyIndex = -1
              commandText = ""
            } else {
              historyIndex += 1
              commandText = commandHistory[historyIndex]
            }
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        contentPadding = PaddingValues(horizontal = 6.dp),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(34.dp).weight(0.8f)
      ) {
        Text("▼", color = Color(0xFF38BDF8), fontSize = 12.sp)
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    // 4. Command Input Field
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedTextField(
        value = commandText,
        onValueChange = { commandText = it },
        placeholder = { Text("Enter command...", color = Color(0xFF475569)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          color = Color.White
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { executeCommand(commandText) }),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = Color(0xFF38BDF8),
          unfocusedBorderColor = Color(0xFF475569)
        ),
        modifier = Modifier.weight(1f)
      )

      Spacer(modifier = Modifier.width(8.dp))

      IconButton(
        onClick = { executeCommand(commandText) },
        enabled = !isRunningCommand,
        modifier = Modifier
          .size(48.dp)
          .background(Color(0xFF38BDF8), RoundedCornerShape(24.dp))
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = "Run Command",
          tint = Color.Black
        )
      }
    }
  }
}
