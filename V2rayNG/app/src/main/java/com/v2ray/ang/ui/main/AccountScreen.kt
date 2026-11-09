package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/** "حساب کاربری" tab: subscription traffic/expiry and quick actions. */
@Composable
fun AccountScreen(
    group: com.v2ray.ang.dto.GroupMapItem?,
    onOpenLink: (String) -> Unit,
    onUpdate: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        val info = group?.usageInfo
        if (group != null && info != null) {
            SubscriptionInfoCard(
                info = info,
                fallbackTitle = group.remarks,
                onOpenLink = onOpenLink,
                onRefresh = onUpdate,
            )
        } else {
            Text(
                stringResource(R.string.account_no_info),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.account_update_sub))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onNavigate(MainDestination.Subscriptions) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.title_sub_setting)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onNavigate(MainDestination.Settings) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.title_settings)) }
    }
}
