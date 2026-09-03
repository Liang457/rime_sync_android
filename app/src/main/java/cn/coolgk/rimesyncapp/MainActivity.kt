package cn.coolgk.rimesyncapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cn.coolgk.rimesyncapp.core.CoreLog
import cn.coolgk.rimesyncapp.core.SafRimeFileStore
import cn.coolgk.rimesyncapp.data.LogBuffer
import cn.coolgk.rimesyncapp.ui.RimeSyncApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafRimeFileStore.init(applicationContext)
        CoreLog.sink = { level, message -> LogBuffer.append(level, message) }
        setContent {
            RimeSyncApp()
        }
    }
}