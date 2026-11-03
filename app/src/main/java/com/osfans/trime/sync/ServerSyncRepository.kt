/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sync

import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * The sync action described in the M2 plan:
 * 1. manifest diff against the local state file, download changes into the Rime user dir;
 * 2. `syncUserData` -> push snapshot -> pull other devices -> `syncUserData` merge;
 * 3. redeploy when distributed files changed.
 */
object ServerSyncRepository {
    private const val STATE_NAME = ".server-sync-state.json"
    private const val INSTALLATION_FILE = "installation.yaml"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class State(
        val revision: Long = -1,
        val files: Map<String, String> = emptyMap(),
    )

    data class SyncResult(
        val downloaded: Int = 0,
        val deleted: Int = 0,
        val pushedFiles: Int = 0,
        val pulledFiles: Int = 0,
        val deployed: Boolean = false,
    ) {
        fun detail(): String = listOf(
            "+$downloaded files",
            "-$deleted files",
            "push $pushedFiles",
            "pull $pulledFiles",
        ).joinToString(", ")
    }

    class SyncException(message: String) : IOException(message)

    private fun stateFile(root: File) = File(root, STATE_NAME)

    private fun loadState(root: File): State = stateFile(root).takeIf { it.isFile }
        ?.runCatching { json.decodeFromString<State>(bufferedReader().readText()) }
        ?.getOrNull() ?: State()

    private fun saveState(root: File, state: State) {
        val file = stateFile(root)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { it.write(json.encodeToString(State.serializer(), state)) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw SyncException("Failed to write $file")
        }
    }

    /** `sha3-256:<hex>` tag as used by the server manifest; null when SHA-3 is unavailable. */
    private fun sha3Tag(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA3-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        "sha3-256:" + md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Parse `installation_id` out of the librime-generated installation.yaml. */
    private fun installationId(root: File): String? = File(root, INSTALLATION_FILE).takeIf { it.isFile }
        ?.runCatching {
            Regex("""^\s*installation_id:\s*"?([^"\s]+)"?""", RegexOption.MULTILINE)
                .find(bufferedReader().readText())
                ?.groupValues?.get(1)
        }?.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Tar the files under [dir] with archive-relative paths, gzipped, into [dest]. */
    private fun tarDirectory(
        dir: File,
        dest: File,
    ) {
        TarArchiveOutputStream(GZIPOutputStream(dest.outputStream().buffered()), "UTF-8").use { tar ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                val entry = TarArchiveEntry(f, rel)
                tar.putArchiveEntry(entry)
                f.inputStream().buffered().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    /**
     * Run one full sync round against the configured server.
     *
     * @param rime a ready-or-soon Rime session (created by the caller).
     */
    suspend fun sync(rime: RimeSession): SyncResult = withContext(Dispatchers.IO) {
        val prefs = AppPrefs.defaultInstance().network
        val serverUrl = prefs.serverUrl.getValue().trim()
        val token = prefs.apiToken.getValue().trim()
        if (serverUrl.isEmpty()) throw SyncException("Server URL is not configured")
        val api = ServerSyncApi(serverUrl, token)
        api.checkHealth()

        // --- 1. file diff & download ------------------------------------
        val root = UserDataManager.userDataDir
        val manifest = api.fetchManifest()
        val state = loadState(root)
        val haveState = state.revision >= 0

        val downloads = mutableListOf<String>()
        for ((rel, meta) in manifest.files) {
            val local = ServerSyncPaths.resolve(root, rel)
            if (!local.isFile) {
                downloads += rel
                continue
            }
            val unchanged =
                if (haveState) {
                    state.files[rel] == meta.sha3
                } else {
                    // No state yet (first sync): hash locally to skip identical files.
                    sha3Tag(local)?.let { it == meta.sha3 } ?: false
                }
            if (!unchanged) downloads += rel
        }
        val deletions = state.files.keys.filterNot { it in manifest.files }

        for (rel in deletions) {
            val local = ServerSyncPaths.resolve(root, rel)
            if (local.isFile) local.delete()
            Timber.d("Server sync: deleted %s", rel)
        }
        when {
            downloads.isEmpty() -> Unit
            downloads.size == 1 -> api.downloadFile(downloads[0], ServerSyncPaths.resolve(root, downloads[0]))
            else -> api.downloadFilesTar(downloads, root)
        }

        if (downloads.isNotEmpty() || deletions.isNotEmpty()) {
            saveState(root, State(manifest.revision, manifest.files.mapValues { it.value.sha3 }))
        }

        // --- 2. user db push / pull / merge ------------------------------
        Timber.i("Server sync: root=%s installation=%s", root, File(root, INSTALLATION_FILE))
        val deviceId =
            installationId(root)
                ?: throw SyncException("installation.yaml not found; deploy once before syncing")

        // Generate the local snapshot and push it (server buckets by installation id).
        val ok = rime.runOnReady { syncUserData() }
        if (!ok) throw SyncException("Rime syncUserData failed")
        val syncDir = ServerSyncPaths.resolve(root, "sync")
        val devDir = ServerSyncPaths.resolve(syncDir, deviceId)
        val snapshotFiles = devDir.walkTopDown().filter { it.isFile }.count()
        var pushed = 0
        if (snapshotFiles > 0) {
            val tar = File.createTempFile("trime-userdb", ".tar.gz")
            try {
                tarDirectory(devDir, tar)
                api.pushUserdb(deviceId, tar)
                pushed = snapshotFiles
            } finally {
                tar.delete()
            }
        }

        // Pull other devices' snapshots and merge them into the local user db.
        val pulled = api.pullUserdb(deviceId, syncDir)
        if (pulled > 0) {
            val merged = rime.runOnReady { syncUserData() }
            if (!merged) throw SyncException("Rime merge sync failed")
        }

        // --- 3. redeploy on distributed-file changes ---------------------
        val deployed =
            if (downloads.isNotEmpty() || deletions.isNotEmpty()) {
                rime.runOnReady { deploy() }
                true
            } else {
                false
            }

        // Record the round for the settings summary (manual syncs included).
        AppPrefs.defaultInstance().network.lastSyncStatus.setValue(1)
        AppPrefs.defaultInstance().network.lastSyncDetail.setValue(
            listOf(downloads.size, deletions.size, pushed, pulled, if (deployed) "deploy" else "")
                .joinToString("/") { it.toString() }.removeSuffix("/"),
        )
        AppPrefs.defaultInstance().network.lastSyncTime.setValue(System.currentTimeMillis())

        SyncResult(downloads.size, deletions.size, pushed, pulled, deployed)
    }
}
