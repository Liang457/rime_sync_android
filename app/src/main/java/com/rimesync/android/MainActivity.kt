package com.rimesync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rimesync.android.core.SafRimeFileStore
import com.rimesync.android.ui.RimeSyncApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafRimeFileStore.init(applicationContext)
        setContent {
            RimeSyncApp()
        }
    }
}