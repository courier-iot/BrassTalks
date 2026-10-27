package com.example.p2pchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.text.font.FontStyle
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
        val vm = (application as BrassApp).chatVm()   // one session for the whole app, kept alive by the service
        setContent {
            SteampunkTheme {
                Box(Modifier.fillMaxSize().background(Steam.Backdrop)) {
                    Surface(
                        color = Color.Transparent,
                        contentColor = Steam.Parchment,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                    ) {
                        val room by vm.room.collectAsStateWithLifecycle()
                        val notice by vm.notice.collectAsStateWithLifecycle()
                        val r = room
                        if (r == null) HomeScreen(vm) else ChatScreen(vm, r)

                        val n = notice
                        if (n != null) {
                            AlertDialog(
                                onDismissRequest = { vm.clearNotice() },
                                containerColor = Steam.Wood, titleContentColor = Steam.Brass,
                                textContentColor = Steam.Parchment,
                                title = { Text("Heads up") },
                                text = { Text(n) },
                                confirmButton = { TextButton(onClick = { vm.clearNotice() }) { Text("OK", color = Steam.Brass) } }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
        Notifier.clearMessages(this)
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
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

@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Steam.Brass, checkedThumbColor = Steam.Leather,
                uncheckedTrackColor = Steam.WoodLight, uncheckedThumbColor = Steam.ParchmentDim,
                uncheckedBorderColor = Steam.Copper
            )
        )
    }
}

@Composable
fun SettingsDialog(s: AppSettings, onChange: (AppSettings) -> Unit, onErase: () -> Unit, onClose: () -> Unit) {
    var confirmErase by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow("Save chat history", "Stored encrypted on this phone only.", s.keepHistory) {
                    onChange(s.copy(keepHistory = it))
                }
                if (s.keepHistory) {
                    Text("Delete old messages after", style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Never", 7 to "7d", 30 to "30d", 90 to "90d").forEach { (days, label) ->
                            FilterChip(
                                selected = s.retentionDays == days,
                                onClick = { onChange(s.copy(retentionDays = days)) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Steam.CopperDark,
                                    selectedLabelColor = Steam.Parchment,
                                    labelColor = Steam.ParchmentDim
                                )
                            )
                        }
                    }
                }
                SettingRow("Keep received photos", "Takes effect once photo sharing is added.", s.keepImages) {
                    onChange(s.copy(keepImages = it))
                }
                SettingRow("Keep received videos", "Takes effect once video sharing is added.", s.keepVideos) {
                    onChange(s.copy(keepVideos = it))
                }
                SettingRow(
                    "Stay connected in background",
                    "Keeps the room alive with the screen off. Shows a notification and uses more battery.",
                    s.keepAlive
                ) { onChange(s.copy(keepAlive = it)) }
                if (s.keepAlive) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Steam.Copper),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Steam.Copper),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Battery settings: set BrassTalks to Unrestricted") }
                }
                Text("Voice memos are never saved. They disappear when you leave a room.",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                OutlinedButton(
                    onClick = { confirmErase = true }, shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Steam.Rust),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Steam.Rust),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Erase all saved history") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = Steam.Brass) } }
    )
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Erase everything?") },
            text = { Text("All saved chat history on this phone will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmErase = false; onErase() }) { Text("Erase", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel", color = Steam.Brass) } }
        )
    }
}

@Composable
fun SettingsHost(vm: ChatViewModel, onClose: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsDialog(s, { vm.updateSettings(it) }, { vm.eraseAllHistory() }, onClose)
}

fun buildMembers(
    myId: String, myName: String, known: Map<String, String>, connected: Set<String>, hostId: String?
): List<Member> {
    val list = mutableListOf(Member(myId, myName, hostId == myId, true))
    connected.forEach { id -> list.add(Member(id, known[id] ?: "Unknown (${id.take(6)})", hostId == id, false)) }
    return list.sortedWith(
        compareByDescending<Member> { it.isHost }.thenByDescending { it.isMe }.thenBy { it.name.lowercase() }
    )
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
    var showSettings by remember { mutableStateOf(false) }
    val joining by vm.joining.collectAsStateWithLifecycle()
    val fieldColors = steamFieldColors()

    if (showSettings) SettingsHost(vm) { showSettings = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showSettings = true }) { Text("Settings", color = Steam.Copper) }
        }
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
                "Pick a passphrase, then share the room code and passphrase with your friends " +
                    "(up to ${Limits.MAX_ROOM} people). You'll be the host and can remove intruders. " +
                    "The room keeps running even if you leave. After a removal, the passphrase changes automatically for everyone aboard, and Invite shares the new one."
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

// ---------- Members / kick / vote ----------

@Composable
fun MembersDialog(
    vm: ChatViewModel, members: List<Member>, votes: Map<String, VoteInfo>,
    iAmHost: Boolean, onClose: () -> Unit
) {
    var kickTarget by remember { mutableStateOf<Member?>(null) }
    var voteTarget by remember { mutableStateOf<Member?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text("Aboard (${members.size} of ${Limits.MAX_ROOM})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (iAmHost) "You're the host: you can remove anyone instantly."
                    else "The host can remove members. If the host is away, a majority vote can remove an intruder.",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim
                )
                Spacer(Modifier.height(8.dp))
                members.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(m.name)
                                    if (m.isMe) append(" (you)")
                                    if (m.isHost) append("  ★ Host")
                                },
                                fontWeight = FontWeight.Bold
                            )
                            Text(m.id.take(8).uppercase(), style = MaterialTheme.typography.labelSmall,
                                color = Steam.Copper, fontFamily = FontFamily.Monospace)
                            val v = votes[m.id]
                            if (v != null) {
                                Text("Removal vote: ${v.yes.size} yes, ${v.no.size} no",
                                    style = MaterialTheme.typography.labelSmall, color = Steam.Rust)
                            }
                        }
                        if (!m.isMe) {
                            if (iAmHost) {
                                TextButton(onClick = { kickTarget = m }) { Text("Remove", color = Steam.Rust) }
                            } else if (!m.isHost) {
                                TextButton(onClick = { voteTarget = m }) { Text("Vote out", color = Steam.Rust) }
                            }
                        }
                    }
                    HorizontalDivider(color = Steam.CopperDark)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Steam.Brass) } }
    )

    val kt = kickTarget
    if (kt != null) {
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Remove ${kt.name}?") },
            text = { Text("They'll be removed from the room and blocked from rejoining on this device.") },
            confirmButton = { TextButton(onClick = { vm.hostKick(kt.id); kickTarget = null }) { Text("Remove", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text("Cancel", color = Steam.Brass) } }
        )
    }
    val vt = voteTarget
    if (vt != null) {
        AlertDialog(
            onDismissRequest = { voteTarget = null },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Vote to remove ${vt.name}?") },
            text = { Text("Everyone will be asked. They're removed when a majority agrees (at least 2 votes).") },
            confirmButton = { TextButton(onClick = { vm.castVote(vt.id, true); voteTarget = null }) { Text("Start vote", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { voteTarget = null }) { Text("Cancel", color = Steam.Brass) } }
        )
    }
}

// ---------- Chat ----------

@Composable
fun ChatScreen(vm: ChatViewModel, room: RoomInfo) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val hostId by vm.hostId.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var eraseOnLeave by remember { mutableStateOf(false) }
    var searchedTooLong by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val fieldColors = steamFieldColors()
    val ctx = LocalContext.current

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val members = buildMembers(vm.myId, room.name, known, connected, hostId)
    val iAmHost = hostId == vm.myId

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

    if (showSettings) SettingsHost(vm) { showSettings = false }
    if (showMembers) MembersDialog(vm, members, votes, iAmHost) { showMembers = false }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Disembark?") },
            text = {
                Column {
                    Text("You'll leave room ${room.code}. To come back you'll need the code and passphrase.")
                    if (settings.keepHistory) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = eraseOnLeave, onCheckedChange = { eraseOnLeave = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Steam.Brass, uncheckedColor = Steam.Copper, checkmarkColor = Steam.Leather
                                )
                            )
                            Text("Also erase this room's history from this phone", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave(eraseOnLeave); eraseOnLeave = false }) {
                    Text("Leave", color = Steam.Rust)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay", color = Steam.Brass) } }
        )
    }

    // Incoming removal vote I haven't answered yet
    val pending = votes.values.firstOrNull {
        it.target != vm.myId && vm.myId !in it.yes && vm.myId !in it.no && it.target !in skipped
    }
    if (pending != null) {
        val who = known[pending.target] ?: "a member"
        AlertDialog(
            onDismissRequest = { skipped = skipped + pending.target },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Remove $who?") },
            text = { Text("A vote has started to remove $who from the room. Do you vote to remove them?") },
            confirmButton = { TextButton(onClick = { vm.castVote(pending.target, true) }) { Text("Yes, remove", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { vm.castVote(pending.target, false) }) { Text("No", color = Steam.Brass) } }
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
                Text("${peers + 1} of ${Limits.MAX_ROOM} aboard",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
            }
            TextButton(onClick = { invite() }) { Text("Invite", color = Steam.Brass) }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", color = Steam.Brass, fontSize = 20.sp) }
                DropdownMenu(
                    expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Steam.Wood)
                ) {
                    DropdownMenuItem(text = { Text("Members (${peers + 1})") },
                        onClick = { menuOpen = false; showMembers = true })
                    DropdownMenuItem(text = { Text("Settings") },
                        onClick = { menuOpen = false; showSettings = true })
                    DropdownMenuItem(text = { Text("Leave room", color = Steam.Copper) },
                        onClick = { menuOpen = false; confirmLeave = true })
                }
            }
        }
        HorizontalDivider(color = Steam.Brass, thickness = 2.dp)

        if (peers == 0) {
            val (text, color) = when {
                room.created && !searchedTooLong ->
                    "Room built! Tap Invite to share the code and passphrase, then wait for others to board." to Steam.Verdigris
                !room.created && !searchedTooLong ->
                    "Searching the WiFi for this room…" to Steam.Verdigris
                else ->
                    "Nobody else found. Check the WiFi and room code. If someone was removed recently, the passphrase may have changed: ask a member for the new one." to Steam.Rust
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
                if (m.system) {
                    Text(m.text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim,
                        fontStyle = FontStyle.Italic)
                } else {
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
