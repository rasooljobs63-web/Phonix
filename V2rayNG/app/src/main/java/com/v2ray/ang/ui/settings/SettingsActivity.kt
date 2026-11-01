package com.v2ray.ang.ui.settings

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.PhonixProvisioner
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.uiColors
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings: the language, Persian or English, and the way out, which removes the key from this device. */
class SettingsActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        SimpleSettingsScreen(onBack = { finish() }, onLogout = ::logout)
    }

    /** Stops the connection, removes the subscriptions and every profile, and goes back to the key screen. */
    private fun logout() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    LauncherManager.stopService(applicationContext)
                    MmkvManager.decodeSubscriptions()
                        .filter { it.subscription.url.isNotBlank() }
                        .forEach { MmkvManager.tryRemoveSubscription(it.guid) }
                    MmkvManager.removeAllServer()
                    MmkvManager.setSelectServer("")
                    PhonixProvisioner.reset()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to log out", e)
                }
            }
            setResult(RESULT_OK)
            finish()
        }
    }
}

@Composable
private fun SimpleSettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val c = uiColors()
    var language by remember {
        mutableStateOf(MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto") ?: "auto")
    }
    val isPersian = language == "fa" || (language == "auto" && Utils.getSysLocale().language == "fa")
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = stringResource(R.string.ui_back),
                    tint = c.text,
                )
            }
            Text(
                text = stringResource(R.string.title_settings),
                color = c.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = c.card) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.ui_settings_language),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = c.subText,
                    fontSize = 14.sp,
                )
                LanguageRow(label = "فارسی", selected = isPersian) {
                    if (!isPersian) {
                        language = "fa"
                        AppLocaleManager.setApplicationLanguage("fa")
                    }
                }
                LanguageRow(label = "English", selected = !isPersian) {
                    if (isPersian) {
                        language = "en"
                        AppLocaleManager.setApplicationLanguage("en")
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { confirmLogout = true },
            shape = RoundedCornerShape(28.dp),
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
        Spacer(Modifier.height(8.dp))
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
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = uiColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.idle),
            modifier = Modifier.size(48.dp),
        )
        Text(text = label, color = c.text, fontSize = 17.sp, modifier = Modifier.padding(start = 4.dp))
    }
}
