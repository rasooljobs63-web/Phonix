package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import android.content.ContextWrapper
import android.content.Context
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.PhonixProvisioner
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.PhonixPolicy
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.util.Utils

/** The colors of the simplified screens: the dark purple look of the reference design. */
internal class UiColors(
    val background: Color,
    val backgroundTop: Color,
    val card: Color,
    val inner: Color,
    val field: Color,
    val text: Color,
    val subText: Color,
    val accent: Color,
    val idle: Color,
    val danger: Color,
)

@Composable
internal fun uiColors(): UiColors = UiColors(
    background = Color(0xFF0C0720),
    backgroundTop = Color(0xFF241150),
    card = Color(0xFF1A1236),
    inner = Color(0xFF241A4A),
    field = Color(0xFF2B2057),
    text = Color(0xFFF4F1FF),
    subText = Color(0xFFA79DCB),
    accent = Color(0xFF8B3DFF),
    idle = Color(0xFF7A6FAE),
    danger = Color(0xFFFF6B7A),
)

internal fun Modifier.phonixBackground(c: UiColors): Modifier =
    background(Brush.verticalGradient(listOf(c.backgroundTop, c.background)))

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** The screens are dark: the icons of the status and navigation bars are light. */
@Composable
internal fun PhonixSystemBars() {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
}

/** First screen: nothing works before a key, a subscription link, is added by pasting it or scanning its QR code. */
@Composable
fun KeyEntryScreen(
    isLoading: Boolean,
    onActivate: (String) -> Unit,
    onPaste: () -> String,
    onScanQr: () -> Unit,
) {
    val c = uiColors()
    var key by rememberSaveable { mutableStateOf("") }
    PhonixSystemBars()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .phonixBackground(c)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "\uD83D\uDD11", fontSize = 96.sp)
        Spacer(Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.ui_key_title),
            color = c.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ui_key_subtitle),
            color = c.text,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        val submit = { onActivate(key.trim()) }
        BasicTextField(
            value = key,
            onValueChange = { key = it },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.field)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (key.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_key_hint),
                            color = c.subText,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerField()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { submit() },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.accent,
                contentColor = Color.White,
                disabledContainerColor = c.accent.copy(alpha = 0.6f),
                disabledContentColor = Color.White,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = stringResource(R.string.ui_key_activate),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { key = onPaste().trim() }) {
                Text(stringResource(R.string.ui_key_paste), color = c.accent, fontSize = 15.sp)
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onScanQr) {
                Icon(
                    painter = painterResource(R.drawable.ic_scan_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = c.accent,
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.ui_key_scan_qr), color = c.accent, fontSize = 15.sp)
            }
        }
    }
}

/** The pages of the second screen, the icons of the bar at the bottom. */
internal enum class HomeTab { HOME, SERVERS, PROFILE, MENU }

/**
 * Second screen, in the look of the reference design: the connection with its speeds and the power switch, the
 * servers by name, the profile with the information of the subscription, and the menu with the language, the
 * "Get new IP" scan and the way out. Nothing else of the app is reachable from here.
 */
@Composable
internal fun SimpleHomeScreen(
    title: String,
    info: SubscriptionInfoUiModel?,
    statusText: String,
    isRunning: Boolean,
    isUpdating: Boolean,
    isScanning: Boolean,
    isStarting: Boolean,
    setupState: PhonixProvisioner.Setup,
    servers: List<PhonixPolicy.ServerRow>,
    selectedGuid: String?,
    manualSelection: Boolean,
    downloadSpeed: Long,
    uploadSpeed: Long,
    isPersian: Boolean,
    onSelectServer: (String?) -> Unit,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onGetNewIp: () -> Unit,
    onSetLanguage: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val c = uiColors()
    PhonixSystemBars()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = HomeTab.values()
    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]
    val preparing = setupState == PhonixProvisioner.Setup.PREPARING
    // The time is over, or the traffic is used up
    val ended = info != null && (info.expired || (info.totalBytes > 0L && info.usedBytes >= info.totalBytes))
    var showEnded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ended) { if (ended) showEnded = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .phonixBackground(c)
            .systemBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                HomeTab.HOME -> ConnectionTab(
                    c = c,
                    statusText = statusText,
                    isRunning = isRunning,
                    isStarting = isStarting,
                    isScanning = isScanning,
                    setupState = setupState,
                    ended = ended,
                    downloadSpeed = downloadSpeed,
                    uploadSpeed = uploadSpeed,
                    locationLabel = stringResource(if (manualSelection) R.string.ui_servers_manual else R.string.ui_servers_auto),
                    locationName = servers.firstOrNull { it.guid == selectedGuid }?.name ?: "\u2014",
                    onToggle = onToggle,
                    onOpenServers = { tabIndex = HomeTab.SERVERS.ordinal },
                    title = title,
                    info = info,
                    isUpdating = isUpdating,
                    onUpdate = onUpdate,
                )

                HomeTab.SERVERS -> ServersTab(
                    c = c,
                    servers = servers,
                    selectedGuid = selectedGuid,
                    manualSelection = manualSelection,
                    onSelect = {
                        onSelectServer(it)
                        tabIndex = HomeTab.HOME.ordinal
                    },
                )

                HomeTab.PROFILE -> ProfileTab(
                    c = c,
                    title = title,
                    info = info,
                    ended = ended,
                    isUpdating = isUpdating,
                    onUpdate = onUpdate,
                )

                HomeTab.MENU -> MenuTab(
                    c = c,
                    isPersian = isPersian,
                    isScanning = isScanning,
                    preparing = preparing,
                    onSetLanguage = onSetLanguage,
                    onGetNewIp = onGetNewIp,
                    onLogout = onLogout,
                )
            }
        }
        BottomNav(c = c, selected = tab, onSelect = { tabIndex = it.ordinal })
    }

    if (showEnded) {
        AlertDialog(
            onDismissRequest = { showEnded = false },
            title = { Text(stringResource(R.string.ui_ended_title)) },
            text = { Text(stringResource(R.string.ui_home_expired)) },
            confirmButton = {
                TextButton(onClick = { showEnded = false }) {
                    Text(stringResource(android.R.string.ok), color = c.accent)
                }
            },
        )
    }
}

@Composable
private fun ConnectionTab(
    c: UiColors,
    statusText: String,
    isRunning: Boolean,
    isStarting: Boolean,
    isScanning: Boolean,
    setupState: PhonixProvisioner.Setup,
    ended: Boolean,
    downloadSpeed: Long,
    uploadSpeed: Long,
    locationLabel: String,
    locationName: String,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    title: String,
    info: SubscriptionInfoUiModel?,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
) {
    val preparing = setupState == PhonixProvisioner.Setup.PREPARING
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.ui_nav_home),
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        PowerToggle(c = c, on = isRunning, busy = isStarting, enabled = !preparing, onClick = onToggle)
        Spacer(Modifier.height(20.dp))

        val shownStatus = when {
            isRunning || isScanning || isStarting -> statusText
            setupState == PhonixProvisioner.Setup.READY -> stringResource(R.string.ui_setup_ready)
            setupState == PhonixProvisioner.Setup.FAILED -> stringResource(R.string.ui_setup_failed)
            else -> statusText
        }
        Text(
            text = shownStatus,
            color = when {
                isRunning -> c.accent
                setupState == PhonixProvisioner.Setup.READY -> c.text
                setupState == PhonixProvisioner.Setup.FAILED -> c.danger
                else -> c.subText
            },
            fontSize = if (isRunning) 28.sp else 17.sp,
            fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (preparing) {
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = c.card) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 3.dp)
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.ui_setup_title),
                            color = c.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.ui_setup_wait),
                            color = c.subText,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
        if (ended) {
            Spacer(Modifier.height(12.dp))
            AlertCard(c)
        }
        Spacer(Modifier.weight(1f))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenServers),
            shape = RoundedCornerShape(22.dp),
            color = c.card,
            border = BorderStroke(1.dp, c.idle.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = locationLabel, color = c.subText, fontSize = 13.sp)
                    Text(
                        text = locationName,
                        color = c.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(text = "\u203A", color = c.text, fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        SubscriptionMiniCard(c, title, info, isUpdating, onUpdate)
        Spacer(Modifier.height(8.dp))
    }
}

/** Compact subscription card for Home: name, used / total, bar, days left and an update button. */
@Composable
private fun SubscriptionMiniCard(
    c: UiColors,
    title: String,
    info: SubscriptionInfoUiModel?,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.idle.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = c.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isUpdating) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onUpdate) {
                        Text(stringResource(R.string.ui_home_update_sub), color = c.accent, fontSize = 14.sp)
                    }
                }
            }
            if (info != null) {
                val total = if (info.totalBytes > 0L) info.totalBytes.toTrafficString() else "\u221E"
                Text(
                    text = "${info.usedBytes.toTrafficString()} / $total",
                    color = if (info.trafficLow) c.danger else c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                info.trafficFraction?.let { fraction ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (info.trafficLow) c.danger else c.accent,
                        trackColor = c.field,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val days = info.expireMillis?.let { ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L) }
                Text(
                    text = if (days != null && !info.expired) stringResource(R.string.ui_profile_days_left, days)
                    else if (info.expired) stringResource(R.string.ui_home_expires)
                    else stringResource(R.string.ui_home_no_expiry),
                    color = if (info.expired) c.danger else c.subText,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SpeedCard(c: UiColors, icon: Int, label: String, bytesPerSecond: Long, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.idle.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(BorderStroke(2.dp, c.accent), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = c.accent)
            }
            Spacer(Modifier.size(10.dp))
            Column {
                Text(text = label, color = c.subText, fontSize = 12.sp)
                Text(
                    text = bytesPerSecond.coerceAtLeast(0L).toTrafficString() + "/s",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The switch of the reference design: a pill whose round knob, with the power icon, sits at one end or the other. */
@Composable
private fun PowerToggle(c: UiColors, on: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // The knob is placed by its offset: the control keeps the same direction in Persian as in English
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val knobOffset by animateDpAsState(targetValue = if (on) 140.dp else 0.dp, label = "knob")
        Box(
            modifier = Modifier
                .width(216.dp)
                .height(76.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(RoundedCornerShape(38.dp))
                .background(if (on) c.accent else c.inner)
                .border(BorderStroke(1.dp, c.idle.copy(alpha = 0.4f)), RoundedCornerShape(38.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(30.dp), color = c.accent, strokeWidth = 3.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (on) c.accent else c.idle,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(c: UiColors) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = c.danger.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, c.danger),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "\u26A0", color = c.danger, fontSize = 22.sp)
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.ui_home_expired),
                color = c.danger,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ServersTab(
    c: UiColors,
    servers: List<PhonixPolicy.ServerRow>,
    selectedGuid: String?,
    manualSelection: Boolean,
    onSelect: (String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.ui_servers_title),
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.field)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_servers_search_hint),
                            color = c.subText,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                    }
                    innerField()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(c.card)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            if (servers.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_servers_empty),
                    modifier = Modifier.padding(12.dp),
                    color = c.subText,
                    fontSize = 15.sp,
                )
            } else {
                if (query.isBlank()) {
                    ServerRowItem(
                        colors = c,
                        name = stringResource(R.string.ui_servers_auto),
                        selected = !manualSelection,
                        onClick = { onSelect(null) },
                    )
                }
                servers.filter { it.name.contains(query.trim(), ignoreCase = true) }.forEach { server ->
                    ServerRowItem(
                        colors = c,
                        name = server.name,
                        selected = manualSelection && server.guid == selectedGuid,
                        onClick = { onSelect(server.guid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(
    c: UiColors,
    title: String,
    info: SubscriptionInfoUiModel?,
    ended: Boolean,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_nav_profile),
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        if (ended) AlertCard(c)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.card) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    color = c.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(text = stringResource(R.string.ui_home_traffic_used), color = c.subText, fontSize = 15.sp)
                    val total = if (info.totalBytes > 0L) info.totalBytes.toTrafficString() else "\u221E"
                    Text(
                        text = "${info.usedBytes.toTrafficString()} / $total",
                        color = if (info.trafficLow) c.danger else c.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    info.trafficFraction?.let { fraction ->
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (info.trafficLow) c.danger else c.accent,
                            trackColor = c.field,
                        )
                    }
                    if (info.totalBytes > 0L) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.ui_profile_remaining) + ": " +
                                (info.totalBytes - info.usedBytes).coerceAtLeast(0L).toTrafficString(),
                            color = c.subText,
                            fontSize = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = info.expireMillis?.let {
                            stringResource(R.string.ui_home_expires) + ": " + Utils.formatTimestamp(it, "yyyy-MM-dd")
                        } ?: stringResource(R.string.ui_home_no_expiry),
                        color = if (info.expired) c.danger else c.subText,
                        fontSize = 15.sp,
                    )
                    info.expireMillis?.takeIf { !info.expired }?.let {
                        val days = ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
                        Text(
                            text = stringResource(R.string.ui_profile_days_left, days),
                            color = c.subText,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
        Button(
            onClick = onUpdate,
            enabled = !isUpdating,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.accent,
                contentColor = Color.White,
                disabledContainerColor = c.accent.copy(alpha = 0.6f),
                disabledContentColor = Color.White,
            ),
        ) {
            if (isUpdating) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.ui_home_update_sub), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MenuTab(
    c: UiColors,
    isPersian: Boolean,
    isScanning: Boolean,
    preparing: Boolean,
    onSetLanguage: (String) -> Unit,
    onGetNewIp: () -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_nav_menu),
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.card) {
            Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                Text(
                    text = stringResource(R.string.ui_settings_language),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = c.subText,
                    fontSize = 14.sp,
                )
                ServerRowItem(colors = c, name = "\u0641\u0627\u0631\u0633\u06CC", selected = isPersian) {
                    if (!isPersian) onSetLanguage("fa")
                }
                ServerRowItem(colors = c, name = "English", selected = !isPersian) {
                    if (isPersian) onSetLanguage("en")
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = !isScanning && !preparing, onClick = onGetNewIp),
            shape = RoundedCornerShape(24.dp),
            color = c.card,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isScanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    text = stringResource(if (isScanning) R.string.ui_home_scanning else R.string.ui_home_get_new_ip),
                    color = if (preparing) c.subText else c.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        val menuContext = androidx.compose.ui.platform.LocalContext.current
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable {
                    try {
                        menuContext.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://netlixy.ir/dl/Phonix_universal.apk")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = c.card,
        ) {
            Text(
                text = stringResource(R.string.ui_menu_update_app),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable { confirmLogout = true },
            shape = RoundedCornerShape(24.dp),
            color = c.card,
        ) {
            Text(
                text = stringResource(R.string.ui_settings_logout),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                color = c.danger,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.ui_settings_logout_title)) },
            text = { Text(stringResource(R.string.ui_settings_logout_message)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text(stringResource(R.string.ui_settings_logout), color = c.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.ui_cancel))
                }
            },
        )
    }
}

@Composable
private fun BottomNav(c: UiColors, selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(26.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.idle.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(c, R.drawable.ic_nav_home, R.string.ui_nav_home, selected == HomeTab.HOME) { onSelect(HomeTab.HOME) }
            NavItem(c, R.drawable.ic_nav_servers, R.string.ui_nav_servers, selected == HomeTab.SERVERS) { onSelect(HomeTab.SERVERS) }
            NavItem(c, R.drawable.ic_nav_profile, R.string.ui_nav_profile, selected == HomeTab.PROFILE) { onSelect(HomeTab.PROFILE) }
            NavItem(c, R.drawable.ic_nav_menu, R.string.ui_nav_menu, selected == HomeTab.MENU) { onSelect(HomeTab.MENU) }
        }
    }
}

@Composable
private fun NavItem(c: UiColors, icon: Int, label: Int, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
            modifier = Modifier.size(26.dp),
            tint = if (selected) c.accent else c.idle,
        )
    }
}

@Composable
private fun ServerRowItem(
    colors: UiColors,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(BorderStroke(2.dp, if (selected) colors.accent else colors.idle), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            color = colors.text,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoRow(text: String, color: Color, textColor: Color) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CircleButton(
    color: Color,
    onClick: () -> Unit,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(shape = CircleShape, color = color, shadowElevation = 1.dp, modifier = Modifier.size(size)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
