package com.rimesync.android.core

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class RimeSyncConfig(
    val serverUrl: String,
    val timeout: Int,
    val retryCount: Int,
    val verifySsl: Boolean,
    val apiToken: String,
    val deviceName: String,
    val rimeDirUri: String?,
    val autoSyncEnabled: Boolean,
    val autoSyncIntervalHours: Int,
) {
    companion object {
        val DEFAULTS = RimeSyncConfig(
            serverUrl = "http://192.168.8.8:10032",
            timeout = 30,
            retryCount = 3,
            verifySsl = false,
            apiToken = "",
            deviceName = "",
            rimeDirUri = null,
            autoSyncEnabled = false,
            autoSyncIntervalHours = 24,
        )
    }
}

private val Context.dataStore by preferencesDataStore(name = "rime_sync_config")

class ConfigRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TIMEOUT = intPreferencesKey("timeout")
        val RETRY_COUNT = intPreferencesKey("retry_count")
        val VERIFY_SSL = booleanPreferencesKey("verify_ssl")
        val API_TOKEN = stringPreferencesKey("api_token")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val RIME_DIR_URI = stringPreferencesKey("rime_dir_uri")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        val AUTO_SYNC_INTERVAL = intPreferencesKey("auto_sync_interval_hours")
    }

    suspend fun load(): RimeSyncConfig {
        val prefs = context.dataStore.data.first()
        val d = RimeSyncConfig.DEFAULTS
        return RimeSyncConfig(
            serverUrl = prefs[Keys.SERVER_URL] ?: d.serverUrl,
            timeout = prefs[Keys.TIMEOUT] ?: d.timeout,
            retryCount = prefs[Keys.RETRY_COUNT] ?: d.retryCount,
            verifySsl = prefs[Keys.VERIFY_SSL] ?: d.verifySsl,
            apiToken = prefs[Keys.API_TOKEN] ?: d.apiToken,
            deviceName = prefs[Keys.DEVICE_NAME] ?: d.deviceName,
            rimeDirUri = prefs[Keys.RIME_DIR_URI]?.takeIf { it.isNotBlank() },
            autoSyncEnabled = prefs[Keys.AUTO_SYNC_ENABLED] ?: d.autoSyncEnabled,
            autoSyncIntervalHours = prefs[Keys.AUTO_SYNC_INTERVAL] ?: d.autoSyncIntervalHours,
        )
    }

    suspend fun update(transform: (RimeSyncConfig) -> RimeSyncConfig) {
        val current = load()
        val next = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = next.serverUrl
            prefs[Keys.TIMEOUT] = next.timeout
            prefs[Keys.RETRY_COUNT] = next.retryCount
            prefs[Keys.VERIFY_SSL] = next.verifySsl
            prefs[Keys.API_TOKEN] = next.apiToken
            prefs[Keys.DEVICE_NAME] = next.deviceName
            prefs[Keys.AUTO_SYNC_ENABLED] = next.autoSyncEnabled
            prefs[Keys.AUTO_SYNC_INTERVAL] = next.autoSyncIntervalHours
            if (next.rimeDirUri.isNullOrEmpty()) {
                prefs.remove(Keys.RIME_DIR_URI)
            } else {
                prefs[Keys.RIME_DIR_URI] = next.rimeDirUri
            }
        }
    }

    suspend fun save(config: RimeSyncConfig) = update { config }

    suspend fun setRimeDir(uri: Uri) {
        update { it.copy(rimeDirUri = uri.toString()) }
    }

    /** 获取当前 rime 目录 store；若未授权或权限丢失返回 null。 */
    suspend fun getRimeStore(): SafRimeFileStore? {
        val uriStr = load().rimeDirUri ?: return null
        val uri = Uri.parse(uriStr)
        if (!SafRimeFileStore.hasPersistedPermission(uri)) return null
        return SafRimeFileStore(uri)
    }

    /**
     * 解析设备名：配置值优先；为空或 unknown 时从 installation.yaml 读取；
     * 仍无法获取则返回 "unknown"，与 CLI 行为一致。
     */
    suspend fun resolveDeviceName(store: RimeFileStore?): String {
        val configured = load().deviceName
        if (configured.isNotBlank() && configured != "unknown") return configured
        val fromInstallation = store?.readInstallationId() ?: return configured.ifBlank { "unknown" }
        if (fromInstallation.isNotBlank()) {
            update { it.copy(deviceName = fromInstallation) }
        }
        return fromInstallation.ifBlank { "unknown" }
    }
}