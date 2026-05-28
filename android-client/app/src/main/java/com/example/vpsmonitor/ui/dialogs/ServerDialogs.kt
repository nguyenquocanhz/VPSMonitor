package com.example.vpsmonitor.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.ssh.runSshTestConnection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
  prefillHost: String,
  onDismiss: () -> Unit,
  onServerAdded: (VpsServer) -> Unit
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

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
      shape = RoundedCornerShape(12.dp)
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text("Add New VPS", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))

        OutlinedTextField(
          value = name, onValueChange = { name = it },
          label = { Text("Display Name", color = Color(0xFF94A3B8)) },
          singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
        )

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
          OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host", color = Color(0xFF94A3B8)) },
            singleLine = true, modifier = Modifier.weight(0.7f).padding(end = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
          OutlinedTextField(
            value = port, onValueChange = { port = it },
            label = { Text("Port", color = Color(0xFF94A3B8)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.3f),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        }

        OutlinedTextField(
          value = username, onValueChange = { username = it },
          label = { Text("Username", color = Color(0xFF94A3B8)) },
          singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
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
            value = password, onValueChange = { password = it },
            label = { Text("Password", color = Color(0xFF94A3B8)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        } else {
          OutlinedTextField(
            value = privateKey, onValueChange = { privateKey = it },
            label = { Text("Private Key (PEM)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(bottom = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )

          OutlinedTextField(
            value = passphrase, onValueChange = { passphrase = it },
            label = { Text("Passphrase (Tùy chọn)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("Để trống nếu không có") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
            .padding(4.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(if (osType == "linux") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "linux" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Linux", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(if (osType == "windows") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "windows" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Windows", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
          Text(err, color = Color(0xFFEF4444), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss, enabled = !isTesting) {
            Text("Cancel", color = Color(0xFF94A3B8))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (name.isBlank() || host.isBlank()) {
                testError = "Missing details"
                return@Button
              }
              if (authType == "password" && password.isBlank()) {
                testError = "Password required"
                return@Button
              }
              if (authType == "key" && privateKey.isBlank()) {
                testError = "Private key required"
                return@Button
              }
              val parsedPort = port.toIntOrNull() ?: 22
              val srv = VpsServer(
                name = name.trim(), host = host.trim(), port = parsedPort,
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
          ) {
            if (isTesting) {
              CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Text("Add")
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerDialog(
  server: VpsServer,
  onDismiss: () -> Unit,
  onServerUpdated: (VpsServer) -> Unit
) {
  var name by remember { mutableStateOf(server.name) }
  var host by remember { mutableStateOf(server.host) }
  var port by remember { mutableStateOf(server.port.toString()) }
  var username by remember { mutableStateOf(server.username) }
  var password by remember { mutableStateOf(server.password) }
  var authType by remember { mutableStateOf(server.authType) }
  var privateKey by remember { mutableStateOf(server.privateKey) }
  var passphrase by remember { mutableStateOf(server.passphrase) }
  var osType by remember { mutableStateOf(server.osType) }
  var strictHostKeyChecking by remember { mutableStateOf(server.strictHostKeyChecking) }

  var isTesting by remember { mutableStateOf(false) }
  var testError by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
      shape = RoundedCornerShape(12.dp)
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text("Edit VPS Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))

        OutlinedTextField(
          value = name, onValueChange = { name = it },
          label = { Text("Display Name", color = Color(0xFF94A3B8)) },
          singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
        )

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
          OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host", color = Color(0xFF94A3B8)) },
            singleLine = true, modifier = Modifier.weight(0.7f).padding(end = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
          OutlinedTextField(
            value = port, onValueChange = { port = it },
            label = { Text("Port", color = Color(0xFF94A3B8)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.3f),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        }

        OutlinedTextField(
          value = username, onValueChange = { username = it },
          label = { Text("Username", color = Color(0xFF94A3B8)) },
          singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
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
            value = password, onValueChange = { password = it },
            label = { Text("Password", color = Color(0xFF94A3B8)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        } else {
          OutlinedTextField(
            value = privateKey, onValueChange = { privateKey = it },
            label = { Text("Private Key (PEM)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(bottom = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )

          OutlinedTextField(
            value = passphrase, onValueChange = { passphrase = it },
            label = { Text("Passphrase (Tùy chọn)", color = Color(0xFF94A3B8)) },
            placeholder = { Text("Để trống nếu không có") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF38BDF8), unfocusedBorderColor = Color(0xFF475569))
          )
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
            .padding(4.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(if (osType == "linux") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "linux" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Linux", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(if (osType == "windows") Color(0xFF0EA5E9) else Color.Transparent)
              .clickable { osType = "windows" }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Windows", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
          Text(err, color = Color(0xFFEF4444), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss, enabled = !isTesting) {
            Text("Cancel", color = Color(0xFF94A3B8))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (name.isBlank() || host.isBlank()) {
                testError = "Missing details"
                return@Button
              }
              if (authType == "password" && password.isBlank()) {
                testError = "Password required"
                return@Button
              }
              if (authType == "key" && privateKey.isBlank()) {
                testError = "Private key required"
                return@Button
              }
              val parsedPort = port.toIntOrNull() ?: 22
              val updated = VpsServer(
                id = server.id, name = name.trim(), host = host.trim(), port = parsedPort,
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
                val err = runSshTestConnection(updated)
                isTesting = false
                if (err == null) {
                  onServerUpdated(updated)
                } else {
                  testError = err
                }
              }
            },
            enabled = !isTesting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
          ) {
            if (isTesting) {
              CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Text("Save")
            }
          }
        }
      }
    }
  }
}
