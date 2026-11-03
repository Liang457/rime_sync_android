<!--
SPDX-FileCopyrightText: 2026 Cool-GK

SPDX-License-Identifier: GPL-3.0-or-later
-->

# rime_sync_android —— Trime（同文输入法）fork

基于 [osfans/trime](https://github.com/osfans/trime) `v3.3.13` 的个人 fork：**在输入法内内置
rime_server 同步客户端**，手机上的 Rime 配置与用户词库直接与自建
[rime_sync](https://github.com/Liang457/rime_sync) 服务器增量同步，无需单独装一个同步 App。

上游文档：[English](https://github.com/osfans/trime#readme) · [简体中文](README_sc.md) · [繁體中文](README_tc.md) · [Wiki](https://github.com/osfans/trime/wiki)

## fork 增量（相对上游 v3.3.13）

| 改动 | 说明 |
|---|---|
| 内置同步客户端（`app/src/main/java/com/osfans/trime/sync/`） | 按服务器 manifest 差量下载（SHA-3 本地比对，未变文件免重下）；用户词库快照 push/pull，多设备经 librime 合并；文件变更后自动重新部署；定时同步（WorkManager，默认关，间隔 ≥5 分钟） |
| 设置页（Profile → 同步） | 服务器地址 / API 令牌（摘要打码）/ 立即同步（含上次结果摘要）/ 定时开关与间隔，三语文案 |
| AndroidManifest | 新增 `INTERNET` 权限 + `usesCleartextTraffic`（自建服务器是局域网明文 HTTP；targetSdk 28+ 默认禁明文） |

其余功能与上游一致；上游更新通过 rebase 接入（冲突面已压到 4 个挂载文件）。

## 使用

1. 部署 rime_server（见 [rime_sync](https://github.com/Liang457/rime_sync)）。
2. Trime 设置 → 配置 → 同步 → 词库服务器同步：填服务器地址与 API 令牌，点「立即同步」。
3. 定时同步默认关闭；开启后按设定间隔后台自动同步。

## 构建

- CI：push 到 `main` 自动构建**签名 release APK**（Actions artifact 里下载，不发布
  Release），见 [.github/workflows/build-release.yml](.github/workflows/build-release.yml)。
  默认只编 `arm64-v8a`，手动触发可改 ABI。
- 本地：JDK 25 + Android SDK；**Windows 上项目路径必须纯 ASCII**（AGP 硬性要求），所以本机
  构建在 `D:\rime-android\trime` 进行，详见 [AGENTS.md](AGENTS.md)。
- debug 包为 `com.osfans.trime.debug`，可与官方版共存。

## 许可证

[GPL-3.0-or-later](LICENSE)（沿用上游 trime，未改动）。本 fork 的全部修改同样以
GPL-3.0-or-later 发布；上游版权归 Rime community / osfans（见 [AUTHORS](AUTHORS)）。
