# AGENTS.md

Android client (Compose, Material3) for syncing Rime configuration and user dictionaries with a remote server. There is a companion CLI/server project; this app must stay **wire- and behavior-compatible** with it (see `core/*.kt` docstrings referencing "CLI 的 core/sync.py / dicts.py / fullsync.py").

## Commands

- Build: `.\gradlew.bat :app:assembleDebug`
- Unit tests (JUnit4 + kotlinx-coroutines-test): `.\gradlew.bat :app:testDebugUnitTest`
- Works with `--offline` once the Gradle distribution and deps are cached.
- Toolchain: Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Java 17, compileSdk/targetSdk 36, minSdk 28. No version catalog (dependencies are hardcoded strings in `app/build.gradle.kts`). No README, no CI, no lint config.

## Architecture

- `core/` — transport-agnostic sync logic and the `RimeFileStore` abstraction:
  - `SyncEngine.kt` — incremental user-dict sync (`sync/<device>/…` dirs on server). `diffSyncState` semantics: same hash → skip; different hash → newer mtime wins (compared as UTC via `TimeUtils.safeParseIso`); unparseable/equal mtime → file is both uploaded AND downloaded. `_manifest.json` is skipped.
  - `DictSync.kt` / `FullSync.kt` — full-config sync; `SyncState` persists server-clock timestamps (`.sync_state.json`) as the `since` cursor.
  - `ApiClient.kt` — OkHttp, retry w/ exponential backoff, JSON navigation helpers (`str/obj/arr/bool/lng`). Endpoints: `/api/device/list`, `/api/sync/info`, `/api/sync/upload/{tar,file}`, `/api/sync/get/{device}/file/{filename}`, `/api/full_sync/{info,download,upload}`.
  - `SafRimeFileStore.kt` — SAF-backed impl; `RimeFileStore` is the interface. JVM tests must use `InMemoryStore` (test source set) — SAF code requires Android.
  - `ConfigRepository.kt` — DataStore prefs (`rime_sync_config`); defaults include `serverUrl=http://192.168.8.8:10032`, `verifySsl=false`.
- `data/` — `SyncRepository` (facade building ApiClient + store), `SyncWorker`/`SyncScheduler` (WorkManager periodic sync, needs foreground-service notification), `LogBuffer` (in-process log shown on Logs screen + Logcat, tag `RimeSync`).
- `ui/` — Compose: `App.kt` nav host (Sync/Settings/Logs), `MainViewModel`, screens, theme.

## Critical conventions

- **Hash format is `sha3-256:<hex>`** (see `HashUtils.computeFileHash`). Must match the CLI/server exactly — a plain hex digest breaks sync. This is a wire contract.
- **Path safety is mandatory.** Every path from the server or config must pass through `SafePath.normalize` / `validateFileName` (rejects absolute paths, drive letters, `..`, path separators in filenames → throws `PathTraversalException`). `TarUtils.extractTar` relies on this; do not bypass it.
- **`SafRimeFileStore.init(applicationContext)` must run before any SAF store use** — it's set in `MainActivity.onCreate` (`SafRimeFileStore.context` is a `lateinit` static).
- JSON parsing uses `org.json` for `.sync_state.json` and manual `kotlinx.serialization.json` navigation in `ApiClient`; no serializable DTOs.
- UI labels, comments, and user-facing error messages are in Chinese — keep that convention.
- `AndroidManifest.xml` sets `usesCleartextTraffic="true"` (HTTP LAN servers are the norm); SSL verification is off by default.