package com.example.vpsmonitor.ui.tabs

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vpsmonitor.data.SftpFileItem
import com.example.vpsmonitor.data.VpsServer
import com.example.vpsmonitor.network.formatSize
import com.example.vpsmonitor.ssh.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpExplorerTab(server: VpsServer) {
  val context = LocalContext.current
  var currentDir by remember { mutableStateOf(".") }
  var fileItems by remember { mutableStateOf<List<SftpFileItem>?>(null) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf<String?>(null) }
  var reloadTrigger by remember { mutableStateOf(0) }

  var activeFileOptions by remember { mutableStateOf<SftpFileItem?>(null) }

  var viewerFileContent by remember { mutableStateOf<String?>(null) }
  var viewerFileName by remember { mutableStateOf<String?>(null) }
  var viewerFilePath by remember { mutableStateOf<String?>(null) }
  var isReadingFile by remember { mutableStateOf(false) }
  var isSavingFile by remember { mutableStateOf(false) }

  var renameItemDialog by remember { mutableStateOf<SftpFileItem?>(null) }
  var isRenamingItem by remember { mutableStateOf(false) }

  var chmodItemDialog by remember { mutableStateOf<SftpFileItem?>(null) }
  var isChmodingItem by remember { mutableStateOf(false) }

  var deleteItemConfirm by remember { mutableStateOf<SftpFileItem?>(null) }
  var isDeleting by remember { mutableStateOf(false) }

  val scope = rememberCoroutineScope()

  LaunchedEffect(server, currentDir, reloadTrigger) {
    isLoading = true
    errorMsg = null
    val result = runSftpList(server, currentDir)
    isLoading = false
    if (result.first == null) {
      errorMsg = result.second ?: "Failed to connect to SFTP."
    } else {
      fileItems = result.first
      currentDir = result.second ?: currentDir
    }
  }

  Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
    // 1. Breadcrumbs Path Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
        .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = {
          val path = currentDir
          if (path != "/" && path.isNotEmpty()) {
            val idx = path.lastIndexOf('/')
            currentDir = if (idx <= 0) "/" else path.substring(0, idx)
          }
        },
        enabled = currentDir != "/"
      ) {
        Icon(
          imageVector = Icons.Default.ArrowBack, 
          contentDescription = "Go Up", 
          tint = if (currentDir != "/") Color(0xFF38BDF8) else Color(0xFF475569)
        )
      }

      Text(
        text = currentDir,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
      )

      IconButton(onClick = { reloadTrigger++ }, enabled = !isLoading) {
        Icon(Icons.Default.Refresh, contentDescription = "Reload List", tint = Color(0xFF38BDF8))
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // 2. Loading or File List View
    if (isLoading) {
      Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color(0xFF38BDF8))
      }
    } else if (errorMsg != null) {
      Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
        Text("SFTP Error: $errorMsg", color = Color(0xFFF43F5E), textAlign = TextAlign.Center)
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
        fileItems?.let { itemsList ->
          if (itemsList.isEmpty()) {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Directory is empty", color = Color(0xFF94A3B8))
              }
            }
          } else {
            items(itemsList) { file ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
                  .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                  .clickable {
                    if (file.isDir) {
                      currentDir = file.path
                    } else {
                      activeFileOptions = file
                    }
                  }
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = if (file.isDir) "📁" else "📄",
                  fontSize = 22.sp,
                  modifier = Modifier.padding(end = 12.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = file.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = if (file.isDir) "Folder • ${file.permissions}" else "${formatSize(file.size)} • ${file.permissions}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                  )
                }

                IconButton(
                  onClick = { activeFileOptions = file },
                  modifier = Modifier.size(36.dp)
                ) {
                  Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color(0xFF38BDF8))
                }
              }
            }
          }
        }
      }
    }
  }

  // Reading/Saving Dialog
  if (isReadingFile || isSavingFile) {
    Dialog(
      onDismissRequest = {},
      properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
      Box(
        modifier = Modifier.size(90.dp).background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(32.dp))
          Spacer(modifier = Modifier.height(8.dp))
          Text(text = if (isSavingFile) "Saving..." else "Loading...", fontSize = 10.sp, color = Color.White)
        }
      }
    }
  }

  // 3. File Options Dialog
  activeFileOptions?.let { file ->
    Dialog(
      onDismissRequest = { activeFileOptions = null }
    ) {
      Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
      ) {
        Column(
          modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
          Text(
            text = file.name,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp)
          )
          Text(
            text = if (file.isDir) "Folder • ${file.permissions}" else "${formatSize(file.size)} • ${file.permissions}",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp)
          )

          HorizontalDivider(color = Color(0xFF334155), thickness = 1.dp)

          if (!file.isDir) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  activeFileOptions = null
                  scope.launch {
                    isReadingFile = true
                    val res = runSftpRead(server, file.path)
                    isReadingFile = false
                    if (res.first != null) {
                      viewerFileName = file.name
                      viewerFilePath = file.path
                      viewerFileContent = res.first
                    } else {
                      Toast.makeText(context, res.second ?: "Failed to read file", Toast.LENGTH_LONG).show()
                    }
                  }
                }
                .padding(vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.Edit, contentDescription = "Edit file", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(12.dp))
              Text("Chỉnh sửa nội dung tệp tin", color = Color.White, fontSize = 14.sp)
            }
            HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                activeFileOptions = null
                renameItemDialog = file
              }
              .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Info, contentDescription = "Rename", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Đổi tên tệp / thư mục", color = Color.White, fontSize = 14.sp)
          }

          HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                activeFileOptions = null
                chmodItemDialog = file
              }
              .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Lock, contentDescription = "Chmod", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Thay đổi quyền truy cập (Chmod)", color = Color.White, fontSize = 14.sp)
          }

          HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                activeFileOptions = null
                deleteItemConfirm = file
              }
              .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF43F5E), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Xóa vĩnh viễn", color = Color(0xFFF43F5E), fontSize = 14.sp)
          }
        }
      }
    }
  }

  // 4. VS Code Text Editor Dialog View
  viewerFileContent?.let { content ->
    val density = androidx.compose.ui.platform.LocalDensity.current
    var savedContent by remember(content) { mutableStateOf(content) }
    var editableText by remember(content) { mutableStateOf(TextFieldValue(content)) }
    
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }
    
    fun updateText(newValue: TextFieldValue, isUserEdit: Boolean = true) {
      if (isUserEdit) {
        if (undoStack.isEmpty() || undoStack.last().text != editableText.text) {
          undoStack.add(editableText)
          if (undoStack.size > 50) {
            undoStack.removeAt(0)
          }
        }
        redoStack.clear()
      }
      editableText = newValue
    }
    
    fun performUndo() {
      if (undoStack.isNotEmpty()) {
        val prev = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(editableText)
        updateText(prev, isUserEdit = false)
      }
    }
    
    fun performRedo() {
      if (redoStack.isNotEmpty()) {
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(editableText)
        updateText(next, isUserEdit = false)
      }
    }
    
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchMatches = remember { mutableStateListOf<Int>() }
    var currentMatchIndex by remember { mutableStateOf(-1) }
    
    LaunchedEffect(searchQuery, editableText.text) {
      searchMatches.clear()
      if (searchQuery.isNotEmpty()) {
        var idx = editableText.text.indexOf(searchQuery, ignoreCase = true)
        while (idx != -1) {
          searchMatches.add(idx)
          idx = editableText.text.indexOf(searchQuery, idx + searchQuery.length, ignoreCase = true)
        }
      }
      if (searchMatches.isEmpty()) {
        currentMatchIndex = -1
      } else {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatches.size) {
          currentMatchIndex = 0
        }
      }
    }
    
    fun navigateMatch(forward: Boolean) {
      if (searchMatches.isEmpty()) return
      if (forward) {
        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
      } else {
        currentMatchIndex = (currentMatchIndex - 1 + searchMatches.size) % searchMatches.size
      }
      val matchPos = searchMatches[currentMatchIndex]
      editableText = editableText.copy(
        selection = TextRange(matchPos, matchPos + searchQuery.length)
      )
    }
    
    var showUnsavedChangesConfirmDialog by remember { mutableStateOf(false) }
    val isDirty = editableText.text != savedContent
    
    fun closeEditor() {
      viewerFileContent = null
      viewerFileName = null
      viewerFilePath = null
    }
    
    if (showUnsavedChangesConfirmDialog) {
      AlertDialog(
        onDismissRequest = { showUnsavedChangesConfirmDialog = false },
        title = { Text("Lưu thay đổi?", color = Color.White) },
        text = { Text("Tệp này có các thay đổi chưa lưu. Bạn có muốn lưu trước khi đóng không?", color = Color.White) },
        confirmButton = {
          TextButton(
            onClick = {
              showUnsavedChangesConfirmDialog = false
              val path = viewerFilePath ?: return@TextButton
              scope.launch {
                isSavingFile = true
                val err = runSftpWrite(server, path, editableText.text)
                isSavingFile = false
                if (err == null) {
                  Toast.makeText(context, "Lưu file thành công!", Toast.LENGTH_SHORT).show()
                  reloadTrigger++
                  closeEditor()
                } else {
                  Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                }
              }
            }
          ) {
            Text("Lưu & Đóng", color = Color(0xFF10B981))
          }
        },
        dismissButton = {
          Row {
            TextButton(
              onClick = {
                showUnsavedChangesConfirmDialog = false
                closeEditor()
              }
            ) {
              Text("Không lưu", color = Color(0xFFF43F5E))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
              onClick = {
                showUnsavedChangesConfirmDialog = false
              }
            ) {
              Text("Hủy", color = Color.LightGray)
            }
          }
        },
        containerColor = Color(0xFF252526)
      )
    }
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    
    val cursorPosition = editableText.selection.start
    val textBeforeCursor = editableText.text.take(cursorPosition)
    val currentLineNumber = textBeforeCursor.count { it == '\n' } + 1
    val currentColumnNumber = cursorPosition - textBeforeCursor.lastIndexOf('\n')
    
    val linesCount = editableText.text.split('\n').size.coerceAtLeast(1)
    
    val fileExtension = viewerFileName?.substringAfterLast('.', "")?.uppercase(Locale.ROOT) ?: "TXT"
    val fileIcon = when (viewerFileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
      "kt", "java" -> "☕"
      "js", "ts" -> "🟨"
      "json" -> "{}"
      "sh", "bash" -> "🐚"
      "py" -> "🐍"
      "html", "htm" -> "🌐"
      "css" -> "🎨"
      "md" -> "📝"
      else -> "📄"
    }
    
    Dialog(
      onDismissRequest = {
        if (isDirty) {
          showUnsavedChangesConfirmDialog = true
        } else {
          closeEditor()
        }
      },
      properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF1E1E1E))
          .safeDrawingPadding()
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          // 1. VS Code style Title Bar
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF252526))
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = server.name,
                fontSize = 10.sp,
                color = Color(0xFF858585),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = viewerFilePath ?: "",
                fontSize = 11.sp,
                color = Color(0xFFCCCCCC),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace
              )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
              // Undo
              IconButton(
                onClick = { performUndo() },
                enabled = undoStack.isNotEmpty(),
                modifier = Modifier.size(32.dp)
              ) {
                Text(
                  text = "↩",
                  fontSize = 16.sp,
                  color = if (undoStack.isNotEmpty()) Color.White else Color(0xFF555555)
                )
              }
              
              // Redo
              IconButton(
                onClick = { performRedo() },
                enabled = redoStack.isNotEmpty(),
                modifier = Modifier.size(32.dp)
              ) {
                Text(
                  text = "↪",
                  fontSize = 16.sp,
                  color = if (redoStack.isNotEmpty()) Color.White else Color(0xFF555555)
                )
              }
              
              // Search Toggle
              IconButton(
                onClick = { showSearch = !showSearch },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Search,
                  contentDescription = "Search",
                  tint = if (showSearch) Color(0xFF007ACC) else Color.White,
                  modifier = Modifier.size(18.dp)
                )
              }
              
              Spacer(modifier = Modifier.width(6.dp))
              
              // Save Button
              Button(
                onClick = {
                  val path = viewerFilePath ?: return@Button
                  scope.launch {
                    isSavingFile = true
                    val err = runSftpWrite(server, path, editableText.text)
                    isSavingFile = false
                    if (err == null) {
                      Toast.makeText(context, "Lưu file thành công!", Toast.LENGTH_SHORT).show()
                      savedContent = editableText.text
                      reloadTrigger++
                    } else {
                      Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                    }
                  }
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = if (isDirty) Color(0xFF0E639C) else Color(0xFF333333)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
              ) {
                Text("Save", fontSize = 11.sp, color = Color.White)
              }
              
              Spacer(modifier = Modifier.width(6.dp))
              
              // Close Editor
              IconButton(
                onClick = {
                  if (isDirty) {
                    showUnsavedChangesConfirmDialog = true
                  } else {
                    closeEditor()
                  }
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close",
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          }
          
          // 2. Search Panel Overlay
          if (showSearch) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Tìm kiếm...", fontSize = 12.sp, color = Color.Gray) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                modifier = Modifier
                  .weight(1f)
                  .height(38.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = Color.White,
                  unfocusedTextColor = Color.White,
                  focusedContainerColor = Color(0xFF1E1E1E),
                  unfocusedContainerColor = Color(0xFF1E1E1E),
                  focusedBorderColor = Color(0xFF007ACC),
                  unfocusedBorderColor = Color(0xFF454545)
                )
              )
              
              Spacer(modifier = Modifier.width(8.dp))
              
              if (searchQuery.isNotEmpty()) {
                val matchCount = searchMatches.size
                val currentText = if (matchCount > 0) "${currentMatchIndex + 1}/$matchCount" else "0/0"
                Text(
                  text = currentText,
                  color = Color.LightGray,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 4.dp)
                )
              }
              
              Spacer(modifier = Modifier.width(4.dp))
              
              IconButton(
                onClick = { navigateMatch(forward = false) },
                enabled = searchMatches.isNotEmpty(),
                modifier = Modifier.size(32.dp)
              ) {
                Text(
                  text = "▲",
                  fontSize = 12.sp,
                  color = if (searchMatches.isNotEmpty()) Color.White else Color.Gray
                )
              }
              
              IconButton(
                onClick = { navigateMatch(forward = true) },
                enabled = searchMatches.isNotEmpty(),
                modifier = Modifier.size(32.dp)
              ) {
                Text(
                  text = "▼",
                  fontSize = 12.sp,
                  color = if (searchMatches.isNotEmpty()) Color.White else Color.Gray
                )
              }
              
              IconButton(
                onClick = {
                  showSearch = false
                  searchQuery = ""
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close Search",
                  tint = Color.LightGray,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
          
          // 3. Tab header
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF252526))
          ) {
            Row(
              modifier = Modifier
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 14.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = "$fileIcon ", fontSize = 12.sp)
              Text(
                text = viewerFileName ?: "untitled",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
              )
              if (isDirty) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "●",
                  color = Color(0xFFE2B93D),
                  fontSize = 10.sp
                )
              }
            }
            Box(
              modifier = Modifier
                .weight(1f)
                .align(Alignment.Bottom)
                .height(1.dp)
                .background(Color(0xFF2D2D2D))
            )
          }
          
          // 4. Editor viewport with gutter line numbers
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
          ) {
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            val lineHeightPx = with(density) { 18.sp.toPx() }
            
            LaunchedEffect(currentLineNumber) {
              val lineOffset = (currentLineNumber - 1) * lineHeightPx
              val currentScroll = verticalScrollState.value
              val bottomThreshold = currentScroll + viewportHeightPx - lineHeightPx * 2.5f
              val topThreshold = currentScroll + lineHeightPx
              if (lineOffset > bottomThreshold) {
                verticalScrollState.animateScrollTo((lineOffset - viewportHeightPx + lineHeightPx * 3.5f).toInt().coerceAtLeast(0))
              } else if (lineOffset < topThreshold) {
                verticalScrollState.animateScrollTo((lineOffset - lineHeightPx * 2f).toInt().coerceAtLeast(0))
              }
            }
            
            Row(modifier = Modifier.fillMaxSize()) {
              // Gutter Line Numbers
              Column(
                modifier = Modifier
                  .fillMaxHeight()
                  .background(Color(0xFF1E1E1E))
                  .verticalScroll(verticalScrollState)
                  .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.End
              ) {
                for (i in 1..linesCount) {
                  Text(
                    text = i.toString(),
                    color = if (i == currentLineNumber) Color(0xFFC6C6C6) else Color(0xFF858585),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp)
                  )
                }
              }
              
              Box(
                modifier = Modifier
                  .fillMaxHeight()
                  .width(1.dp)
                  .background(Color(0xFF2D2D2D))
              )
              
              // Text Area
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(Color(0xFF1E1E1E))
                  .verticalScroll(verticalScrollState)
                  .horizontalScroll(horizontalScrollState)
                  .padding(vertical = 8.dp, horizontal = 8.dp)
              ) {
                BasicTextField(
                  value = editableText,
                  onValueChange = { newValue ->
                    val isTextChange = newValue.text != editableText.text
                    updateText(newValue, isUserEdit = isTextChange)
                  },
                  textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFD4D4D4)
                  ),
                  cursorBrush = SolidColor(Color(0xFF007ACC)),
                  modifier = Modifier
                    .fillMaxSize()
                    .width(IntrinsicSize.Max)
                )
              }
            }
          }
          
          // 5. VS Code Status Bar
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF007ACC))
              .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "⚡ SFTP CONNECTION",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
              )
            }
            
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "Spaces: 4",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
              Text(
                text = "UTF-8",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
              Text(
                text = fileExtension,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
              Text(
                text = "Ln $currentLineNumber, Col $currentColumnNumber",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }
      }
    }
  }

  // Rename Dialog
  renameItemDialog?.let { fileToRename ->
    var newName by remember { mutableStateOf(fileToRename.name) }
    AlertDialog(
      onDismissRequest = { renameItemDialog = null },
      title = { Text("Đổi tên tệp / thư mục") },
      text = {
        Column {
          Text("Nhập tên mới cho '${fileToRename.name}':", fontSize = 13.sp, color = Color.White)
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
          )
        }
      },
      confirmButton = {
        TextButton(
          enabled = !isRenamingItem && newName.isNotBlank() && newName != fileToRename.name,
          onClick = {
            scope.launch {
              isRenamingItem = true
              val oldPath = fileToRename.path
              val newPath = fileToRename.path.substringBeforeLast('/') + "/" + newName.trim()
              val err = runSftpRename(server, oldPath, newPath)
              isRenamingItem = false
              if (err == null) {
                reloadTrigger++
                Toast.makeText(context, "Đã đổi tên thành công", Toast.LENGTH_SHORT).show()
                renameItemDialog = null
              } else {
                Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
              }
            }
          }
        ) {
          if (isRenamingItem) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Text("Đổi tên")
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { renameItemDialog = null }, enabled = !isRenamingItem) {
          Text("Hủy")
        }
      }
    )
  }

  // Chmod Dialog
  chmodItemDialog?.let { fileToChmod ->
    var octalString by remember { mutableStateOf(if (fileToChmod.isDir) "755" else "644") }
    AlertDialog(
      onDismissRequest = { chmodItemDialog = null },
      title = { Text("Thay đổi quyền truy cập (Chmod)") },
      text = {
        Column {
          Text("Nhập mã quyền bát phân (octal) cho '${fileToChmod.name}':", fontSize = 13.sp, color = Color.White)
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = octalString,
            onValueChange = { octalString = it.take(4) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text("Phổ biến: 755 (Đọc/Ghi/Chạy cho thư mục), 644 (Đọc/Ghi cho tệp tin)", fontSize = 11.sp, color = Color(0xFF94A3B8))
        }
      },
      confirmButton = {
        val isValid = octalString.length in 3..4 && octalString.all { it in '0'..'7' }
        TextButton(
          enabled = !isChmodingItem && isValid,
          onClick = {
            scope.launch {
              isChmodingItem = true
              val err = runSftpChmod(server, fileToChmod.path, octalString)
              isChmodingItem = false
              if (err == null) {
                reloadTrigger++
                Toast.makeText(context, "Thay đổi quyền truy cập thành công", Toast.LENGTH_SHORT).show()
                chmodItemDialog = null
              } else {
                Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
              }
            }
          }
        ) {
          if (isChmodingItem) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Text("Chấp nhận")
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { chmodItemDialog = null }, enabled = !isChmodingItem) {
          Text("Hủy")
        }
      }
    )
  }

  // Delete Confirm Dialog
  deleteItemConfirm?.let { fileToDelete ->
    AlertDialog(
      onDismissRequest = { deleteItemConfirm = null },
      title = { Text("Delete file/folder?") },
      text = { Text("Are you sure you want to permanently delete '${fileToDelete.name}' from the VPS server?") },
      confirmButton = {
        TextButton(
          enabled = !isDeleting,
          onClick = {
            scope.launch {
              isDeleting = true
              val err = runSftpDelete(server, fileToDelete.path, fileToDelete.isDir)
              isDeleting = false
              if (err == null) {
                reloadTrigger++
                Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
              } else {
                Toast.makeText(context, "Failed: $err", Toast.LENGTH_LONG).show()
              }
              deleteItemConfirm = null
            }
          }
        ) {
          if (isDeleting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Text("Delete", color = Color(0xFFF43F5E))
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteItemConfirm = null }, enabled = !isDeleting) {
          Text("Cancel")
        }
      }
    )
  }
}
