# AGENTS.md — rime_sync_android（trime fork）

trime（同文输入法）个人 fork，基于上游 `v3.3.13`，内置 rime_server 同步客户端（重构计划 M2）。
许可证 GPL-3.0-or-later（上游自带，不要改成别的）。远程：`origin` = 本 GitHub 仓库
（Liang457/rime_sync_android），`upstream` = osfans/trime。fork 增量见根 `README.md` 与
`D:\Users\23386\项目\rime-dev\M2-同文开工报告.md`（详细执行记录）。

## 构建环境（关键约束）

- **本仓库若检出在 `D:\Users\23386\项目\` 下无法构建**——AGP 硬性拒绝非 ASCII 路径。
  构建专用 clone 在 `D:\rime-android\trime`（origin 同为本仓库），rime-dev 里的检出只作镜像浏览。
- 构建环境脚本：`source /d/rime-android/env.sh`（JAVA_HOME=Temurin 25、ANDROID_HOME、
  GRADLE_USER_HOME 全部项目本地化；`BUILD_ABI=arm64-v8a`，置空则编全 4 个 ABI）。
- 构建：`./gradlew :app:assembleDebug` 或 `make release`（后者自带 lua.patch 应用）。
  JDK 用 **25**（trime 全部 CI 都用 25；机器系统默认 Java 25，勿改机器级环境变量）。
- NDK 30 / cmake 4.1.2 由 AGP 自动下载安装，可用 `NDK_VERSION` / `CMAKE_VERSION` 环境变量覆盖。
- submodule 需 `--init --recursive --depth 1`（librime 下还有 6 个嵌套 deps，只 update 顶层会死在
  `installOpenCCData`）。`app/prebuilt` 机制可跳过 native 编译（CI 的 JNI 缓存就靠它）。

## Windows 检出的已知噪音（预期，勿提交）

- 机器未开开发者模式 → 16 处 git symlink（`app/src/main/assets/shared/*` 等）checkout 成
  「目标路径文本」，`git status` 恒显示 modified；`fastlane` icon、
  `build-logic/gradle/wrapper/gradle-wrapper.properties` 同理。rebase 上游后：`git checkout -- .`
  再重跑内容修复（一行脚本存档在 `D:\Users\23386\项目\rime-dev\env\README.md` §trime 仓库注意事项）。
- `librime-lua-deps` 需先打 `patches/lua.patch`：`git apply --directory=app/src/main/jni/librime-lua-deps patches/lua.patch`。
- native 插件在 Windows 无符号链接权限时需 NTFS junction（无需管理员）：
  `sh /d/rime-android/fix-native-links.sh`（junction 存绝对路径，**仓库挪位置后要重跑**）。

## 同步功能（fork 核心，勿破坏）

- `app/src/main/java/com/osfans/trime/sync/`：`ServerSyncApi`（OkHttp + 路径越界防护）、
  `ServerSyncRepository`（状态文件 `.server-sync-state.json`，revision+sha3 差量；librime 合并后
  文件有变即 `deploy()`）、`ServerSyncWorker`（WorkManager 周期任务，默认关，间隔下限 5 分钟）。
- 对上游的挂载点只有 4 处（rebase 冲突面）：`AndroidManifest.xml`、`data/prefs/AppPrefs.kt`、
  `ui/main/NavigationRoute.kt`、`ui/main/settings/ProfileSettingsFragment.kt`，外加
  `app/build.gradle.kts` / `gradle/libs.versions.toml`（okhttp、commons-compress）。
- 服务器线协议 = rime_sync 仓库 v2 API，哈希格式 `sha3-256:<hex>`，**不要改**。
- 踩过坑：`installation.yaml` 的 `installation_id` 不在首行，读它必须 `RegexOption.MULTILINE`（fb37321）。

## CI（.github/workflows/build-release.yml）

- `main` push / 手动触发：ubuntu 上编 release APK（`BUILD_ABI=arm64-v8a`，手动触发可传 `abi`
  输入覆盖），JNI 缓存按 `:app:calculateNativeCacheHash` 键控，产物上传 artifact，**不发布 Release**。
- 签名走 GitHub secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
  （沿用旧 Compose 客户端仓库的同名 secrets，已存在，无需迁移）；写入 trime 原生格式的
  `keystore.properties`（`keyBase64=…`，构建时自行解码）。无 secrets 时产出未签名 APK。
- 上游 5 个 workflow 文件已删（4 OS 全 ABI 矩阵、nightly/release 发 Release，不合 fork 用）；
  rebase 上游后若上游 CI 步骤有变，需手动对照更新本 workflow。

## 上游更新流程

`git fetch upstream` → 找到新 tag → rebase `feat/m2-sync`（= main）→ 重跑 symlink 内容修复 +
lua.patch + fix-native-links.sh → 构建验证 → 打新 tag（版本名来自 `git describe --tags --long`，
**tag 必须推到 origin** 否则 CI 产物版本名退化成裸 commit hash）→ push。
