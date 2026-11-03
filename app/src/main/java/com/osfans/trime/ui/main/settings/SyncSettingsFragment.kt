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
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.sync.ServerSyncRepository
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

    private val backgroundSyncEnable = AppPrefs.defaultInstance().profile.periodicBackgroundSync

    // Periodic server sync has no scheduler of its own: it rides on the periodic
    // background sync, so re-enqueue that work to pick up the new constraints.
    private val onServerConfigChange =
        PreferenceDelegate.OnChangeListener<String> { _, _ ->
            if (backgroundSyncEnable.getValue()) {
                viewModel.restartBackgroundSyncWork.value = true
            }
        }

    private var syncNowPreference: Preference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.serverUrl.registerOnChangeListener(onServerConfigChange)
        prefs.apiToken.registerOnChangeListener(onServerConfigChange)
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
                    Preference(ctx).apply {
                        isIconSpaceReserved = false
                        setTitle(R.string.server_sync_auto)
                        setSummary(R.string.server_sync_auto_follows_background)
                        isEnabled = false
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
        prefs.serverUrl.unregisterOnChangeListener(onServerConfigChange)
        prefs.apiToken.unregisterOnChangeListener(onServerConfigChange)
    }
}
