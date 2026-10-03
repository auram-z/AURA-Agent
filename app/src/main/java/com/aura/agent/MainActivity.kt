package com.aura.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.agent.ai.AuraViewModel
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import com.aura.agent.learning.ConfirmationGate
import com.aura.agent.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        enableEdgeToEdge()
        setContent {
            AuraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    AuraChatScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraChatScreen(viewModel: AuraViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // وقتی پیشنهاد تأیید آمد، متن ویرایش را پر کن
    LaunchedEffect(uiState.pendingConfirmation) {
        uiState.pendingConfirmation?.let { editText = it.rawContent }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0E17), Color(0xFF0F172A), Color(0xFF0A0E17))
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopAppBar(
                title = {
                    Column {
                        Text("AURA", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        Text("At your service", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A).copy(alpha = 0.9f)
                )
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    item { WelcomeMessage() }
                }

                items(uiState.messages) { message -> MessageBubble(message) }

                if (uiState.isLoading) {
                    item { TypingIndicator() }
                }
            }

            // نوار ورودی + دکمه صدا
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = {
                        if (uiState.isListening) {
                            viewModel.stopVoiceInput()
                        } else {
                            viewModel.startVoiceInput()
                        }
                    },
                    containerColor = if (uiState.isListening) Color(0xFFEF4444) else Color(0xFF334155),
                    contentColor = Color.White,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (uiState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "صدا"
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (uiState.isListening) "در حال شنیدن..." else "با AURA صحبت کنید...",
                            color = Color(0xFF64748B)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155),
                        cursorColor = Color(0xFF38BDF8),
                        focusedContainerColor = Color(0xFF1E293B),
                        unfocusedContainerColor = Color(0xFF1E293B)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !uiState.isLoading) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                                keyboardController?.hide()
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank() && !uiState.isLoading) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                            keyboardController?.hide()
                        }
                    },
                    containerColor = Color(0xFF0EA5E9),
                    contentColor = Color.White,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال")
                }
            }
        }

        // ── دیالوگ تأیید ذخیره حافظه / بازخورد ──
        uiState.pendingConfirmation?.let { pending ->
            AlertDialog(
                onDismissRequest = { viewModel.cancelConfirmation() },
                title = {
                    Text(
                        when (pending.category) {
                            ConfirmationGate.Category.MEMORY_FACT -> "ذخیره در حافظه؟"
                            ConfirmationGate.Category.FEEDBACK -> "ذخیره بازخورد؟"
                            ConfirmationGate.Category.KNOWLEDGE -> "ذخیره دانش؟"
                            else -> "ذخیره اطلاعات؟"
                        },
                        color = Color(0xFF38BDF8)
                    )
                },
                text = {
                    Column {
                        Text(pending.summary, color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("محتوا (قابل ویرایش)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFF38BDF8),
                                focusedLabelColor = Color(0xFF94A3B8),
                                unfocusedLabelColor = Color(0xFF64748B)
                            ),
                            maxLines = 5
                        )
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(
                            onClick = {
                                viewModel.resolveConfirmation(
                                    ConfirmationGate.Decision.EDIT_AND_APPROVE,
                                    editText.takeIf { it != pending.rawContent }
                                )
                            }
                        ) {
                            Text("✅ ذخیره", color = Color(0xFF22C55E))
                        }
                        TextButton(
                            onClick = {
                                viewModel.resolveConfirmation(ConfirmationGate.Decision.REJECT)
                            }
                        ) {
                            Text("❌ رد", color = Color(0xFFEF4444))
                        }
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}

@Composable
fun WelcomeMessage() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🌌", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "سلام، من AURA هستم",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF38BDF8),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "دستیار هوشمند شخصی شما\nمی‌توانید با صدا یا متن صحبت کنید.\nحافظه با تأیید شما ذخیره می‌شود.\nAt your service.",
            fontSize = 15.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Icon(Icons.Default.SmartToy, null, tint = Color(0xFF38BDF8),
                modifier = Modifier.padding(end = 8.dp, top = 4.dp).size(28.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) Color(0xFF0EA5E9) else Color(0xFF1E293B),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(message.content, color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(12.dp), lineHeight = 22.sp)
        }

        if (isUser) {
            Icon(Icons.Default.Person, null, tint = Color(0xFF94A3B8),
                modifier = Modifier.padding(start = 8.dp, top = 4.dp).size(28.dp))
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Icon(Icons.Default.SmartToy, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1E293B)) {
            Text("AURA در حال فکر کردن...", color = Color(0xFF94A3B8),
                fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
