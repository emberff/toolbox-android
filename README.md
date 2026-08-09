# 工具箱 (Toolbox)

一个模块化的 Android 工具应用。主页为模块选择界面，便于后续持续扩展新工具。

## 功能模块

### 1. X 视频下载
- **剪贴板自动监听**：复制 X / 推特 分享链接，应用自动识别并下载视频
- **手动输入链接**：在输入框粘贴视频链接，点击「下载」即可
- 后台服务常驻 + 通知栏实时进度
- 视频保存至 `Download/XVideoDownloader` 目录

### 2. 种子资源下载
- 支持 **磁力链接 (Magnet)** 与 **.torrent 文件**
- 基于 libtorrent 原生引擎（jlibtorrent 1.2.0），支持 DHT、多 Tracker、做种
- 前台服务后台持续下载，开机自动恢复未完成任务
- **断点续传**（fastresume 机制）
- **边下边播**：MP4 / WebM / TS / 3GP 等格式下载中即可播放（ExoPlayer 流式读取 + piece 优先调度）
- MKV / AVI 等格式下载完成后调用系统播放器播放
- 保存至 `Download/种子下载` 目录

## 技术栈

| 项目 | 版本 |
|---|---|
| 语言 | Kotlin |
| UI | Material 3 |
| Android Gradle Plugin | 8.4.2 |
| Gradle | 8.9 |
| JDK | 17 |
| compileSdk / minSdk | 34 / 24 |
| 种子引擎 | jlibtorrent 1.2.0（libtorrent 原生库，内置 arm64 / arm / x86 / x86_64） |
| 播放器 | AndroidX Media3 ExoPlayer 1.4.1 |

## 项目结构

```
app/src/main/java/com/xvd/app/
├── HomeActivity.kt            # 主页：模块选择
├── ModuleAdapter.kt           # 模块列表适配器
├── MainActivity.kt            # X 视频下载功能页
├── ClipboardProcessor.kt      # 剪贴板 / 输入链接解析
├── TweetParser.kt             # X/推特 链接正则解析
├── VideoFetcher.kt            # 视频地址解析（fxtwitter API）
├── Downloader.kt              # HTTP 下载 + MediaStore 保存
├── ClipboardMonitorService.kt # 剪贴板监听前台服务
├── OverlayHelper.kt           # 悬浮窗辅助
├── TorrentActivity.kt         # 种子下载功能页
├── TorrentEngine.kt           # libtorrent Session 封装
├── TorrentManager.kt          # 任务状态与持久化
├── TorrentService.kt          # 种子下载前台服务
├── TorrentStreamSource.kt     # 边下边播自定义 DataSource
├── TorrentPlayerActivity.kt   # 流式播放页
├── TorrentMediaPicker.kt      # 视频文件识别
├── NotificationHelper.kt      # 通知渠道与进度
├── DownloadBus.kt             # 事件总线（StateFlow）
└── BootReceiver.kt            # 开机恢复服务
```

## 构建

```bash
# 需要 JDK 17 与 Android SDK（local.properties 中配置 sdk.dir）
gradle assembleRelease

# 产物
app/build/outputs/apk/release/app-release.apk
```

签名：release 构建使用 `keystore/release.jks`（已从仓库中排除，本地保留）。

## 安装说明

- 通过侧载方式安装 APK
- **种子下载**：Android 11+ 需在设置中授予「所有文件访问」权限（应用内有引导入口）
- **X 视频自动监听**：Android 10+ 需授予「显示在其他应用上层」权限；未授予时可手动粘贴链接或点击「立即读取当前剪贴板」

## 扩展新模块

主页模块列表位于 `HomeActivity.kt`，为 `modules` 列表添加一项即可：

```kotlin
ModuleItem(
    title = "新工具",
    subtitle = "功能说明",
    iconRes = R.drawable.ic_module_xxx,
    target = NewToolActivity::class.java
)
```

新建对应的 Activity 并在 `AndroidManifest.xml` 中注册即可。

## 免责声明

请仅用于下载拥有合法权利的资源，遵守所在地区法律法规。
