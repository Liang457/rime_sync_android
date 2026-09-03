# rime-sync Android 客户端

rime-sync 的 Android 客户端。用 Jetpack Compose 写的，通过 HTTP API 和 rime-server 通信，在手机上同步 Rime 配置与用户输入词库。与 CLI 客户端共用同一套服务器 API，哈希格式都是 `sha3-256:<hex>`，是同步的线协议，别改。

## 功能

- **用户输入词库同步**：增量上传/下载，哈希对比只传变更文件，冲突按修改时间新者胜
- **词库更新**：增量拉取 cn_dicts / en_dicts / lua / opencc
- **完整同步**：从服务器下载完整配置包，或用本地配置上传覆盖服务器
- **后台定时同步**：WorkManager 定时跑增量用户词库同步
- **日志页**：应用内查看操作日志，同时输出到 Logcat（tag `RimeSync`）

## 要求

- Android 8.0+（minSdk 28）
- 能访问到服务器即可（默认走 HTTP，局域网内用）

## 构建

```bash
.\gradlew.bat :app:assembleDebug
```

工具链：Gradle 9.7.1、AGP 9.4.0、Kotlin 2.4.10、Java 17。发布版 APK 由 GitHub Actions 在 main 分支自动构建（`.github/workflows/build-release.yml`）。

## 使用

首次打开先到「设置」页：

1. 选 Rime 目录（手机存储下的 `rime` 目录，比如 `/storage/emulated/0/rime`），用系统文件夹选择器授权
2. 填服务器地址，如 `http://192.168.1.100:10032`
3. 服务器开了 `api_token` 就填上；局域网 HTTP 一般不用验证 SSL
4. 设备名留空会自动读 `installation.yaml`
5. 需要的话开后台定时同步，设好间隔

然后到「同步」页操作：快速同步用户词库、更新词库、下载/上传完整配置。

## 目录结构

```
app/src/main/java/cn/coolgk/rimesyncapp/
├── MainActivity.kt         # 入口，初始化 SAF 存储
├── core/                   # 同步逻辑与存储抽象
│   ├── ApiClient.kt        # OkHttp 客户端（重试、JSON 导航）
│   ├── SyncEngine.kt       # 用户词库增量同步
│   ├── DictSync.kt         # 词库增量同步
│   ├── FullSync.kt         # 完整配置同步
│   ├── SafRimeFileStore.kt # SAF 文件存储
│   ├── TarUtils.kt         # tar 安全解压
│   ├── SafePath.kt         # 路径校验
│   ├── HashUtils.kt        # SHA3-256
│   └── ConfigRepository.kt # 设置存储（DataStore）
├── data/                   # SyncRepository、SyncWorker/SyncScheduler、LogBuffer
└── ui/                     # Compose：App.kt、MainViewModel、三个页面、主题
```

## 相关项目

- [rime-sync 服务器](https://github.com/Liang457/rime_sync)
- [rime-sync CLI 客户端](https://github.com/Liang457/rime_sync_cli)