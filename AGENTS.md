# AGENTS.md — rime_sync_android（trime fork）

trime（同文输入法）个人 fork，基于上游 `v3.3.13`，内置 rime_server 同步客户端（重构计划 M2）。
许可证 GPL-3.0-or-later（上游自带，不要改成别的）。远程：`origin` = 本 GitHub 仓库
（Liang457/rime_sync_android），`upstream` = osfans/trime。fork 增量见根 `README.md` 与
`D:\Users\23386\项目\rime-dev\M2-同文开工报告.md`（详细执行记录）。

## 当前状态（2026-11-03）

- `main` 上的 fork 增量（同步相关 3 commit）：`932336a`（INTERNET + HTTP 明文放行）、
  `29f854c` + `fb37321`（`sync/` 包 + 4 挂载点 + MULTILINE 修复）；**真机端到端验证已通过**
  （vivo V2339A + PC 本机 rime_server：差量下载/删除传播/push/pull 合并/deploy/UI 全绿）。
- 2026-11-03 晚：同步调度统一——删 `ServerSyncWorker` 独立周期任务，服务器
  同步并入 `BackgroundSyncWork`（后台同步顺带执行）；服务器页「定时服务器同步/同步间隔」
  改为灰字说明；新增「允许后台通过流量同步」开关（默认关）；顺带修 `BackgroundSyncWork`
  二次 `WorkManager.initialize` 崩溃隐患。构建通过，真机验收待装包。
- debug APK 便捷副本：`D:\rime-android\trime-3.3.13-debug-arm64.apk`
  （`com.osfans.trime.debug`，与官方版共存）。
- 2026-11-03：fork 增量推送 origin（`main`），release tag `v3.3.13-7` 触发 CI
  构建 Release（Release 名即 tag 名，APK 版本名 `v3.3.13-7-0-g<hash>`）；临时分支
  `feat/m2-sync` 已并回 `main` 删除，**仓库只留 `main` + release tag，不开特性分支**。
- 待办：真机验收调度统一改动；真实服务器上线后改地址复测。
- 旧 Compose 客户端已退役：仓库 main 已是 fork 基线 `dd4bee2`，旧签名材料备份在
  `D:\rime-android\keystore-backup\`。

## 构建环境（关键约束）

- **本仓库若检出在 `D:\Users\23386\项目\` 下无法构建**——AGP 硬性拒绝非 ASCII 路径。
  真实路径是 `D:\rime-android\trime`（构建必须在此 ASCII 路径跑）；rime-dev 工作区里的
  `rime_sync_android\` 是指向它的 **NTFS junction**（2026-11-03 起），两边同一目录，
  改哪边都一样，无需再手动同步。junction 挪位置后需重建（PowerShell `New-Item -ItemType Junction`）。
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
  文件有变即 `deploy()`）。**定时调度只有一处**（2026-11-03 起）：上游 `worker/BackgroundSyncWork`
  （默认关，间隔下限 15 分钟）在本地同步成功且配置了服务器地址时顺带执行服务器同步；
  「允许后台通过流量同步」（默认关）决定 UNMETERED/CONNECTED 约束，未配服务器不加网络
  约束；独立的 `ServerSyncWorker` 已删（勿在 rebase 时捡回来）。
- 对上游的挂载点只有 4 处（rebase 冲突面）：`AndroidManifest.xml`、`data/prefs/AppPrefs.kt`、
  `ui/main/NavigationRoute.kt`、`ui/main/settings/ProfileSettingsFragment.kt`，外加
  `app/build.gradle.kts` / `gradle/libs.versions.toml`（okhttp、commons-compress）。
- 服务器线协议 = rime_sync 仓库 v2 API，哈希格式 `sha3-256:<hex>`，**不要改**。
- 踩过坑：`installation.yaml` 的 `installation_id` 不在首行，读它必须 `RegexOption.MULTILINE`（fb37321）。

## CI（.github/workflows/build-release.yml）

- `v*` tag push / Release 发布 / 手动触发：ubuntu 上编 release APK（`BUILD_ABI=arm64-v8a`，
  手动触发可传 `abi` 输入覆盖），JNI 缓存按 `:app:calculateNativeCacheHash` 键控，产物上传
  artifact 并自动附到同名 Release（tag 构建时自动创建）。
- 签名走 GitHub secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
  （沿用旧 Compose 客户端仓库的同名 secrets，已存在，无需迁移）；写入 trime 原生格式的
  `keystore.properties`（`keyBase64=…`，构建时自行解码）。无 secrets 时产出未签名 APK。
- 上游 5 个 workflow 文件已删（4 OS 全 ABI 矩阵、nightly/release 发 Release，不合 fork 用）；
  rebase 上游后若上游 CI 步骤有变，需手动对照更新本 workflow。

## 上游更新流程

`git fetch upstream` → 找到新 tag → rebase `main` → 重跑 symlink 内容修复 +
lua.patch + fix-native-links.sh → 构建验证 → 打新 tag（版本名来自 `git describe --tags --long`，
**tag 必须推到 origin** 否则 CI 产物版本名退化成裸 commit hash）→ push。
