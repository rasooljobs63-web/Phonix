package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.core.PhonixProvisioner
import com.v2ray.ang.handler.PhonixPolicy
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerAetherActivity
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    /** Counts the moments the key may have changed, so that the screen looks for it again. */
    private val keyRevision = mutableIntStateOf(0)

    // Phonix: true from a press of the power button until the connection is up, so that a second press does not start a
    // second connection while the first prepares
    private val startingState = mutableStateOf(false)
    private var startJob: kotlinx.coroutines.Job? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            // A log out in the settings removes the key: the groups are read again, and the key is looked for again.
            mainViewModel.onAction(MainAction.RefreshGroups)
            keyRevision.intValue++
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        // Phonix: sets the Aether profile up, when it is not yet, for a subscription already there.
        com.v2ray.ang.core.PhonixProvisioner.kick()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onStart() {
        super.onStart()
        // The service can have gone away while the screen was not shown, without a word if its process died.
        mainViewModel.onAction(MainAction.RefreshServiceState)
        keyRevision.intValue++
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }

        val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
        val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
        val revision = keyRevision.intValue
        // The key: the subscription of the allowed domain. Looked for again when the groups, the loading or a return
        // from the settings may have changed it.
        val sub = remember(uiState.groups, isLoading, revision) { PhonixPolicy.allowedSubs().firstOrNull() }

        if (sub == null) {
            KeyEntryScreen(
                isLoading = isLoading,
                onActivate = { text ->
                    if (text.isEmpty()) toast(R.string.ui_key_empty)
                    else mainViewModel.onAction(MainAction.ImportBatchConfig(text))
                },
                onPaste = { Utils.getClipboard(this) },
                onScanQr = ::importQRcode,
            )
        } else {
            val scope = rememberCoroutineScope()
            val setupState by PhonixProvisioner.setupState.collectAsStateWithLifecycle()
            var scanning by remember { mutableStateOf(false) }
            var serversRevision by remember { mutableIntStateOf(0) }
            // The pick comes from the screen and the service: the list is read again then
            val servers = remember(uiState.groups, uiState.selectedGuid, uiState.isRunning, setupState, serversRevision) {
                PhonixPolicy.serverRows()
            }
            val manualGuid = remember(servers, serversRevision) { PhonixPolicy.manualServerGuid() }
            val info = remember(sub) {
                sub.subscription.usageInfo?.let { buildSubscriptionInfoUiModel(it, System.currentTimeMillis()) }
            }
            val statusText = when (val status = uiState.status) {
                MainStatus.Disconnected -> stringResource(R.string.ui_home_status_disconnected)
                MainStatus.Connected -> stringResource(R.string.ui_home_status_connected)
                else -> mainViewModel.formatStatus(status)
            }
            SimpleHomeScreen(
                title = PhonixPolicy.aetherNameOf(sub),
                info = info,
                statusText = when {
                    scanning -> stringResource(R.string.ui_home_scanning)
                    startingState.value && !uiState.isRunning -> stringResource(R.string.ui_home_preparing)
                    else -> statusText
                },
                isStarting = startingState.value && !uiState.isRunning,
                isRunning = uiState.isRunning,
                isUpdating = isLoading,
                isScanning = scanning,
                setupState = setupState,
                servers = servers,
                selectedGuid = uiState.selectedGuid,
                manualSelection = manualGuid != null,
                onSelectServer = { guid ->
                    // null gives the pick back to the lowest delay; a key is a server chosen by hand
                    PhonixPolicy.setManualServerName(guid?.let { key -> servers.firstOrNull { it.guid == key }?.name })
                    (guid ?: PhonixPolicy.randomServer())?.let { setSelectServer(it) }
                    serversRevision++
                },
                onToggle = {
                    if (setupState == PhonixProvisioner.Setup.PREPARING) toast(R.string.ui_setup_wait)
                    else handleFabAction()
                },
                onUpdate = { mainViewModel.onAction(MainAction.UpdateSubscriptions) },
                onGetNewIp = {
                    if (scanning) return@SimpleHomeScreen
                    if (uiState.isRunning) {
                        toast(R.string.ui_home_disconnect_first)
                        return@SimpleHomeScreen
                    }
                    scanning = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            PhonixProvisioner.rescanEndpoint(applicationContext)
                        }
                        scanning = false
                        when (result) {
                            PhonixProvisioner.RescanResult.FOUND -> toastSuccess(R.string.ui_home_ip_found)
                            PhonixProvisioner.RescanResult.NOT_FOUND -> toastError(R.string.ui_home_ip_not_found)
                            PhonixProvisioner.RescanResult.SESSION_ACTIVE -> toast(R.string.ui_home_disconnect_first)
                            PhonixProvisioner.RescanResult.BUSY -> toast(R.string.ui_home_ip_busy)
                            PhonixProvisioner.RescanResult.KEYS_MISSING -> toastError(R.string.ui_home_keys_missing)
                            PhonixProvisioner.RescanResult.NO_PROFILE -> toastError(R.string.toast_failure)
                        }
                    }
                },
                onOpenSettings = { navigateTo(MainDestination.Settings) },
            )
        }
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun openSubscriptionLink(url: String) {
        // Links come from a remote panel, so only http(s) and tg:// are ever handed to ACTION_VIEW.
        val safeUrl = SubscriptionInfoParser.sanitizeLink(url) ?: return
        Utils.openUri(this, safeUrl)
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
            MainDestination.Promotion -> {
                Utils.openUri(
                    this,
                    "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"
                )
                return
            }
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
            // A service whose process died takes no stop and answers nothing; asking for its state right
            // after lets that silence show the screen as stopped instead of leaving it connected.
            mainViewModel.onAction(MainAction.RefreshServiceState)
        } else if (!startingState.value) {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        // Phonix: the servers of the subscription are tested through the tunnel before each connection, and the one
        // with the lowest delay is the one that connects
        if (startJob?.isActive == true) return
        startingState.value = true
        startJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { com.v2ray.ang.core.PhonixProvisioner.prepareStart(this@MainActivity) }
                LauncherManager.startService(this@MainActivity)
                // The service takes a moment to report that it runs; a press in between would start it again
                kotlinx.coroutines.delay(2500L)
            } finally {
                startingState.value = false
            }
        }
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            EConfigType.AETHER.value -> Intent(this, ServerAetherActivity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            EConfigType.AETHER -> ServerAetherActivity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
