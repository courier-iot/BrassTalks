package com.example.p2pchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = (application as BrassApp).chatVm()   // one brain for the whole app, kept alive by the service
        // Pick the colours before the first frame so there's no flash of the wrong theme
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val startDark = when (vm.settings.value.theme) { "light" -> false; "system" -> systemDark; else -> true }
        Steam.apply(startDark)
        applyBars(startDark)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val sysDark = isSystemInDarkTheme()
            val dark = when (settings.theme) { "light" -> false; "system" -> sysDark; else -> true }
            LaunchedEffect(dark) {
                Steam.apply(dark)
                applyBars(dark)
            }
            SteampunkTheme {
                Box(Modifier.fillMaxSize().background(Steam.Backdrop)) {
                    Surface(
                        color = Color.Transparent,
                        contentColor = Steam.Parchment,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                    ) {
                        AppRoot(vm)
                    }
                }
            }
        }
    }

    /** Light or dark status/navigation bar icons to match the theme. */
    private fun applyBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
        Notifier.clearMessages(this)
        (application as BrassApp).chatVm().markActiveRead()
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }
}

// ---------- Which screen? ----------

@Composable
fun AppRoot(vm: ChatViewModel) {
    val name by vm.profileName.collectAsStateWithLifecycle()
    val carriages by vm.carriages.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }

    val nm = name
    val act = active
    when {
        nm == null -> NameScreen { vm.setProfileName(it) }
        act == null || showNew -> HomeScreen(vm, nm, canCancel = carriages.isNotEmpty() && act != null) { showNew = false }
        else -> CarriageShell(vm, carriages, act) { showNew = true }
    }

    val n = notice
    if (n != null) {
        AlertDialog(
            onDismissRequest = { vm.clearNotice() },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Heads up") },
            text = { Text(n) },
            confirmButton = { TextButton(onClick = { vm.clearNotice() }) { Text("OK", color = Steam.Brass) } }
        )
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
fun SettingsDialog(
    s: AppSettings, onChange: (AppSettings) -> Unit, onErase: () -> Unit, onClose: () -> Unit,
    alias: String? = null, serial: String? = null
) {
    var confirmErase by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (alias != null) {
                    Column {
                        Text("Your alias: $alias", fontWeight = FontWeight.Bold)
                        Text("Chosen at first launch and permanent for this install." +
                                (serial?.let { " Serial ${it.take(8).uppercase()}." } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                    }
                }
                Text("Appearance", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("dark" to "Brass", "light" to "Parchment", "system" to "Match phone").forEach { (key, label) ->
                        FilterChip(
                            selected = s.theme == key,
                            onClick = { onChange(s.copy(theme = key)) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Steam.CopperDark,
                                selectedLabelColor = Steam.Parchment,
                                labelColor = Steam.ParchmentDim
                            )
                        )
                    }
                }
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
                SettingRow("Keep photos", "When off, photos are deleted when you leave a carriage or restart the app.", s.keepImages) {
                    onChange(s.copy(keepImages = it))
                }
                SettingRow("Keep videos", "When off, videos are deleted when you leave a carriage or restart the app.", s.keepVideos) {
                    onChange(s.copy(keepVideos = it))
                }
                Text("Default quality for photos and videos", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Quality.presets.forEach { q ->
                        FilterChip(
                            selected = s.quality == q.key,
                            onClick = { onChange(s.copy(quality = q.key)) },
                            label = { Text(q.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Steam.CopperDark,
                                selectedLabelColor = Steam.Parchment,
                                labelColor = Steam.ParchmentDim
                            )
                        )
                    }
                }
                SettingRow("Ask quality each time", "Choose quality (or Original for photos) before every send.", s.askQuality) {
                    onChange(s.copy(askQuality = it))
                }
                SettingRow(
                    "Stay connected in background",
                    "Keeps your carriages alive with the screen off. Shows a notification and uses more battery.",
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
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Notifier.CH_MESSAGES)
                            )
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Steam.Copper),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Steam.Copper),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Message alert sound (steam whistle)") }
                Text("Voice memos are never saved. They disappear when you leave a carriage.",
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
    val alias by vm.profileName.collectAsStateWithLifecycle()
    SettingsDialog(s, { vm.updateSettings(it) }, { vm.eraseAllHistory() }, onClose, alias, vm.myId)
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

// ---------- First launch: who are you? ----------

@Composable
fun NameScreen(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val fieldColors = steamFieldColors()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Gear(96.dp, Steam.Brass, spin = true)
        Spacer(Modifier.height(12.dp))
        Text("BRASSTALKS", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = Steam.Brass)
        Text("Wireless telegraphy for the modern adventurer",
            style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text("What shall we call you, traveller?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your alias") }, singleLine = true,
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        BrassButton("Continue", enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { onDone(name.trim()) }
    }
}

// ---------- Build or board a carriage ----------

@Composable
fun HomeScreen(vm: ChatViewModel, alias: String, canCancel: Boolean, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(true) }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (canCancel) TextButton(onClick = onDone) { Text("← Back", color = Steam.Copper) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) { Text("Settings", color = Steam.Copper) }
        }
        Gear(72.dp, Steam.Brass, spin = true)
        Spacer(Modifier.height(8.dp))
        Text("BRASSTALKS", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = Steam.Brass)
        Text("Sailing as $alias · Serial ${vm.myId.take(8).uppercase()}",
            style = MaterialTheme.typography.labelMedium, color = Steam.Copper)
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Build Carriage", creating, Modifier.weight(1f)) { creating = true }
            ModeButton("Board Carriage", !creating, Modifier.weight(1f)) { creating = false }
        }
        Spacer(Modifier.height(12.dp))

        if (creating) {
            InfoCard(
                "Build a new carriage",
                "Pick a passphrase, then share the carriage code and passphrase with your friends " +
                    "(up to ${Limits.MAX_ROOM} people). You'll be the host and can remove intruders. " +
                    "The carriage keeps running even if you leave. After a removal, the passphrase changes " +
                    "automatically for everyone aboard, and Invite shares the new one."
            )
            Spacer(Modifier.height(12.dp))
            Text("Your carriage code", style = MaterialTheme.typography.labelMedium, color = Steam.ParchmentDim)
            Surface(
                color = Steam.Leather, shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Steam.Brass), modifier = Modifier.fillMaxWidth()
            ) {
                Text(newCode, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 22.sp, textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold, color = Steam.Brass, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { newCode = RoomCodes.generate() }) { Text("New code", color = Steam.Copper) }
            }
            OutlinedTextField(createPw, { createPw = it }, label = { Text("Choose a passphrase (6+ characters)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BrassButton(
                if (joining) "Stoking the boilers…" else "Build the Carriage",
                enabled = !joining && createPw.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { vm.join(newCode, createPw, created = true); onDone() }
        } else {
            InfoCard(
                "Board an existing carriage",
                "Enter the carriage code and passphrase from whoever built it. " +
                    "You both need to be on the same WiFi."
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(joinCode, { joinCode = it }, label = { Text("Carriage code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(joinPw, { joinPw = it }, label = { Text("Passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BrassButton(
                if (joining) "Stoking the boilers…" else "Board the Carriage",
                enabled = !joining && joinCode.isNotBlank() && joinPw.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { vm.join(joinCode.trim().uppercase(), joinPw, created = false); onDone() }
        }
    }
}

// ---------- Menu drawer + current carriage ----------

@Composable
fun CarriageRow(c: CarriageSession, selected: Boolean, onClick: () -> Unit) {
    val room by c.room.collectAsStateWithLifecycle()
    val peers by c.peers.collectAsStateWithLifecycle()
    val unread by c.unread.collectAsStateWithLifecycle()
    val nickname by c.nickname.collectAsStateWithLifecycle()
    Surface(
        onClick = onClick,
        color = if (selected) Steam.CopperDark else Color.Transparent,
        contentColor = Steam.Parchment,
        shape = RoundedCornerShape(6.dp),
        border = if (selected) BorderStroke(1.dp, Steam.Copper) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Gear(24.dp, if (selected) Steam.Brass else Steam.Copper, spin = peers > 0)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (nickname.isBlank()) room.code else nickname,
                    fontFamily = if (nickname.isBlank()) FontFamily.Monospace else FontFamily.Serif,
                    fontWeight = FontWeight.Bold, color = Steam.Brass, maxLines = 1)
                if (nickname.isNotBlank()) {
                    Text(room.code, style = MaterialTheme.typography.labelSmall, color = Steam.Copper,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Text(if (peers == 0) "No one else aboard" else "$peers others aboard",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
            }
            if (unread > 0) {
                Surface(shape = RoundedCornerShape(50), color = Steam.Brass) {
                    Text("$unread", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Steam.Leather, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun CarriageShell(vm: ChatViewModel, carriages: List<CarriageSession>, active: CarriageSession, onNew: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // Ask once for permission to show notifications (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showSettings) SettingsHost(vm) { showSettings = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Steam.Wood, drawerContentColor = Steam.Parchment) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Gear(32.dp, Steam.Brass)
                        Spacer(Modifier.width(12.dp))
                        Text("YOUR CARRIAGES", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = Steam.Brass)
                    }
                    Spacer(Modifier.height(12.dp))
                    carriages.forEach { c ->
                        CarriageRow(c, selected = c === active) {
                            vm.select(c)
                            scope.launch { drawerState.close() }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Steam.CopperDark)
                    TextButton(
                        onClick = { scope.launch { drawerState.close() }; onNew() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("＋ Build or board another carriage", color = Steam.Brass) }
                    TextButton(
                        onClick = { scope.launch { drawerState.close() }; showSettings = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Settings", color = Steam.Copper) }
                }
            }
        }
    ) {
        // key() gives each carriage its own fresh screen state (typed text, dialogs)
        key(active.tag) {
            ChatScreen(active, onOpenMenu = { scope.launch { drawerState.open() } }, onOpenSettings = { showSettings = true })
        }
    }
}

// ---------- Members / kick / vote ----------

@Composable
fun MembersDialog(
    vm: CarriageSession, members: List<Member>, votes: Map<String, VoteInfo>,
    iAmHost: Boolean, onClose: () -> Unit
) {
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    var nickTarget by remember { mutableStateOf<Member?>(null) }
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
                            val nick = if (m.isMe) null else nicks[m.id]
                            Text(
                                buildString {
                                    append(nick ?: m.name)
                                    if (m.isMe) append(" (you)")
                                    if (m.isHost) append("  \u2605 Host")
                                },
                                fontWeight = FontWeight.Bold
                            )
                            Text((if (nick != null) "${m.name} · " else "") + m.id.take(8).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Steam.Copper, fontFamily = FontFamily.Monospace)
                            val v = votes[m.id]
                            if (v != null) {
                                Text("Removal vote: ${v.yes.size} yes, ${v.no.size} no",
                                    style = MaterialTheme.typography.labelSmall, color = Steam.Rust)
                            }
                        }
                        if (!m.isMe) {
                            TextButton(onClick = { nickTarget = m }) { Text("Nickname", color = Steam.Brass) }
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

    val nt = nickTarget
    if (nt != null) {
        NicknameDialog(alias = nt.name, current = nicks[nt.id] ?: "",
            onSave = { vm.people.setNickname(nt.id, it); nickTarget = null }, onClose = { nickTarget = null })
    }
    val kt = kickTarget
    if (kt != null) {
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Remove ${kt.name}?") },
            text = { Text("They'll be removed from the carriage and the passphrase will change so they can't return.") },
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

// ---------- Chat (one carriage) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: CarriageSession, onOpenMenu: () -> Unit, onOpenSettings: () -> Unit) {
    val room by vm.room.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val hostId by vm.hostId.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var eraseOnLeave by remember { mutableStateOf(false) }
    var searchedTooLong by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var showRename by remember { mutableStateOf(false) }
    var openPhoto by remember { mutableStateOf<Msg?>(null) }
    var openVideo by remember { mutableStateOf<Msg?>(null) }
    var pendingMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }   // (picked item, is it a video)
    var replyingTo by remember { mutableStateOf<Msg?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }        // message whose long-press menu is open
    var nickFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // (person id, alias)
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    val fieldColors = steamFieldColors()

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val isVideo = (ctx.contentResolver.getType(uri) ?: "").startsWith("video")
            val q = Quality.of(vm.settings.value.quality)
            when {
                vm.settings.value.askQuality -> pendingMedia = uri to isVideo
                isVideo -> vm.sendVideo(uri, q)
                else -> vm.sendPhoto(uri, q)
            }
        }
    }

    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) { recording = true; recMs = 0L }
        else Toast.makeText(ctx, "Couldn't start the microphone", Toast.LENGTH_SHORT).show()
    }
    fun finishRecording(send: Boolean) {
        if (!recording) return
        recording = false
        if (send) {
            val res = recorder.stop()
            if (res != null) vm.sendVoice(res.first, res.second)
            else Toast.makeText(ctx, "Too short. Tap the mic, speak, then tap Send.", Toast.LENGTH_SHORT).show()
        } else recorder.cancel()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(ctx, "BrassTalks needs the microphone to record voice notes.", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(recording) {
        while (recording) {
            recMs = recorder.elapsedMs()
            if (recMs >= MAX_VOICE_MS) { finishRecording(true); break }
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose { recorder.cancel(); VoicePlayer.stop() }
    }

    val members = buildMembers(vm.myId, room.name, known, connected, hostId)
    val title = if (nickname.isBlank()) room.code else nickname
    val iAmHost = hostId == vm.myId

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        if (AppState.foreground) vm.unread.value = 0
    }
    LaunchedEffect(peers) {
        if (peers > 0) searchedTooLong = false else { delay(20_000); searchedTooLong = true }
    }

    fun invite() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Join my BrassTalks carriage!\nCarriage code: ${room.code}\nPassphrase: ${room.password}\n(Be on the same WiFi.)")
        }
        ctx.startActivity(Intent.createChooser(send, "Invite to carriage"))
    }

    if (showMembers) MembersDialog(vm, members, votes, iAmHost) { showMembers = false }
    if (showRename) {
        RenameDialog(current = nickname, code = room.code,
            onSave = { vm.rename(it); showRename = false }, onClose = { showRename = false })
    }
    val photo = openPhoto
    if (photo != null) PhotoViewer(vm, photo) { openPhoto = null }
    val pm = pendingMedia
    if (pm != null) {
        QualityDialog(
            uri = pm.first, isVideo = pm.second, others = peers,
            default = Quality.of(settings.quality),
            onSend = { q ->
                pendingMedia = null
                if (pm.second) vm.sendVideo(pm.first, q) else vm.sendPhoto(pm.first, q)
            },
            onClose = { pendingMedia = null }
        )
    }
    val nf = nickFor
    if (nf != null) {
        NicknameDialog(alias = nf.second, current = nicks[nf.first] ?: "",
            onSave = { vm.people.setNickname(nf.first, it); nickFor = null }, onClose = { nickFor = null })
    }
    val video = openVideo
    if (video != null) VideoViewer(vm, video) { openVideo = null }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
            title = { Text("Disembark?") },
            text = {
                Column {
                    Text("You'll leave carriage $title. To come back you'll need the code and passphrase.")
                    if (settings.keepHistory) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = eraseOnLeave, onCheckedChange = { eraseOnLeave = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Steam.Brass, uncheckedColor = Steam.Copper, checkmarkColor = Steam.Leather
                                )
                            )
                            Text("Also erase this carriage's history from this phone", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave(eraseOnLeave) }) {
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
            text = { Text("A vote has started to remove $who from carriage $title. Do you vote to remove them?") },
            confirmButton = { TextButton(onClick = { vm.castVote(pending.target, true) }) { Text("Yes, remove", color = Steam.Rust) } },
            dismissButton = { TextButton(onClick = { vm.castVote(pending.target, false) }) { Text("No", color = Steam.Brass) } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Steam.Wood).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenMenu) { Text("☰", color = Steam.Brass, fontSize = 22.sp) }
            Gear(28.dp, Steam.Brass, spin = peers > 0)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Steam.Brass, maxLines = 1,
                    fontFamily = if (nickname.isBlank()) FontFamily.Monospace else FontFamily.Serif)
                Text((if (nickname.isBlank()) "" else "${room.code} · ") + "${peers + 1} of ${Limits.MAX_ROOM} aboard",
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
                    DropdownMenuItem(text = { Text("Rename carriage") },
                        onClick = { menuOpen = false; showRename = true })
                    DropdownMenuItem(text = { Text("Settings") },
                        onClick = { menuOpen = false; onOpenSettings() })
                    DropdownMenuItem(text = { Text("Leave carriage", color = Steam.Copper) },
                        onClick = { menuOpen = false; confirmLeave = true })
                }
            }
        }
        HorizontalDivider(color = Steam.Brass, thickness = 2.dp)

        if (peers == 0) {
            val (text, color) = when {
                room.created && !searchedTooLong ->
                    "Carriage built! Tap Invite to share the code and passphrase, then wait for others to board." to Steam.Verdigris
                !room.created && !searchedTooLong ->
                    "Searching the WiFi for this carriage…" to Steam.Verdigris
                else ->
                    "Nobody else found. Check the WiFi and carriage code. If someone was removed recently, the passphrase may have changed: ask a member for the new one." to Steam.Rust
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
            itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                Column {
                if (i == 0 || !sameDay(messages[i - 1].ts, m.ts)) DateChip(m.ts)
                if (m.system) {
                    Text(m.text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim,
                        fontStyle = FontStyle.Italic)
                } else {
                    val mine = m.senderId == vm.myId
                    val label = if (mine) m.senderName else (nicks[m.senderId] ?: m.senderName)
                    val showMenu = { menuFor = m.id }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Box {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (mine) Steam.CopperDark else Steam.WoodLight,
                            contentColor = Steam.Parchment,
                            border = BorderStroke(1.dp, if (mine) Steam.Copper else Steam.Verdigris),
                            modifier = Modifier.widthIn(max = 300.dp)
                                .combinedClickable(onClick = {}, onLongClick = showMenu)
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                if (!mine) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(label, style = MaterialTheme.typography.labelSmall,
                                            color = Steam.Verdigris, fontWeight = FontWeight.Bold)
                                        if (nicks[m.senderId] != null) {
                                            Text(" · ${m.senderName}", style = MaterialTheme.typography.labelSmall,
                                                color = Steam.ParchmentDim)
                                        }
                                    }
                                }
                                val re = m.replyTo
                                if (re != null) {
                                    val orig = messages.firstOrNull { it.id == re }
                                    ReplyQuote(
                                        who = orig?.let { o -> if (o.senderId == vm.myId) "You" else (nicks[o.senderId] ?: o.senderName) },
                                        snippet = orig?.let { snippet(it) }
                                    ) {
                                        val idx = messages.indexOfFirst { it.id == re }
                                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                                    }
                                }
                                if (m.image != null) {
                                    PhotoBubble(vm, m, transfers[m.id], mine) { openPhoto = m }
                                } else if (m.video != null) {
                                    VideoBubble(vm, m, transfers[m.id], preparing[m.id], mine) { openVideo = m }
                                } else if (m.voice != null) {
                                    VoiceBubble(vm, m, transfers[m.id], mine)
                                } else {
                                    Text(m.text)
                                }
                                Text(timeOf(ctx, m.ts), style = MaterialTheme.typography.labelSmall,
                                    color = Steam.ParchmentDim, modifier = Modifier.align(Alignment.End))
                            }
                        }
                        DropdownMenu(
                            expanded = menuFor == m.id, onDismissRequest = { menuFor = null },
                            modifier = Modifier.background(Steam.Wood)
                        ) {
                            DropdownMenuItem(text = { Text("Reply") }, onClick = {
                                menuFor = null; replyingTo = m
                            })
                            if (m.text.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                                    menuFor = null
                                    clipboard.setText(AnnotatedString(m.text))
                                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                })
                            }
                            if (!mine) {
                                DropdownMenuItem(
                                    text = { Text(if (nicks[m.senderId] == null) "Give ${m.senderName} a nickname" else "Change nickname") },
                                    onClick = { menuFor = null; nickFor = m.senderId to m.senderName }
                                )
                            }
                        }
                        }
                    }
                }
                }
            }
        }

        HorizontalDivider(color = Steam.Copper)
        val rt = replyingTo
        if (rt != null) {
            Row(
                Modifier.fillMaxWidth().background(Steam.Wood).padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Replying to " + (if (rt.senderId == vm.myId) "yourself" else (nicks[rt.senderId] ?: rt.senderName)),
                        style = MaterialTheme.typography.labelSmall, color = Steam.Brass, fontWeight = FontWeight.Bold)
                    Text(snippet(rt), style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim, maxLines = 1)
                }
                TextButton(onClick = { replyingTo = null }) { Text("\u2715", color = Steam.Copper) }
            }
        }
        if (recording) {
            Row(
                Modifier.fillMaxWidth().background(Steam.Wood).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { finishRecording(false) }) { Text("Cancel", color = Steam.Copper) }
                Text("\u25CF", color = Steam.Rust, fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text("Recording ${fmtDur(recMs)} / ${fmtDur(MAX_VOICE_MS)}", modifier = Modifier.weight(1f))
                BrassButton("Send", enabled = true) { finishRecording(true) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Steam.Wood).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }) { Text("\uD83D\uDCF7", fontSize = 22.sp) }
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecording()
                    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("\uD83C\uDFA4", fontSize = 22.sp) }
                OutlinedTextField(
                    input, { input = it }, placeholder = { Text("Dispatch a message") },
                    colors = fieldColors, modifier = Modifier.weight(1f), maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                BrassButton("Transmit", enabled = input.isNotBlank()) { vm.send(input, replyingTo?.id); input = ""; replyingTo = null }
            }
        }
    }
}


// ---------- Photos ----------

@Composable
fun TransferBar(p: Float?, mine: Boolean) {
    if (p == null) return
    Spacer(Modifier.height(4.dp))
    if (p < 0f) {
        Text(if (mine) "Not sent. Try again." else "Didn't arrive in full.",
            style = MaterialTheme.typography.labelSmall, color = Steam.Rust)
    } else {
        LinearProgressIndicator(
            progress = { p }, color = Steam.Brass, trackColor = Steam.Leather,
            modifier = Modifier.width(160.dp)
        )
        Text((if (mine) "Sending " else "Receiving ") + "${(p * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim)
    }
}

@Composable
fun PhotoBubble(vm: CarriageSession, m: Msg, progress: Float?, mine: Boolean, onClick: () -> Unit) {
    // Incoming photos only exist once every piece has arrived, so reload when the transfer finishes
    val arriving = !mine && progress != null
    val state by produceState<Pair<Boolean, ImageBitmap?>>(false to null, m.image, arriving) {
        value = if (arriving) false to null else true to withContext(Dispatchers.IO) {
            vm.readImage(m)?.let { ImageUtil.decodeSampled(it, 700)?.asImageBitmap() }
        }
    }
    val (done, bmp) = state
    Column {
        if (bmp != null) {
            Image(
                bitmap = bmp, contentDescription = "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp)
                    .clickable(onClick = onClick)
            )
        } else {
            Text(
                when {
                    arriving -> "\uD83D\uDCF7 Photo on its way"
                    done -> "Photo no longer available"
                    else -> "Loading photo…"
                },
                style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim, fontStyle = FontStyle.Italic
            )
        }
        TransferBar(progress, mine)
    }
}

@Composable
fun VoiceBubble(vm: CarriageSession, m: Msg, progress: Float?, mine: Boolean) {
    val playing by VoicePlayer.playing.collectAsStateWithLifecycle()
    val pos by VoicePlayer.position.collectAsStateWithLifecycle()
    val ready = mine || progress == null
    val isMe = playing == m.voice
    val total = m.durMs.coerceAtLeast(1L)
    Column(Modifier.width(220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val bytes = vm.readVoice(m)
                    if (bytes != null) VoicePlayer.toggle(m.voice!!, bytes)
                },
                enabled = ready && (mine || progress == null),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Steam.Brass),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Steam.Brass),
                modifier = Modifier.size(40.dp)
            ) { Text(if (isMe) "\u25A0" else "\u25B6") }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { if (isMe) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f },
                    color = Steam.Verdigris, trackColor = Steam.Leather,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("\uD83C\uDFA4 " + (if (isMe) "${fmtDur(pos)} / " else "") + fmtDur(m.durMs),
                    style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim)
            }
        }
        TransferBar(progress, mine)
        if (ready && vm.readVoice(m) == null) {
            Text("Voice note expired (not kept after leaving or restarting)",
                style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun PhotoViewer(vm: CarriageSession, m: Msg, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp by produceState<ImageBitmap?>(null, m.image) {
        value = withContext(Dispatchers.IO) {
            vm.readImage(m)?.let { ImageUtil.decodeSampled(it, 2048)?.asImageBitmap() }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).safeDrawingPadding()) {
            val b = bmp
            if (b != null) {
                Image(bitmap = b, contentDescription = "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp))
            } else {
                CircularProgressIndicator(color = Steam.Brass, modifier = Modifier.align(Alignment.Center))
            }
            Text(m.senderName, color = Steam.ParchmentDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (Build.VERSION.SDK_INT >= 29 && bmp != null) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = savePhoto(ctx, vm.readImage(m))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, if (saved) "Saved to Pictures/BrassTalks" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Save", color = Steam.Brass) }
                }
                TextButton(onClick = onClose) { Text("Close", color = Steam.Brass) }
            }
        }
    }
}

private fun savePhoto(ctx: android.content.Context, bytes: ByteArray?): Boolean {
    if (bytes == null || Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BrassTalks_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BrassTalks")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    } catch (e: Exception) { false }
}

// ---------- Rename ----------

@Composable
fun RenameDialog(current: String, code: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text("Rename carriage") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(30) }, singleLine = true,
                    label = { Text("Your name for this carriage") }, colors = steamFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Only you see this name. Others still see the code ($code). Leave it blank to use the code.",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = Steam.Brass) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Steam.Copper) } }
    )
}


// ---------- Videos ----------

@Composable
fun VideoBubble(vm: CarriageSession, m: Msg, progress: Float?, preparing: Float?, mine: Boolean, onClick: () -> Unit) {
    val arriving = !mine && progress != null
    val thumb by produceState<ImageBitmap?>(null, m.video, arriving) {
        value = withContext(Dispatchers.IO) {
            vm.readThumb(m)?.let { ImageUtil.decodeSampled(it, 480)?.asImageBitmap() }
        }
    }
    val playable by produceState(false, m.video, progress, preparing) {
        value = preparing == null && (mine || progress == null) && withContext(Dispatchers.IO) { vm.hasVideo(m) }
    }
    Column {
        Box(
            Modifier.widthIn(max = 240.dp)
                .then(if (playable) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(bitmap = t, contentDescription = "Video", contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp))
            } else {
                Box(Modifier.size(200.dp, 120.dp).background(Steam.Leather))
            }
            Surface(shape = RoundedCornerShape(50), color = Steam.Leather.copy(alpha = 0.75f)) {
                Text(if (playable) "\u25B6" else "\u23F3", color = Steam.Brass, fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        Text("\uD83C\uDFAC " + fmtDur(m.durMs), style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim)
        if (preparing != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { preparing }, color = Steam.Copper, trackColor = Steam.Leather,
                modifier = Modifier.width(160.dp)
            )
            Text("Preparing ${(preparing * 100).toInt()}% (shrinking to send)",
                style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim)
        }
        TransferBar(progress, mine)
        if (!playable && preparing == null && progress == null) {
            Text("Video no longer available", style = MaterialTheme.typography.labelSmall,
                color = Steam.ParchmentDim, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun VideoViewer(vm: CarriageSession, m: Msg, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // The stored copy is encrypted; a temporary private copy is made for playing and deleted on close
    val tmp = remember { File(ctx.cacheDir, "play_${m.id}.mp4") }
    val ready by produceState<Boolean?>(null, m.video) {
        value = withContext(Dispatchers.IO) { vm.exportVideo(m, tmp) }
    }
    DisposableEffect(Unit) { onDispose { tmp.delete() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            when (ready) {
                null -> CircularProgressIndicator(color = Steam.Brass, modifier = Modifier.align(Alignment.Center))
                false -> Text("Video no longer available", color = Steam.Parchment,
                    modifier = Modifier.align(Alignment.Center))
                true -> AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            val controls = MediaController(c)
                            controls.setAnchorView(this)
                            setMediaController(controls)
                            setVideoPath(tmp.absolutePath)
                            setOnPreparedListener { start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                )
            }
            Text(m.senderName, color = Steam.ParchmentDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (Build.VERSION.SDK_INT >= 29 && ready == true) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = saveVideo(ctx, vm, m)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, if (saved) "Saved to Movies/BrassTalks" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Save", color = Steam.Brass) }
                }
                TextButton(onClick = onClose) { Text("Close", color = Steam.Brass) }
            }
        }
    }
}

private fun saveVideo(ctx: android.content.Context, vm: CarriageSession, m: Msg): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "BrassTalks_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BrassTalks")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = ctx.contentResolver.openOutputStream(uri)?.use { vm.copyVideo(m, it) } ?: false
        if (!ok) ctx.contentResolver.delete(uri, null, null)
        ok
    } catch (e: Exception) { false }
}


// ---------- Quality chooser ----------

private fun fileSize(ctx: android.content.Context, uri: Uri): Long? = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }
} catch (e: Exception) { null }

@Composable
fun QualityDialog(
    uri: Uri, isVideo: Boolean, others: Int, default: Quality,
    onSend: (Quality) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf(default) }
    // Videos: length in ms. Photos: size of the original file in bytes.
    val info by produceState<Long?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (isVideo) VideoUtil.durationMs(ctx, uri) else fileSize(ctx, uri)
        }
    }
    val options = if (isVideo) Quality.presets else Quality.presets + Quality.ORIGINAL
    fun sizeOf(q: Quality): Long? = when {
        isVideo -> info?.let { videoEstimateBytes(q, it) }
        q == Quality.ORIGINAL -> info
        else -> q.photoEstimate
    }
    val tooLong = isVideo && (info ?: 0L) > MAX_VIDEO_MS + 999
    val tooBig = !isVideo && choice == Quality.ORIGINAL && (info ?: 0L) > ImageUtil.MAX_ORIGINAL

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text(if (isVideo) "Send video" else "Send photo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isVideo && info != null) {
                    Text("Length ${fmtDur(info!!)}", style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                    Spacer(Modifier.height(4.dp))
                }
                options.forEach { q ->
                    val size = sizeOf(q)
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = q }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = choice == q, onClick = { choice = q },
                            colors = RadioButtonDefaults.colors(selectedColor = Steam.Brass, unselectedColor = Steam.Copper)
                        )
                        Column {
                            Text(q.label + (size?.let { " · ≈ " + fmtSize(it) } ?: ""), fontWeight = FontWeight.Bold)
                            Text(if (isVideo) q.videoDesc else q.photoDesc,
                                style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val per = sizeOf(choice)
                if (per != null && others > 0) {
                    Text("Each of the $others others aboard gets their own copy: about ${fmtSize(per * others)} sent in total.",
                        style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                }
                if (choice == Quality.ORIGINAL) {
                    Spacer(Modifier.height(4.dp))
                    Text("Location and camera serial numbers are removed. HEIC, PNG and other formats are sent as a full-size JPEG so every phone can open them.",
                        style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
                }
                if (isVideo) {
                    Spacer(Modifier.height(4.dp))
                    Text("Original-quality video is coming later as tap-to-download.",
                        style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim, fontStyle = FontStyle.Italic)
                }
                if (tooLong) {
                    Spacer(Modifier.height(4.dp))
                    Text("Videos can be up to 5 minutes long.", color = Steam.Rust, style = MaterialTheme.typography.bodySmall)
                }
                if (tooBig) {
                    Spacer(Modifier.height(4.dp))
                    Text("This photo is over 25 MB. Choose High instead.", color = Steam.Rust, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(choice) }, enabled = !tooLong && !tooBig) {
                Text("Send", color = if (tooLong || tooBig) Steam.ParchmentDim else Steam.Brass)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Steam.Copper) } }
    )
}


// ---------- Timestamps, replies, nicknames ----------

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Time in the phone's own 12/24-hour style. */
private fun timeOf(ctx: android.content.Context, ts: Long): String =
    android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ts))

@Composable
fun DateChip(ts: Long) {
    val now = System.currentTimeMillis()
    val label = when {
        sameDay(ts, now) -> "Today"
        sameDay(ts, now - 86_400_000L) -> "Yesterday"
        else -> android.text.format.DateFormat.format("EEE, MMM d, yyyy", Date(ts)).toString()
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = Steam.Wood, border = BorderStroke(1.dp, Steam.CopperDark)) {
            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, color = Steam.ParchmentDim)
        }
    }
}

/** Short description of a message, for reply quotes. */
fun snippet(m: Msg): String = when {
    m.image != null -> "\uD83D\uDCF7 Photo"
    m.video != null -> "\uD83C\uDFAC Video"
    m.voice != null -> "\uD83C\uDFA4 Voice note"
    else -> m.text.replace('\n', ' ').take(100)
}

@Composable
fun ReplyQuote(who: String?, snippet: String?, onClick: () -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp).background(Steam.Leather.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Steam.Brass))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (who == null) {
                Text("Original message not available", style = MaterialTheme.typography.labelSmall,
                    color = Steam.ParchmentDim, fontStyle = FontStyle.Italic)
            } else {
                Text(who, style = MaterialTheme.typography.labelSmall, color = Steam.Brass, fontWeight = FontWeight.Bold)
                Text(snippet ?: "", style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim, maxLines = 2)
            }
        }
    }
}

@Composable
fun NicknameDialog(alias: String, current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Steam.Wood, titleContentColor = Steam.Brass, textContentColor = Steam.Parchment,
        title = { Text("Nickname for $alias") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(24) }, singleLine = true,
                    label = { Text("Your name for them") }, colors = steamFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Only you see this, in every carriage you share with them. Their own alias ($alias) stays the same. Leave blank to remove.",
                    style = MaterialTheme.typography.bodySmall, color = Steam.ParchmentDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = Steam.Brass) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Steam.Copper) } }
    )
}
