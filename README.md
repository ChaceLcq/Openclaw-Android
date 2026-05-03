# Openclaw-Android

[English](README.md) | [中文](README.zh-CN.md)

Openclaw-Android is an Android fork of the upstream OpenClaw companion app.
It keeps the upstream Android node features and adds an embedded local gateway
runtime so a phone can run OpenClaw locally instead of only connecting to a
gateway on another machine.

This fork is prepared for public source release:

- Android package name: `io.github.openclawcn.app`
- Launcher name: `Openclaw-Android`
- Launcher icon: independent adaptive icon resources
- API credentials: not bundled; users enter provider settings in the app
- Gateway control: Disconnect stops the app-owned local gateway before reconnect
- Runtime data directory: the app private files directory

## APK Download

Prebuilt APKs can be attached to this repository's GitHub Releases page:

```text
https://github.com/<your-github-user>/<your-repo>/releases
```

This repository intentionally does not commit `build/` outputs. Build artifacts
should be generated locally or uploaded as release assets.

## Status

This app is experimental. The embedded gateway path currently targets rooted or
root-capable Android devices where the bundled runtime can start reliably. The
external gateway connection mode remains useful on normal devices.

## Root Requirement

Root is required only for the experimental phone-local gateway mode. A normal
non-rooted Android device can still use Openclaw-Android as a companion app for
an external OpenClaw gateway running on a computer, server, or another trusted
machine.

Rooting is device-specific and may wipe the phone, void the warranty, break OTA
updates, break banking/DRM apps, or brick the device if the wrong boot image is
flashed. Do not use unknown one-click root tools. The recommended route is an
official bootloader unlock plus Magisk patched boot image.

General rooting flow:

1. Back up the phone. Bootloader unlock normally erases all user data.
2. Install Android platform-tools on the computer so `adb` and `fastboot` are
   available.
3. Enable Developer options on the phone, then enable `OEM unlocking` and `USB
   debugging`.
4. Reboot to bootloader:

   ```bash
   adb reboot bootloader
   ```

5. Unlock the bootloader with the command required by the device vendor. Common
   commands are:

   ```bash
   fastboot flashing unlock
   fastboot oem unlock
   ```

   The phone will usually wipe itself during this step.

6. Download the exact factory firmware for the phone model and current build
   number. Extract `boot.img` or, on newer Android devices, `init_boot.img`.
7. Install Magisk on the phone and use it to patch that exact image.
8. Pull the patched image back to the computer and flash it from bootloader:

   ```bash
   fastboot flash boot magisk_patched.img
   ```

   On devices that use `init_boot.img`, flash the matching partition instead:

   ```bash
   fastboot flash init_boot magisk_patched.img
   ```

9. Reboot, open Magisk, and confirm root is installed.
10. Install Openclaw-Android, grant the app root access when prompted, then use
    local gateway mode.

Always follow the device-specific guide for the exact model and firmware build.
For A/B slot devices, custom recovery devices, Samsung/Odin devices, and phones
without an unlockable bootloader, the commands and requirements can be different.

## What Changed From Upstream

| Area | Upstream Android app | Openclaw-Android |
| --- | --- | --- |
| Package name | `ai.openclaw.app` | `io.github.openclawcn.app` |
| Launcher name | `OpenClaw Node` | `Openclaw-Android` |
| Primary role | Companion node for an existing OpenClaw gateway | Companion node plus optional local gateway runner |
| Gateway | Usually started on desktop/server, then paired from Android | Can connect to an external gateway or start a local gateway from the app |
| Model provider config | Managed by the gateway config | First launch can collect Base URL, API key, and model name for local gateway config |
| Permission setup | Individual Android prompts | Bulk recommended runtime permission request plus per-permission controls |
| API key handling | Not an Android app concern in the classic external-gateway flow | Stored in app-private config for local gateway mode; no key should be committed |
| Install coexistence | Uses upstream package name | Can be installed next to upstream builds |
| APK output name | `openclaw-<version>-...apk` | `openclaw-android-<version>-...apk` |

## First Launch Configuration

For local gateway mode, users should provide their own model provider details:

- Base URL, for example an Anthropic-compatible endpoint
- API key
- Model name, for example `qwen3.6-plus`

The first-launch fields are intentionally blank. The app does not ship a
default Base URL, API key, or model name as visible placeholder text or bundled
credential material.

The app writes these values into the OpenClaw config under its private app data
directory. No Android storage permission is required for the app to write there.
On a rooted shell, the path is similar to:

```text
/data/user/0/io.github.openclawcn.app/files/openclaw/home/.openclaw/openclaw.json
```

Because this directory is private to the app, a normal `adb shell` user cannot
enter it directly. Use app UI, `run-as` for debuggable builds, or `su` on rooted
devices when manual inspection is needed.

## Security Notes For Open Source Releases

Before publishing:

1. Do not commit real API keys, gateway tokens, `.openclaw` runtime state, or
   generated user config.
2. Do not publish debug keystores as release signing keys.
3. Prefer release builds signed with a maintainer-owned keystore.
4. Keep generated build outputs and Gradle caches out of git.
5. Clearly document that users must bring their own model provider account/key.

The app stores provider settings in app-private storage. That is appropriate for
normal Android app data, but it is not a hardware-backed secret vault for rooted
devices. Users who run rooted phones should treat local config files as readable
by privileged shell users.

## Build

Required build environment:

- JDK 21 or a recent Android Studio bundled JDK
- Android SDK with platform 36 and build-tools installed
- Node.js and npm on `PATH`
- Network access to Maven repositories and the npm registry
- Optional China-friendly npm registry override:

  ```bash
  export OPENCLAW_ANDROID_NPM_REGISTRY=https://registry.npmmirror.com
  ```

From this repository root:

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew :app:assemblePlayDebug
./gradlew :app:assembleThirdPartyDebug
```

On Windows PowerShell:

```powershell
$env:ANDROID_HOME = "Q:\Android"
.\gradlew :app:assemblePlayDebug
```

Debug APKs are written under:

```text
app/build/outputs/apk/play/debug/
app/build/outputs/apk/thirdParty/debug/
```

The APK file prefix is `openclaw-android`.

Do not commit generated outputs such as `app/build/`, `.gradle/`, `.kotlin/`,
`local.properties`, `node_modules/`, crash logs, or generated APKs. They are
ignored by `.gitignore` and should stay outside source control.

Openclaw-Android defaults the phone-local gateway to `127.0.0.1:18790`. The upstream
Android app commonly uses `127.0.0.1:18789`; using a separate port lets both APKs
stay installed during migration without one app connecting to the other's local
gateway token.

When the app starts the local gateway, it writes an app-private PID file at:

```text
files/openclaw/home/.openclaw/android-gateway.pid
```

The Disconnect button now disconnects Android sessions and stops that app-owned
gateway process. On the next Connect, the app restarts the local gateway so new
provider settings and gateway tokens are used. If `127.0.0.1:18790` is already
reachable but does not match the app-owned PID file, the app reports the port as
in use instead of connecting to another app's gateway and producing a token
mismatch.

This avoids the previous failure mode where Openclaw-Android connected to an old
`127.0.0.1:18789` gateway started by the upstream package and then failed with a
gateway token mismatch.

## Permissions

The onboarding permission page has an `Enable recommended` button that requests
normal Android runtime permissions in one batch. Android special access screens,
such as notification listener access, still require a tap through Android
Settings because the platform does not allow apps to grant those permissions
silently. The same screen still exposes individual permission toggles for users
who want a smaller permission set.

## Install And Launch

```bash
./gradlew :app:installPlayDebug
adb shell am start -n io.github.openclawcn.app/ai.openclaw.app.MainActivity
```

The Kotlin source package is intentionally still `ai.openclaw.app`. Android
allows the runtime application id to differ from the Kotlin namespace. Keeping
the source namespace stable reduces merge conflicts with upstream OpenClaw.

## Runtime Layout

Local gateway mode uses the app-private files directory:

```text
files/openclaw/
files/openclaw/home/.openclaw/
files/openclaw/home/.openclaw/openclaw.json
files/openclaw/home/.openclaw/android-gateway.log
```

The exact absolute path depends on the Android user id and package name. For
this fork it is normally:

```text
/data/user/0/io.github.openclawcn.app/files/openclaw/
```

## Relationship To Official OpenClaw

This is a fork for Android-local gateway experimentation and China-friendly
model-provider configuration. It is not an official OpenClaw release unless the
upstream project says so. Keep upstream license notices and make local changes
clear when publishing binaries.

## License

The upstream OpenClaw source is MIT licensed. This Android fork keeps that
license unless a file states otherwise.

The APK bundles Android runtime/native components needed by local gateway mode.
Before publishing release binaries, review and complete the notices under
`THIRD_PARTY_LICENSES/` for bundled native libraries such as Node.js runtime
dependencies, OpenSSL, ICU, sqlite, zlib, c-ares, libc++, and project fonts.
