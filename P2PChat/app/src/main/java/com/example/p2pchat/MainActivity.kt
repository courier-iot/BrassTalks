package com.example.p2pchat

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            SteampunkTheme {
                Box(Modifier.fillMaxSize().background(Steam.Backdrop)) {
                    Surface(
                        color = Color.Transparent,
                        contentColor = Steam.Parchment,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                    ) {
                        val vm: ChatViewModel = viewModel()
                        val room by vm.room.collectAsStateWithLifecycle()
                        val r = room
                        if (r == null) HomeScreen(vm) else ChatScreen(vm, r)
                    }
                }
            }
        }
    }
}

// ---------- Shared pieces ----------

@Composable
fun BrassButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Steam.Brass, contentColor = Steam.Leather,
            disabledContainerColor = Steam.CopperDark, disabledContentColor = Steam.ParchmentDim
        )
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick, modifier = modifier, shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Steam.Brass, contentColor = Steam.Leather)
        ) { Text(label, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(
            onClick = onClick, modifier = modifier, shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, Steam.Copper),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Steam.Copper)
        ) { Text(label) }
    }
}

@Composable
fun InfoCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(6.dp), color = Steam.Wood,
        border = BorderStroke(1.dp, Steam.CopperDark), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Steam.Brass)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
        }
    }
}

// ---------- Home: Create vs Join ----------

@Composable
fun HomeScreen(vm: ChatViewModel) {
    var creating by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf(RoomCodes.generate()) }
    var createPw by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var joinPw by remember { mutableStateOf("") }
    val joining by vm.joining.collectAsStateWithLifecycle()
    val fieldColors = steamFieldColors()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Gear(72.dp, Steam.Brass, spin = true)
        Spacer(Modifier.height(8.dp))
        Text("BRASSTALKS", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = Steam.Brass)
        Text("Serial no. ${vm.myId.take(8).uppercase()}",
            style = MaterialTheme.typography.labelMedium, color = Steam.Copper)
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(name, { name = it }, label = { Text("Your alias") }, singleLine = true,
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Create Room", creating, Modifier.weight(1f)) { creating = true }
            ModeButton("Join Room", !creating, Modifier.weight(1f)) { creating = false }
        }
        Spacer(Modifier.height(12.dp))

        if (creating) {
            InfoCard(
                "Start a new room",
                "Pick a passphrase, then share the room code and passphrase with your friends. " +
                    "Nobody is in charge: the room keeps running as long as anyone is in it."
            )
            Spacer(Modifier.height(12.dp))
            Text("Your room code", style = MaterialTheme.typography.labelMedium, color = Steam.ParchmentDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Steam.Leather, shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Steam.Brass)
                ) {
                    Text(newCode, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = Steam.Brass)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { newCode = RoomCodes.generate() }) { Text("New code", color = Steam.Copper) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(createPw, { createPw = it }, label = { Text("Choose a passphrase (6+ characters)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BrassButton(
                if (joining) "Stoking the boilers…" else "Build the Room",
                enabled = !joining && name.isNotBlank() && createPw.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { vm.join(RoomInfo(newCode, createPw, name.trim(), created = true)) }
        } else {
            InfoCard(
                "Join an existing room",
                "Enter the room code and passphrase from whoever built it. " +
                    "You both need to be on the same WiFi."
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(joinCode, { joinCode = it }, label = { Text("Room code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(joinPw, { joinPw = it }, label = { Text("Passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BrassButton(
                if (joining) "Stoking the boilers…" else "Board the Room",
                enabled = !joining && name.isNotBlank() && joinCode.isNotBlank() && joinPw.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { vm.join(RoomInfo(joinCode.trim().uppercase(), joinPw, name.trim(), created = false)) }
        }
    }
}

// ---------- Chat ----------

@Composable
fun ChatScreen(vm: ChatViewModel, room: RoomInfo) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var searchedTooLong by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fieldColors = steamFieldColors()
    val ctx = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(peers) {
        if (peers > 0) searchedTooLong = false else { delay(20_000); searchedTooLong = true }
    }

    fun invite() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Join my BrassTalks room!\nRoom code: ${room.code}\nPassphrase: ${room.password}\n(Be on the same WiFi.)")
        }
        ctx.startActivity(Intent.createChooser(send, "Invite to room"))
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Steam.Wood,
            titleContentColor = Steam.Brass,
            textContentColor = Steam.Parchment,
            title = { Text("Disembark?") },
            text = { Text("You'll leave room ${room.code}. To come back you'll need the code and passphrase.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave() }) { Text("Leave", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay", color = Steam.Brass) } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Steam.Wood).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Gear(32.dp, Steam.Brass, spin = peers > 0)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(room.code, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Steam.Brass, fontFamily = FontFamily.Monospace)
                Text(if (peers == 0) "No one else aboard" else "$peers aboard",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
            }
            TextButton(onClick = { invite() }) { Text("Invite", color = Steam.Brass) }
            TextButton(onClick = { confirmLeave = true }) { Text("Leave", color = Steam.Copper) }
        }
        HorizontalDivider(color = Steam.Brass, thickness = 2.dp)

        // Status banner while nobody else is connected
        if (peers == 0) {
            val (text, color) = when {
                room.created && !searchedTooLong ->
                    "Room built! Tap Invite to share the code and passphrase, then wait for others to board." to Steam.Verdigris
                !room.created && !searchedTooLong ->
                    "Searching the WiFi for this room…" to Steam.Verdigris
                else ->
                    "Nobody else found. Check that everyone is on the same WiFi and the code and passphrase match exactly." to Steam.Rust
            }
            Surface(color = Steam.Wood, border = BorderStroke(1.dp, color), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                val mine = m.senderId == vm.myId
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (mine) Steam.CopperDark else Steam.WoodLight,
                        contentColor = Steam.Parchment,
                        border = BorderStroke(1.dp, if (mine) Steam.Copper else Steam.Verdigris),
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            if (!mine) Text(m.senderName, style = MaterialTheme.typography.labelSmall,
                                color = Steam.Verdigris, fontWeight = FontWeight.Bold)
                            Text(m.text)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Steam.Copper)
        Row(
            Modifier.fillMaxWidth().background(Steam.Wood).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                input, { input = it }, placeholder = { Text("Dispatch a message") },
                colors = fieldColors, modifier = Modifier.weight(1f), maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            BrassButton("Transmit", enabled = input.isNotBlank()) { vm.send(input); input = "" }
        }
    }
}
