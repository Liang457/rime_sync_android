# AGENTS.md

Android client (Compose, Material3) for syncing Rime configuration and user dictionaries with a remote server. There is a companion CLI/server project; this app must stay **wire- and behavior-compatible** with it (see `core/*.kt` docstrings referencing "CLI 的 core/sync.py / dicts.py / fullsync.py").

## Commands

- Build: `.\gradlew.bat :app:assembleDebug --no-daemon --console=plain`
- Unit tests (JUnit4 + kotlinx-coroutines-test): `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
- Works with `--offline` once the Gradle distribution and deps are cached.
- **必须加 `--no-daemon`（加 `--console=plain` 避免 ANSI 乱码）。** 否则 Gradle 守护进程会持有 stdout 句柄，命令不输出结束信号，在工具调用里卡死。同一轮可多次复用输出，无需重复完整构建。
- **不要动系统 Java 环境。** 系统默认是 Oracle Java 25（`C:\Program Files\Java\jdk-25.0.4.1`），用户要靠它跑 HMCL；`JAVA_HOME`/PATH 只在单次命令内临时覆盖（如 `$env:JAVA_HOME='...jdk-25.0.4.1'`），构建目标是 Java 17 字节码（`compileOptions`/`jvmTarget` = 17），在 JDK 21/25 下都能构建，无需改机器级环境变量。本机另装有 Temurin JDK 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`）仅供备用。
- Toolchain: Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.10 (AGP 9 has built-in Kotlin — do NOT apply `org.jetbrains.kotlin.android`; only `org.jetbrains.kotlin.plugin.compose` is used), Java 17, compileSdk/targetSdk 36 (Android 16, capped deliberately — several libs were pinned to the last API-36-compatible versions: core-ktx 1.18.0, lifecycle 2.10.0, navigation-compose 2.9.8, okhttp 5.4.0, Compose BOM 2026.06.01), minSdk 28. No version catalog (dependencies are hardcoded strings in `app/build.gradle.kts`). No README, no CI, no lint config.

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