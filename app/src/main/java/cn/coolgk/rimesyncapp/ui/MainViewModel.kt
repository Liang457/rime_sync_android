package cn.coolgk.rimesyncapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.coolgk.rimesyncapp.core.RimeSyncConfig
import cn.coolgk.rimesyncapp.data.LogBuffer
import cn.coolgk.rimesyncapp.data.SyncRepository
import cn.coolgk.rimesyncapp.data.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class UiState(
    val config: RimeSyncConfig? = null,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val busyKey: String? = null,
    val error: String? = null,
    val lastMessage: String? = null,
    val backgroundSyncRunning: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SyncRepository(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(config = repo.loadConfig())
        }
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK_NAME)
                .map { infos ->
                    infos.any { it.state == WorkInfo.State.RUNNING }
                }
                .distinctUntilChanged()
                .collect { running ->
                    _state.value = _state.value.copy(backgroundSyncRunning = running)
                }
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
                cn.coolgk.rimesyncapp.data.SyncScheduler.apply(
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