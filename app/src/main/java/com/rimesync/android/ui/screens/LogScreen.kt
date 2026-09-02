package com.rimesync.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rimesync.android.data.LogBuffer
import com.rimesync.android.data.LogEntry
import com.rimesync.android.ui.components.ActionButton
import com.rimesync.android.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val entries by LogBuffer.entries.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard("日志") {
            ActionButton("清空日志", onClick = { LogBuffer.clear() })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (entries.isEmpty()) {
                Text("暂无日志", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(state = listState) {
                    items(entries) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    val color = when (entry.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "[$time] [${entry.level}] ${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}