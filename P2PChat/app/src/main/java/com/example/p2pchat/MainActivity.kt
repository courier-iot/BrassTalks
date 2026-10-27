package com.example.p2pchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val vm: ChatViewModel = viewModel()
                    val room by vm.room.collectAsStateWithLifecycle()
                    val r = room
                    if (r == null) JoinScreen(vm) else ChatScreen(vm, r)
                }
            }
        }
    }
}

@Composable
fun JoinScreen(vm: ChatViewModel) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val joining by vm.joining.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("P2P Chat", style = MaterialTheme.typography.headlineMedium)
        Text("Your ID: ${vm.myId.take(8)}…", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(code, { code = it }, label = { Text("Room code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.join(RoomInfo(code.trim(), pw, name.trim())) },
            enabled = !joining && name.isNotBlank() && code.isNotBlank() && pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (joining) "Joining…" else "Join / Create room") }
        Spacer(Modifier.height(8.dp))
        Text("Same code + password on the same WiFi = same room. First one in creates it.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel, room: RoomInfo) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Room: ${room.code}", style = MaterialTheme.typography.titleMedium)
                Text(if (peers == 0) "Searching for others…" else "$peers connected", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.leave() }) { Text("Leave") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m ->
                val mine = m.senderId == vm.myId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            if (!mine) Text(m.senderName, style = MaterialTheme.typography.labelSmall)
                            Text(m.text)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
        }
    }
}
