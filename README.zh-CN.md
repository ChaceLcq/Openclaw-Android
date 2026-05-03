# Openclaw-Android

[English](README.md) | [中文](README.zh-CN.md)

Openclaw-Android 是基于上游 OpenClaw Android companion app 改造的 Android
版本。它保留了上游 Android node 能力，并加入了内置本地网关运行能力，让手机可以在本机运行
OpenClaw，而不只是连接电脑或服务器上的 gateway。

这个仓库已经按公开源码发布整理：

- Android 包名：`io.github.openclawcn.app`
- 应用名称：`Openclaw-Android`
- 启动图标：独立 adaptive icon 资源
- API 凭证：不内置，用户在 App 内自行填写
- 网关控制：点击断开时会停止 App 自己启动的本地 gateway
- 运行数据目录：App 私有文件目录

## APK 下载

预编译 APK 可以放在本仓库的 GitHub Releases 页面：

```text
https://github.com/<your-github-user>/<your-repo>/releases
```

这个源码仓库不提交 `build/` 构建输出。APK 应该由用户本地构建，或者作为 GitHub Release
附件单独上传。

## 当前状态

这个项目仍属于实验版本。内置本地 gateway 目前主要面向 root 或具备 root 能力的 Android
设备，因为本地运行 OpenClaw runtime 对 Android 系统环境有额外要求。

普通未 root 设备仍然可以使用外部 gateway 连接模式，也就是在电脑或服务器上运行 OpenClaw
gateway，然后用 Android App 连接。

## Root 要求

只有实验性的手机本地 gateway 模式需要 root。普通未 root Android 设备仍然可以把
Openclaw-Android 当作 companion app 使用，连接运行在电脑、服务器或其他可信设备上的外部
OpenClaw gateway。

Root 会因机型而异，可能清空手机数据、影响保修、影响 OTA 更新、影响银行/DRM 类 App，刷错镜像还可能导致设备无法启动。
不要使用来源不明的一键 root 工具。推荐路线是官方解锁 bootloader，然后使用 Magisk 修补 boot 镜像。

通用 root 流程：

1. 备份手机。解锁 bootloader 通常会清空全部用户数据。
2. 在电脑上安装 Android platform-tools，确保可以使用 `adb` 和 `fastboot`。
3. 在手机上打开开发者选项，启用 `OEM 解锁` 和 `USB 调试`。
4. 重启到 bootloader：

   ```bash
   adb reboot bootloader
   ```

5. 按设备厂商要求解锁 bootloader。常见命令是：

   ```bash
   fastboot flashing unlock
   fastboot oem unlock
   ```

   这一步通常会清空手机数据。

6. 下载和当前手机型号、当前系统版本号完全匹配的官方固件，解包得到 `boot.img`；较新的 Android 设备可能是
   `init_boot.img`。
7. 在手机上安装 Magisk，并用 Magisk 修补这个完全匹配的镜像文件。
8. 把 Magisk 生成的 patched 镜像传回电脑，在 bootloader 下刷入：

   ```bash
   fastboot flash boot magisk_patched.img
   ```

   如果设备使用 `init_boot.img`，则刷入对应分区：

   ```bash
   fastboot flash init_boot magisk_patched.img
   ```

9. 重启手机，打开 Magisk，确认 root 已安装。
10. 安装 Openclaw-Android，在系统弹出 root 授权时允许 App 使用 root，然后再使用本地 gateway 模式。

请始终优先参考对应机型和对应固件版本的专门 root 教程。A/B 分区设备、自定义 recovery 设备、三星 Odin
设备，以及 bootloader 不允许解锁的设备，命令和要求都可能不同。

## 和上游 Android App 的区别

| 项目 | 上游 Android App | Openclaw-Android |
| --- | --- | --- |
| 包名 | `ai.openclaw.app` | `io.github.openclawcn.app` |
| 应用名称 | `OpenClaw Node` | `Openclaw-Android` |
| 主要定位 | 连接已有 OpenClaw gateway 的 companion node | companion node，加可选本地 gateway runner |
| Gateway | 通常在电脑或服务器启动，再由 Android 配对连接 | 可以连接外部 gateway，也可以从 App 内启动本地 gateway |
| 模型配置 | 由 gateway 配置管理 | 首次启动可填写 Base URL、API key、模型名 |
| 权限设置 | 一个个 Android 权限提示 | 支持一键请求推荐权限，同时保留单项控制 |
| API key 处理 | 经典外部 gateway 流程下不由 Android App 管理 | 本地 gateway 模式下写入 App 私有配置，不应提交到仓库 |
| 共存安装 | 使用上游包名 | 可以和上游构建同时安装 |
| APK 文件名 | `openclaw-<version>-...apk` | `openclaw-android-<version>-...apk` |

## 首次启动配置

使用本地 gateway 模式时，用户需要自己填写模型服务配置：

- Base URL，例如 Anthropic 兼容接口地址
- API key
- 模型名，例如 `qwen3.6-plus`

首次启动输入框默认是空的。App 不内置默认 Base URL、API key 或模型名，也不会在源码里提交任何真实凭证。

App 会把这些值写入自己的 OpenClaw 配置目录。这个目录位于 Android App 私有数据目录中，不需要额外存储权限。
在 root shell 下，路径通常类似：

```text
/data/user/0/io.github.openclawcn.app/files/openclaw/home/.openclaw/openclaw.json
```

因为这是 App 私有目录，普通 `adb shell` 用户不能直接进入。调试时可以使用 App 界面、debug 构建的
`run-as`，或者在 root 设备上使用 `su`。

## 开源发布安全注意事项

发布前请确认：

1. 不要提交真实 API key、gateway token、`.openclaw` 运行状态或用户生成配置。
2. 不要把 debug keystore 当作正式发布签名使用。
3. 正式 release build 建议使用维护者自己的 keystore 签名。
4. 不要提交 Gradle 构建输出、缓存目录或本地 SDK 配置。
5. 文档里明确说明：用户需要自行准备模型服务账号和 API key。

App 会把模型服务配置保存在 App 私有目录中。对普通 Android 应用数据来说这是合理的，但它不是硬件级保险箱。
如果用户使用 root 设备，应默认认为特权 shell 可以读取本地配置文件。

## 构建

构建环境要求：

- JDK 21，或较新的 Android Studio bundled JDK
- Android SDK，安装 platform 36 和 build-tools
- Node.js 和 npm，并确保在 `PATH` 中
- 可以访问 Maven 仓库和 npm registry 的网络环境
- 国内网络可以使用 npm 镜像：

  ```bash
  export OPENCLAW_ANDROID_NPM_REGISTRY=https://registry.npmmirror.com
  ```

在仓库根目录执行：

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew :app:assemblePlayDebug
./gradlew :app:assembleThirdPartyDebug
```

Windows PowerShell 示例：

```powershell
$env:ANDROID_HOME = "Q:\Android"
.\gradlew :app:assemblePlayDebug
```

Debug APK 输出目录：

```text
app/build/outputs/apk/play/debug/
app/build/outputs/apk/thirdParty/debug/
```

APK 文件名前缀是 `openclaw-android`。

不要提交 `app/build/`、`.gradle/`、`.kotlin/`、`local.properties`、`node_modules/`、
崩溃日志或生成的 APK。这些都已经被 `.gitignore` 忽略，应该留在源码仓库之外。

## 本地 Gateway

Openclaw-Android 默认把手机本地 gateway 放在：

```text
127.0.0.1:18790
```

上游 Android App 常见端口是 `127.0.0.1:18789`。这里使用独立端口，是为了避免两个 App
同时安装时误连到另一个 App 启动的旧 gateway，导致 gateway token mismatch。

当 App 启动本地 gateway 时，会在 App 私有目录写入 PID 文件：

```text
files/openclaw/home/.openclaw/android-gateway.pid
```

点击断开后，App 会断开 Android session，并停止自己启动的本地 gateway。下次点击连接时，App
会重新启动 gateway，以便新的 provider 设置和 gateway token 生效。

如果 `127.0.0.1:18790` 已经可以访问，但不是当前 App 的 PID 文件对应的进程，App 会提示端口被占用，
而不是直接连接到其他 App 的 gateway。

## 权限

首次引导的权限页面提供 `启用推荐权限` 按钮，可以一次请求普通 Android 运行时权限。

通知监听等特殊权限仍然需要跳转 Android 系统设置手动打开，因为 Android 系统不允许 App 静默授予这类权限。
同一个页面也保留了单项权限开关，方便用户只开启自己需要的权限。

## 安装和启动

```bash
./gradlew :app:installPlayDebug
adb shell am start -n io.github.openclawcn.app/ai.openclaw.app.MainActivity
```

Kotlin 源码包名仍然保留为 `ai.openclaw.app`。Android 允许运行时 application id
和 Kotlin namespace 不同。保留源码包名可以减少后续同步上游 OpenClaw 时的冲突。

## 运行目录

本地 gateway 模式使用 App 私有文件目录：

```text
files/openclaw/
files/openclaw/home/.openclaw/
files/openclaw/home/.openclaw/openclaw.json
files/openclaw/home/.openclaw/android-gateway.log
```

实际绝对路径取决于 Android 用户 id 和包名。这个 fork 通常是：

```text
/data/user/0/io.github.openclawcn.app/files/openclaw/
```

## 和官方 OpenClaw 的关系

这是一个用于 Android 本地 gateway 实验和国内模型服务配置的 fork。除非上游项目明确说明，否则它不是官方
OpenClaw 发行版。

公开发布源码或二进制时，请保留上游许可证说明，并明确标注本 fork 的改动。

## 许可证

上游 OpenClaw 源码使用 MIT 许可证。本 Android fork 默认沿用 MIT 许可证，除非具体文件另有说明。

APK 会打包本地 gateway 模式需要的 Android runtime/native 组件。公开发布 release APK 前，请检查并补齐
`THIRD_PARTY_LICENSES/` 下的第三方许可证说明，包括 Node.js runtime 依赖、OpenSSL、ICU、sqlite、zlib、
c-ares、libc++ 和项目字体等。
