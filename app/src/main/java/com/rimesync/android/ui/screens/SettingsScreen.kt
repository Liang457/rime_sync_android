package com.rimesync.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rimesync.android.ui.MainViewModel
import com.rimesync.android.ui.components.ActionButton
import com.rimesync.android.ui.components.InfoRow
import com.rimesync.android.ui.components.SectionCard
import com.rimesync.android.ui.components.SecondaryActionButton

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val config = state.config

    var serverUrl by remember { mutableStateOf(config?.serverUrl ?: "") }
    var apiToken by remember { mutableStateOf(config?.apiToken ?: "") }
    var deviceName by remember { mutableStateOf(config?.deviceName ?: "") }
    var verifySsl by remember { mutableStateOf(config?.verifySsl ?: false) }
    var timeout by remember { mutableStateOf(config?.timeout?.toString() ?: "30") }
    var retryCount by remember { mutableStateOf(config?.retryCount?.toString() ?: "3") }
    var autoSyncEnabled by remember { mutableStateOf(config?.autoSyncEnabled ?: false) }
    var autoSyncInterval by remember { mutableStateOf(config?.autoSyncIntervalHours?.toString() ?: "24") }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // 权限持久化失败不影响本次使用
            }
            viewModel.updateRimeDir(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Rime 配置目录") {
            InfoRow(
                "当前目录",
                if (config?.rimeDirUri.isNullOrEmpty()) "未选择" else "已授权 (${config?.rimeDirUri?.takeLast(40)})",
            )
            Spacer(modifier = Modifier.height(4.dp))
            ActionButton("选择 Rime 目录", onClick = { folderPicker.launch(null) })
            Text(
                "请选择 手机存储 下的 rime 目录（如 /storage/emulated/0/rime）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("服务器") {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                label = { Text("API Token（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text("验证 SSL 证书", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = verifySsl, onCheckedChange = { verifySsl = it })
            }
        }

        SectionCard("超时与重试") {
            OutlinedTextField(
                value = timeout,
                onValueChange = { timeout = it },
                label = { Text("请求超时（秒）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = retryCount,
                onValueChange = { retryCount = it },
                label = { Text("重试次数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("自动同步") {
            Column {
                Text("开启后台定时同步", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = autoSyncEnabled, onCheckedChange = { autoSyncEnabled = it })
            }
            OutlinedTextField(
                value = autoSyncInterval,
                onValueChange = { autoSyncInterval = it },
                label = { Text("同步间隔（小时）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "后台同步会增量上传/下载用户词库，需保持网络连接与授权目录。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("设备") {
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("设备名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "留空时从 installation.yaml 自动读取。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        ActionButton("保存设置", onClick = {
            viewModel.updateSettings(
                serverUrl = serverUrl,
                apiToken = apiToken,
                deviceName = deviceName,
                verifySsl = verifySsl,
                timeout = timeout.toIntOrNull() ?: 30,
                retryCount = retryCount.toIntOrNull() ?: 3,
                autoSyncEnabled = autoSyncEnabled,
                autoSyncIntervalHours = autoSyncInterval.toIntOrNull() ?: 24,
            )
        })
    }
}