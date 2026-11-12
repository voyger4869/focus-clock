# 🍅 专注时钟 (Focus Clock)

一个**单文件、零依赖**的番茄钟 / 翻页时钟应用，支持 **安卓 + Windows + 网页** 三端运行，并内置**局域网 P2P 数据同步**。

核心应用只有一个 `index.html`（约 3400 行，纯原生 HTML/CSS/JS），无需任何框架、构建工具或第三方库，双击即可在浏览器中运行。

## ✨ 核心特性

- ⏱️ **多种计时模式**：专注 / 短休息 / 长休息 / 正计时
- 🎨 **四种时钟装扮**：圆环进度、翻页时钟、大数字、极简、模拟表盘
- 🔄 **局域网 P2P 同步**：纯局域网点对点，无服务器，手动配对码连接，多设备数据实时同步
- 📊 **可视化统计**：7 天趋势、30 天趋势、时段分布、任务分布、日历热力图、专注时间轴等
- ✅ **任务管理 + 习惯打卡 + 倒数日**：专注关联任务，习惯养成，重要日子倒计时
- 🏆 **成就徽章系统**：首次番茄、连续 7 天、累计 100 番茄等 12 个成就
- 🎯 **每日目标**：设定每日专注目标，实时进度条
- 🔔 **后台计时不冻结**：安卓端前台服务保活，锁屏/后台也能准确计时并结束提醒
- 📱 **通知栏内联控制**：安卓通知栏实时显示剩余时间，可直接暂停/跳过/开始休息
- 💾 **数据导出/导入**：JSON 全量备份、CSV 记录导出（带 BOM，Excel 直接打开）
- 🌗 **多主题**：多种配色主题 + 强调色自定义

## 🚀 快速开始

### 网页版（最简单）

直接用浏览器打开项目根目录的 `index.html` 即可，无需任何安装。

### 安卓版

在 [releases/](./releases/) 目录下载最新 APK 直接安装（历史版本见 [GitHub Releases](../../releases)），或参考下方「构建」章节自行打包。

### Windows 桌面版

前往 [GitHub Releases](../../releases) 下载 `.exe` 安装包，或参考下方「构建」章节自行打包。

## 🏗️ 项目结构

```
focus-clock/
├── index.html                 # 核心应用（单文件，零依赖）
├── manifest.json              # PWA 配置
├── sw.js                      # Service Worker（离线缓存）
├── icon.svg / icon-*.png      # 应用图标
│
├── electron/                  # Electron 桌面版工程
│   ├── main.js                # 主进程（窗口/托盘/通知）
│   ├── preload.js             # 安全桥（contextIsolation）
│   ├── package.json           # electron-builder 打包配置
│   └── index.html             # 同核心应用（打包时同步）
│
├── android-app/               # 安卓原生 WebView 壳工程
│   ├── app/src/main/java/     # MainActivity.java + TimerService.java
│   ├── app/src/main/assets/   # 同核心应用（打包时同步）
│   ├── app/build.gradle       # 构建配置（签名信息从 keystore.properties 读取）
│   └── keystore.properties.example  # 签名配置模板
│
├── doc/                       # 详细文档（功能清单/开发进度/踩坑记录等）
└── releases/                  # 最新版本安装包（历史版本见 GitHub Releases）
```

> 💡 **重要**：`index.html` 是唯一的核心代码源。修改后需同步到 `electron/index.html` 和 `android-app/app/src/main/assets/index.html` 两处（内容完全一致），三端才能保持一致。

## 🔨 构建

### 前置要求

| 平台 | 依赖 |
|------|------|
| 网页 | 无（直接用浏览器打开） |
| 安卓 | JDK 17 + Android SDK（`sdk.dir` 配置在 `local.properties`） |
| Windows | Node.js + npm |

### 安卓 APK

```bash
cd android-app

# 1. 配置 SDK 路径（首次）
#    Windows:  local.properties 写入  sdk.dir=C:\\path\\to\\android-sdk

# 2. 配置签名（可选，不配置则构建未签名 APK）
#    复制 keystore.properties.example 为 keystore.properties 并填入信息

# 3. 构建
./gradlew assembleRelease        # macOS / Linux
gradlew.bat assembleRelease      # Windows

# 产物在 app/build/outputs/apk/release/app-release.apk
```

### Windows 桌面版

```bash
cd electron
npm install
npm run dist        # 生成 NSIS 安装包 + portable 便携版，产物在 dist/
```

## 📄 文档

详细文档见 [doc/](./doc/) 目录：

- [功能清单](./doc/功能清单.md) — 已实现功能详解
- [开发进度](./doc/开发进度.md) — 版本历程与里程碑
- [踩坑记录](./doc/踩坑记录.md) — 技术难点与解决方案
- [开发环境](./doc/开发环境.md) — 环境搭建与构建命令
- [开发方向](./doc/开发方向.md) — 未来规划

## 📦 版本

最新版本：**v1.6.2**（最新 APK 见 [releases/](./releases/) 目录）

完整版本历史与安装包请前往 [GitHub Releases](../../releases) 下载。

## 📜 许可证

[MIT](./LICENSE) © 2026 专注时钟 Contributors
