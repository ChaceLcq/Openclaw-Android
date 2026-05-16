# OpenClaw DLA Daemon

Root-started bridge for MTK APUSYS/NPU devices. The daemon exposes an OpenAI-compatible local endpoint at `127.0.0.1:8081`. It prefers the persistent native server `/data/local/tmp/llm_sdk/openclaw-qwen-dla-server` for lower first-token latency after warmup, and falls back to `/data/local/tmp/llm_sdk/main` as a short-lived worker when the server is unavailable. The default persistent-server idle timeout is 10 minutes, so short multi-turn sessions reuse the loaded model.

## ADB validation

From the repository root on Windows:

```powershell
.\scripts\build-openclaw-qwen-dla-server.ps1
.\scripts\deploy-openclaw-dla-daemon.ps1 -Start -LaunchApp
```

Manual device commands:

```bash
adb shell su 0 sh /data/local/tmp/openclaw-dla-daemon/start.sh
adb shell su 0 sh /data/local/tmp/openclaw-dla-daemon/status.sh
adb shell su 0 sh /data/local/tmp/openclaw-dla-daemon/stop.sh
```

## Magisk module

Package a module zip:

```powershell
.\scripts\package-openclaw-dla-magisk.ps1
```

The generated zip starts the same daemon from Magisk `service.sh` after boot. It does not modify the system or vendor partition.

## Requirements

- Root is required for APUSYS/NPU access.
- `/data/local/tmp/llm_sdk/main`, `/data/local/tmp/llm_sdk/openclaw-qwen-dla-server`, and `config_np8-qwen3-1.7b.yaml` must exist.
- OpenClaw must be installed, or `libopenclaw_node.so` must be deployed with the daemon.
