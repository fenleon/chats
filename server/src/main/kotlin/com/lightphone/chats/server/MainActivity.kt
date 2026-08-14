package com.lightphone.chats.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lightphone.chats.server.MatrixRepository.ChatConnectionState
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

private const val TAG = "ChatsDev"
private const val DEV_ROOM_CAP = 50

/**
 * Development status/control screen for the companion. The real chat UI is the
 * tool (:app, Phase 3); this screen exists so the Matrix core can be driven and
 * observed without it — login, connection state, rooms, and a thread. It also
 * auto-logs-in from launch extras (homeserver/user/password/tokenLogin) for
 * scripted verification on the emulator.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dev toggle for the companion's verbose logging (Trixnity FINE +
        // HTTP-TRAFFIC): `--es debugLog 1` persists the flag before init, so
        // it applies from the very first log call. Default off (efficiency
        // audit 2026-08-14 — both were always-on and burned standby CPU).
        intent?.getStringExtra("debugLog")?.let {
            getSharedPreferences("chats_account", MODE_PRIVATE).edit()
                .putBoolean("debug_logging", it == "1" || it.equals("true", ignoreCase = true)).apply()
        }
        MatrixRepository.init(this)
        setContent { DevScreen() }
    }
}

@Composable
private fun DevScreen() {
    val themeColors by LightThemeController.colors.collectAsState()
    val connectionState by MatrixRepository.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Scripted login/send via launch extras (dev verification):
    //   --es homeserver http://10.0.2.2:8008 --es user @alice:localhost
    //   --es password alicepass [--es sendTo <roomId> --es sendBody <text>]
    // Runs even when a session is restored, so the send can use the existing client.
    val launchExtras = (LocalContext.current as? MainActivity)?.intent?.extras
    LaunchedEffect(Unit) {
        if (launchExtras?.getString("homeserver") != null) {
            val result = MatrixRepository.login(
                homeserver = launchExtras.getString("homeserver")!!,
                user = launchExtras.getString("user") ?: "",
                passwordOrToken = launchExtras.getString("password") ?: "",
                tokenLogin = launchExtras.getBoolean("tokenLogin"),
            )
            if (result.isFailure) android.util.Log.e(TAG, "scripted login failed", result.exceptionOrNull())
        }
        val sendTo = launchExtras?.getString("sendTo")
        val sendBody = launchExtras?.getString("sendBody")
        android.util.Log.d(TAG, "scripted extras: sendTo=$sendTo sendBody=$sendBody")
        if (sendTo != null && sendBody != null) {
            MatrixRepository.ensureClient()
            runCatching { MatrixRepository.sendMessage(sendTo, sendBody, null) }
                .onSuccess { android.util.Log.d(TAG, "scripted send ok (txn ${it.transactionId})") }
                .onFailure { android.util.Log.e(TAG, "scripted send failed", it) }
        }
    }

    LightTheme(colors = themeColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            val room = selectedRoom
            if (room != null) {
                ThreadView(roomId = room, onBack = { selectedRoom = null })
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(2f.gridUnitsAsDp()),
                ) {
                    LightText(text = "Chats Server", variant = LightTextVariant.Heading)
                    LightText(
                        text = "Companion for the Chats tool — Matrix core (Phase 2)",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                    LightText(text = "Connection: ${connectionLabel(connectionState)}", variant = LightTextVariant.Copy)

                    val accountState = MatrixRepository.accountState()
                    if (accountState.loggedIn) {
                        LightText(
                            text = "Account: ${accountState.userId ?: "?"}",
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        RoomsSection(
                            refreshTick = refreshTick,
                            onOpenRoom = { selectedRoom = it },
                            onMarkRead = { roomId, eventId ->
                                scope.launch { MatrixRepository.markRead(roomId, eventId) }
                            },
                        )
                        Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                        Row {
                            LightText(
                                text = "Refresh",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { refreshTick++ }
                                    .padding(end = 2f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = "Logout",
                                variant = LightTextVariant.Fine,
                                modifier = Modifier.lightClickable {
                                    scope.launch { MatrixRepository.logout() }
                                },
                            )
                        }
                    } else {
                        LoginForm()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginForm() {
    val scope = rememberCoroutineScope()
    var homeserver by remember { mutableStateOf("http://10.0.2.2:8008") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tokenLogin by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))

    val currentField = editingField
    if (currentField != null) {
        val value = when (currentField) {
            "homeserver" -> homeserver
            "user" -> user
            else -> password
        }
        val state = androidx.compose.foundation.text.input.rememberTextFieldState(value)
        val editorKey = remember { java.util.UUID.randomUUID().toString() }
        LightTextInputEditor(
            title = when (currentField) {
                "homeserver" -> "Homeserver"
                "user" -> "User"
                else -> if (tokenLogin) "Access token" else "Password"
            },
            state = state,
            keyboardOptionsFlow = rememberKeyboardOptions(),
            editorKey = editorKey,
            onSubmit = {
                val text = it.toString()
                when (currentField) {
                    "homeserver" -> homeserver = text
                    "user" -> user = text
                    else -> password = text
                }
                editingField = null
            },
            onBack = { editingField = null },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LightTextField(
        label = "Homeserver:",
        value = homeserver,
        placeholder = "https://matrix.org",
        onClick = { editingField = "homeserver" },
    )
    Spacer(modifier = Modifier.height(0.75f.gridUnitsAsDp()))
    LightTextField(
        label = "User:",
        value = user,
        placeholder = "@alice:matrix.org",
        onClick = { editingField = "user" },
    )
    Spacer(modifier = Modifier.height(0.75f.gridUnitsAsDp()))
    LightTextField(
        label = if (tokenLogin) "Access token:" else "Password:",
        value = password,
        placeholder = if (tokenLogin) "syt_..." else "password",
        onClick = { editingField = "password" },
    )
    Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = tokenLogin, onCheckedChange = { tokenLogin = it })
        LightText(
            text = " use access token",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
        )
    }
    Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))

    errorMessage?.let {
        LightText(text = it, variant = LightTextVariant.Fine, modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()))
    }

    LightText(
        text = "Login",
        variant = LightTextVariant.Button,
        modifier = Modifier.lightClickable {
            errorMessage = null
            scope.launch {
                val result = MatrixRepository.login(homeserver, user, password, tokenLogin)
                if (result.isFailure) errorMessage = result.exceptionOrNull()?.message ?: "login failed"
            }
        },
    )
}

@Composable
private fun RoomsSection(
    refreshTick: Int,
    onOpenRoom: (String) -> Unit,
    onMarkRead: (roomId: String, eventId: String) -> Unit,
) {
    var rooms by remember { mutableStateOf<List<LightServiceMethod.GetRooms.Room>?>(null) }

    LaunchedEffect(refreshTick) {
        rooms = MatrixRepository.getRooms()
    }

    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
    LightText(text = "Rooms", variant = LightTextVariant.Subheading)
    Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))

    when {
        rooms == null -> LightText(text = "loading…", variant = LightTextVariant.Fine, lighten = true)
        rooms!!.isEmpty() -> LightText(text = "no rooms yet", variant = LightTextVariant.Fine, lighten = true)
        else -> {
            // Dev-only view: cap at 50 rows — the 1284-room account ANRs the
            // LP3 if composed eagerly (the real UI is the tool's lazy list).
            val shown = rooms!!.take(DEV_ROOM_CAP)
            shown.forEach { room ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.75f.gridUnitsAsDp())
                        .lightClickable {
                            room.lastEventId?.let { onMarkRead(room.id, it) }
                            onOpenRoom(room.id)
                        },
                ) {
                    LightText(text = room.name, variant = LightTextVariant.Copy)
                    LightText(
                        text = buildString {
                            if (room.lastMessage.isNotBlank()) append(room.lastMessage)
                            if (room.unreadCount > 0) append("  (${room.unreadCount} new)")
                        },
                        variant = LightTextVariant.Fine,
                        lighten = true,
                    )
                }
            }
            if (rooms!!.size > shown.size) {
                LightText(
                    text = "… ${rooms!!.size - shown.size} more (dev shows $DEV_ROOM_CAP)",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
            }
        }
    }
}

@Composable
private fun ThreadView(roomId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<LightServiceMethod.GetMessages.Message>?>(null) }
    var composer by remember { mutableStateOf("") }
    var editingComposer by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) {
        messages = MatrixRepository.getMessages(roomId, null, 30).messages
    }

    Column(modifier = Modifier.fillMaxSize().padding(2f.gridUnitsAsDp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = "Back",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier
                    .lightClickable(onClick = onBack)
                    .padding(end = 1f.gridUnitsAsDp()),
            )
            LightText(text = roomId, variant = LightTextVariant.Subheading)
        }
        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

        val msgs = messages
        when {
            msgs == null -> LightText(text = "loading…", variant = LightTextVariant.Fine, lighten = true)
            msgs.isEmpty() -> LightText(text = "no messages", variant = LightTextVariant.Fine, lighten = true)
            else -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                msgs.forEach { m ->
                    LightText(
                        text = if (m.isMine) "me: ${m.body}" else "${m.senderName}: ${m.body}",
                        variant = LightTextVariant.Copy,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
        if (editingComposer) {
            val state = androidx.compose.foundation.text.input.rememberTextFieldState(composer)
            val editorKey = remember { java.util.UUID.randomUUID().toString() }
            LightTextInputEditor(
                title = "Message",
                state = state,
                keyboardOptionsFlow = rememberKeyboardOptions(),
                editorKey = editorKey,
                onSubmit = { composer = it.toString(); editingComposer = false },
                onBack = { editingComposer = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LightTextField(
                label = "Message:",
                value = composer,
                placeholder = "say something",
                onClick = { editingComposer = true },
            )
            LightText(
                text = "Send",
                variant = LightTextVariant.Button,
                modifier = Modifier.lightClickable {
                    if (composer.isNotBlank()) {
                        scope.launch {
                            runCatching { MatrixRepository.sendMessage(roomId, composer.trim(), null) }
                            composer = ""
                            messages = MatrixRepository.getMessages(roomId, null, 30).messages
                        }
                    }
                },
            )
        }
    }
}

private fun connectionLabel(state: ChatConnectionState): String = when (state) {
    ChatConnectionState.LoggedOut -> "not logged in"
    ChatConnectionState.Connecting -> "connecting…"
    ChatConnectionState.Syncing -> "syncing"
    is ChatConnectionState.Offline -> "offline (${state.detail})"
}
