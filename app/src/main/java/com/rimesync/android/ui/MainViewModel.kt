package com.rimesync.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rimesync.android.core.RimeSyncConfig
import com.rimesync.android.data.LogBuffer
import com.rimesync.android.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val config: RimeSyncConfig? = null,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val busyKey: String? = null,
    val error: String? = null,
    val lastMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SyncRepository(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(config = repo.loadConfig())
        }
    }

    fun refreshConfig() {
        viewModelScope.launch {
            _state.value = _state.value.copy(config = repo.loadConfig())
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun updateSettings(
        serverUrl: String,
        apiToken: String,
        deviceName: String,
        verifySsl: Boolean,
        timeout: Int,
        retryCount: Int,
        autoSyncEnabled: Boolean,
        autoSyncIntervalHours: Int,
    ) {
        viewModelScope.launch {
            try {
                repo.updateConfig { config ->
                    config.copy(
                        serverUrl = serverUrl.trim().trimEnd('/'),
                        apiToken = apiToken.trim(),
                        deviceName = deviceName.trim(),
                        verifySsl = verifySsl,
                        timeout = timeout,
                        retryCount = retryCount,
                        autoSyncEnabled = autoSyncEnabled,
                        autoSyncIntervalHours = autoSyncIntervalHours,
                    )
                }
                com.rimesync.android.data.SyncScheduler.apply(
                    getApplication(),
                    autoSyncEnabled,
                    autoSyncIntervalHours,
                )
                _state.value = _state.value.copy(config = repo.loadConfig(), lastMessage = "设置已保存")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun updateRimeDir(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                repo.updateConfig { it.copy(rimeDirUri = uri.toString()) }
                _state.value = _state.value.copy(config = repo.loadConfig(), lastMessage = "Rime 目录已选择")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun syncUserdbQuick() {
        launchOp("快速同步中...", "quick") { repo.syncUserdbQuick() }
    }

    fun syncUserdbUpload() {
        launchOp("上传用户词库...", "upload") { repo.syncUserdbUpload() }
    }

    fun syncUserdbDownload() {
        launchOp("下载用户词库...", "download") { repo.syncUserdbDownload() }
    }

    fun syncDicts() {
        launchOp("更新词库...", "dicts") { repo.syncDicts(null) }
    }

    fun downloadFullSync() {
        launchOp("下载完整配置包...", "full_download") { repo.downloadFullSync() }
    }

    fun uploadFullSync(overwrite: Boolean) {
        launchOp("上传完整配置包...", "full_upload") { repo.uploadFullSyncFromLocal(overwrite) }
    }

    private fun launchOp(label: String, key: String, block: suspend () -> Any) {
        viewModelScope.launch {
            setBusy(true, label, key)
            try {
                block()
                _state.value = _state.value.copy(error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                LogBuffer.append("ERROR", "操作失败: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean, label: String? = null, key: String? = null) {
        _state.value = _state.value.copy(
            busy = busy,
            busyLabel = if (busy) label else null,
            busyKey = if (busy) key else null,
        )
    }
}