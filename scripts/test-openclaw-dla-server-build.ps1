param(
  [string] $SourceDir = "D:\workspace\dyc_git\Openclaw-DLA-JNI-backup-20260514-225042\ai-server-llm_cmdline_tool-jni"
)

$ErrorActionPreference = "Stop"

function Assert-Contains {
  param(
    [string] $Path,
    [string] $Needle
  )
  if (!(Test-Path -LiteralPath $Path)) {
    throw "Missing file: $Path"
  }
  $content = Get-Content -LiteralPath $Path -Raw
  if (!$content.Contains($Needle)) {
    throw "Expected '$Needle' in $Path"
  }
}

$serverSource = Join-Path $SourceDir "main\openclaw-qwen-dla-server.cpp"
$androidMk = Join-Path $SourceDir "main\Android.mk"
$buildScript = Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\build-openclaw-qwen-dla-server.ps1"
$deployScript = Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\deploy-openclaw-dla-daemon.ps1"
$packageScript = Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\package-openclaw-dla-magisk.ps1"
$magiskService = Join-Path (Split-Path -Parent $PSScriptRoot) "tools\openclaw-dla-magisk\service.sh"

if (!(Test-Path -LiteralPath $serverSource)) {
  throw "Missing native server source: $serverSource"
}

Assert-Contains -Path $androidMk -Needle "openclaw-qwen-dla-server"
Assert-Contains -Path $buildScript -Needle "ndk-build"
Assert-Contains -Path $serverSource -Needle "text/event-stream"
Assert-Contains -Path $serverSource -Needle "mtk_llm_inference_once"
Assert-Contains -Path $serverSource -Needle "sendSseDelta"
Assert-Contains -Path $serverSource -Needle "Qwen3NoInputNoThink"
Assert-Contains -Path $deployScript -Needle "openclaw-qwen-dla-server"
Assert-Contains -Path $packageScript -Needle "openclaw-qwen-dla-server"
Assert-Contains -Path $magiskService -Needle "OPENCLAW_DLA_SERVER_BIN"

Write-Host "openclaw-qwen-dla-server build integration checks passed"
