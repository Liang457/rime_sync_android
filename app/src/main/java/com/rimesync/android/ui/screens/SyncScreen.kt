package com.rimesync.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rimesync.android.data.LogBuffer
import com.rimesync.android.ui.MainViewModel
import com.rimesync.android.ui.UiState
import com.rimesync.android.ui.components.ActionButton
import com.rimesync.android.ui.components.InlineProgress
import com.rimesync.android.ui.components.SectionCard
import com.rimesync.android.ui.components.SecondaryActionButton

@Composable
fun SyncScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val logs by LogBuffer.entries.collectAsState()
    val latestLog = logs.lastOrNull()?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("用户输入词库") {
            Text(
                "快速：先下载其他设备变更，再上传本机词库。冲突时较新的修改时间胜出。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ActionButton("快速", onClick = { viewModel.syncUserdbQuick() })
            OperationProgress(state, "quick", latestLog)
            Spacer(modifier = Modifier.height(4.dp))
            SecondaryActionButton("上传", onClick = { viewModel.syncUserdbUpload() })
            OperationProgress(state, "upload", latestLog)
            Spacer(modifier = Modifier.height(4.dp))
            SecondaryActionButton("下载", onClick = { viewModel.syncUserdbDownload() })
            OperationProgress(state, "download", latestLog)
        }

        SectionCard("更新词库") {
            Text(
                "增量同步 cn_dicts / en_dicts / lua / opencc，从服务器拉取变更文件。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ActionButton("更新词库", onClick = { viewModel.syncDicts() })
            OperationProgress(state, "dicts", latestLog)
        }

        SectionCard("完整同步") {
            Text(
                "下载：从服务器拉取完整配置包覆盖本地。上传：将本地 Rime 目录打包上传，会覆盖服务器配置！",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ActionButton("下载完整配置", onClick = { viewModel.downloadFullSync() })
            OperationProgress(state, "full_download", latestLog)
            Spacer(modifier = Modifier.height(4.dp))
            SecondaryActionButton("上传完整配置（覆盖服务器）", onClick = { viewModel.uploadFullSync(true) })
            OperationProgress(state, "full_upload", latestLog)
        }
    }
}

@Composable
private fun OperationProgress(state: UiState, key: String, latestLog: String?) {
    if (state.busy && state.busyKey == key) {
        InlineProgress(label = state.busyLabel ?: "处理中...", latestLog = latestLog)
    }
}