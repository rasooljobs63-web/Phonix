package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
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
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.util.Utils

/** The colors of the simplified screens: the look of the reference design, light and dark. */
internal class UiColors(
    val background: Color,
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
internal fun uiColors(): UiColors =
    if (LocalDarkTheme.current) {
        UiColors(
            background = Color(0xFF121216),
            card = Color(0xFF1C1C21),
            inner = Color(0xFF26262D),
            field = Color(0xFF2A2A31),
            text = Color(0xFFF2F2F7),
            subText = Color(0xFF9A9AA6),
            accent = Color(0xFF0A84FF),
            idle = Color(0xFF6F8491),
            danger = Color(0xFFFF6B6B),
        )
    } else {
        UiColors(
            background = Color(0xFFF0F0F7),
            card = Color(0xFFFFFFFF),
            inner = Color(0xFFF0F0F7),
            field = Color(0xFFE5E5E5),
            text = Color(0xFF000000),
            subText = Color(0xFF6B6B6B),
            accent = Color(0xFF007AFF),
            idle = Color(0xFFB0BEC5),
            danger = Color(0xFFD93025),
        )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
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

/**
 * Second screen: the power button, and the subscription's name, traffic and expiry, which can only be looked at, with
 * its update and the "Get new IP" scan. Nothing else of the app is reachable from here but the settings.
 */
@Composable
internal fun SimpleHomeScreen(
    title: String,
    info: SubscriptionInfoUiModel?,
    statusText: String,
    isRunning: Boolean,
    isUpdating: Boolean,
    isScanning: Boolean,
    setupState: PhonixProvisioner.Setup,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onGetNewIp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = uiColors()
    val ring = if (isRunning) c.accent else c.idle
    val preparing = setupState == PhonixProvisioner.Setup.PREPARING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(
                color = c.card,
                onClick = onOpenSettings,
                contentDescription = stringResource(R.string.ui_settings_open),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = c.idle,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(c.card)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .border(BorderStroke(8.dp, ring), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(84.dp),
                    tint = ring,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val shownStatus = when {
            isRunning || isScanning -> statusText
            setupState == PhonixProvisioner.Setup.READY -> stringResource(R.string.ui_setup_ready)
            setupState == PhonixProvisioner.Setup.FAILED -> stringResource(R.string.ui_setup_failed)
            else -> statusText
        }
        Text(
            text = shownStatus,
            color = when {
                isRunning -> c.accent
                setupState == PhonixProvisioner.Setup.READY -> c.accent
                setupState == PhonixProvisioner.Setup.FAILED -> c.danger
                else -> c.subText
            },
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (preparing) {
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.card) {
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
        Spacer(Modifier.height(20.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = c.card,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = c.inner,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                modifier = Modifier.weight(1f),
                                color = c.text,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            CircleButton(
                                color = c.card,
                                size = 44.dp,
                                onClick = onUpdate,
                                enabled = !isUpdating,
                                contentDescription = stringResource(R.string.ui_home_update_sub),
                            ) {
                                if (isUpdating) {
                                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_refresh_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = c.idle,
                                    )
                                }
                            }
                        }
                        if (info != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.ui_home_traffic_used),
                                color = c.subText,
                                fontSize = 15.sp,
                            )
                            val total = if (info.totalBytes > 0L) info.totalBytes.toTrafficString() else "\u221E"
                            Text(
                                text = "${info.usedBytes.toTrafficString()} / $total",
                                color = if (info.trafficLow) c.danger else c.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            info.trafficFraction?.let { fraction ->
                                Spacer(Modifier.height(8.dp))
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
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = info.expireMillis?.let {
                                    stringResource(R.string.ui_home_expires) + ": " + Utils.formatTimestamp(it, "yyyy-MM-dd")
                                } ?: stringResource(R.string.ui_home_no_expiry),
                                color = if (info.expired) c.danger else c.subText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }

                if (info?.expired == true) {
                    InfoRow(text = stringResource(R.string.ui_home_expired), color = c.inner, textColor = c.danger)
                }

                Button(
                    onClick = onGetNewIp,
                    enabled = !isScanning && !preparing,
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
                    if (isScanning) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.ui_home_scanning), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(stringResource(R.string.ui_home_get_new_ip), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
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
