/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.sync.ServerSyncRepository
import com.osfans.trime.sync.ServerSyncWorker
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings for syncing with the self-hosted rime_server (fork feature, see the M2 plan):
 * distributed files (dicts/config/lua/opencc) and user db snapshots.
 */
class SyncSettingsFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val prefs = AppPrefs.defaultInstance().network
    private val lastSyncStatus by prefs.lastSyncStatus
    private val lastSyncDetail by prefs.lastSyncDetail
    private val lastSyncTime by prefs.lastSyncTime

    private val autoSyncEnable = prefs.autoSync

    private val onAutoSyncChange =
        PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
            ServerSyncWorker.start(requireContext().applicationContext)
        }

    private val onAutoSyncIntervalChange =
        PreferenceDelegate.OnChangeListener<Int> { _, _ ->
            if (autoSyncEnable.getValue()) {
                ServerSyncWorker.start(requireContext().applicationContext)
            }
        }

    private var syncNowPreference: Preference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.autoSync.registerOnChangeListener(onAutoSyncChange)
        prefs.autoSyncInterval.registerOnChangeListener(onAutoSyncIntervalChange)
    }

    private fun provideLastSyncSummary(ctx: Context): String = if (lastSyncTime == 0L) {
        ""
    } else {
        val time = customFormatTimeInDefault("yyyy-MM-dd HH:mm", lastSyncTime)
        val status =
            when (lastSyncStatus) {
                1 -> ctx.getString(R.string.success)
                2 -> ctx.getString(R.string.failure)
                else -> "N/A"
            }
        ctx.getString(R.string.server_sync_last_summary, time, status, lastSyncDetail)
    }

    private fun updateSyncNowSummary() {
        syncNowPreference?.summary = provideLastSyncSummary(requireContext())
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.server_sync) {
                isIconSpaceReserved = false
                addPreference(
                    EditTextPreference(ctx).apply {
                        key = AppPrefs.Network.SERVER_URL
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_server_url)
                        summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                    },
                )
                addPreference(
                    EditTextPreference(ctx).apply {
                        key = AppPrefs.Network.API_TOKEN
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_api_token)
                        summaryProvider =
                            Preference.SummaryProvider<EditTextPreference> { pref ->
                                pref.text?.takeIf { it.isNotEmpty() }?.let { it.take(4) + "…" } ?: ""
                            }
                    },
                )
                addPreference(
                    Preference(ctx).apply {
                        syncNowPreference = this
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_now)
                        updateSyncNowSummary()
                        setOnPreferenceClickListener {
                            val rime = viewModel.rime
                            lifecycleScope.launch {
                                withLoadingDialog(ctx) {
                                    runCatching { ServerSyncRepository.sync(rime) }
                                        .onSuccess { result ->
                                            updateSyncNowSummary()
                                            Timber.i("Server sync ok: %s", result.detail())
                                            ctx.toast(ctx.getString(R.string.server_sync_done, result.detail()))
                                        }.onFailure { e ->
                                            prefs.lastSyncStatus.setValue(2)
                                            prefs.lastSyncDetail.setValue(e.message ?: e.javaClass.simpleName)
                                            prefs.lastSyncTime.setValue(System.currentTimeMillis())
                                            updateSyncNowSummary()
                                            Timber.e(e, "Server sync failed")
                                            ctx.toast(ctx.getString(R.string.server_sync_failed, e.message ?: e.javaClass.simpleName))
                                        }
                                }
                            }
                            true
                        }
                    },
                )
                addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = AppPrefs.Network.AUTO_SYNC
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_auto)
                        setDefaultValue(false)
                    },
                )
                addPreference(
                    EditTextIntPreference(ctx).apply {
                        key = AppPrefs.Network.AUTO_SYNC_INTERVAL
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_auto_interval)
                        min = 5
                        setDefaultValue(30)
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        isEnabled = autoSyncEnable.getValue()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSyncNowSummary()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.autoSync.unregisterOnChangeListener(onAutoSyncChange)
        prefs.autoSyncInterval.unregisterOnChangeListener(onAutoSyncIntervalChange)
    }
}
