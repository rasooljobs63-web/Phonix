package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager

/**
 * Bar under the power button. Shows the selected server and connection state.
 * Tapping it opens the server selection sheet for [groupId].
 */
@Composable
fun ServerStatusBar(
    isRunning: Boolean,
    statusDetail: String,
    selectedGuid: String?,
    groupId: String?,
    onSelectServer: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val serverName = remember(selectedGuid) {
        selectedGuid?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
    }
    val stateText = if (isRunning) {
        stringResource(R.string.server_bar_connected)
    } else {
        stringResource(R.string.server_bar_ready)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = serverName.ifEmpty { stringResource(R.string.server_bar_no_server) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    text = if (isRunning && statusDetail.isNotEmpty()) "$stateText · $statusDetail" else stateText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_more_vert_24dp),
                contentDescription = stringResource(R.string.server_bar_choose),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    if (showSheet) {
        ServerSelectSheet(
            groupId = groupId,
            selectedGuid = selectedGuid,
            onDismiss = { showSheet = false },
            onSelect = { guid ->
                showSheet = false
                onSelectServer(guid)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelectSheet(
    groupId: String?,
    selectedGuid: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val guids = remember(groupId) {
        groupId?.let { MmkvManager.decodeServerList(it) }.orEmpty()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(guids) { guid ->
                val profile = remember(guid) { MmkvManager.decodeServerConfig(guid) } ?: return@items
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(guid) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = guid == selectedGuid, onClick = { onSelect(guid) })
                    Text(
                        text = profile.remarks.ifEmpty { guid },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
