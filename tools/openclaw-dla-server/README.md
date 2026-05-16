# OpenClaw DLA Native Server

This folder defines the productized native-server contract for the MTK DLA
runtime. The Android app does not load the model in-process. Instead, the
JavaScript DLA bridge keeps the public OpenClaw provider endpoint on
`127.0.0.1:8081` and optionally starts a native server on an internal loopback
port.

## Runtime Contract

The native server must:

- accept the listen port as argv[1], for example `openclaw-qwen-dla-server 18082`;
- read `LLM_DIR` and `CONFIG` from the environment;
- expose `GET /v1/models`;
- expose `POST /v1/chat/completions`;
- accept OpenAI-compatible request bodies with `model`, `messages`,
  `max_tokens`, and `stream`;
- return OpenAI-compatible JSON for non-stream requests;
- return OpenAI-compatible SSE chunks for stream requests when `stream=true`;
- serialize requests internally or return `429` with `qwen_dla_busy`;
- call the equivalent of `llm_reset()` after each completed or aborted request;
- exit cleanly on `SIGTERM`.

The bridge will fall back to the short-lived `main` worker when this server is
missing, not ready, times out, or returns an error before the public response has
started.

## Memory Policy

Keep the server outside the App UID process. The bridge owns process lifetime and
sets:

- `OPENCLAW_DLA_SERVER_PID_FILE`
- `OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS`
- `OPENCLAW_DLA_SERVER_REQUEST_TIMEOUT_MS`

The bridge stops the native server after idle timeout or when the local gateway
stops. Future native code should also self-check available memory before large
allocations and return a clear JSON error instead of letting the process grow
until low-memory kill.

## Build

Build the native server from the JNI/C++ source tree:

```powershell
.\scripts\build-openclaw-qwen-dla-server.ps1
```

The script invokes `ndk-build` for the `openclaw-qwen-dla-server` target and
copies the executable to:

```text
dist/openclaw-qwen-dla-server
```

`deploy-openclaw-dla-daemon.ps1` pushes that binary to:

```text
/data/local/tmp/llm_sdk/openclaw-qwen-dla-server
```

The Magisk packaging script also includes the same executable and points
`OPENCLAW_DLA_SERVER_BIN` at the module-local copy.
