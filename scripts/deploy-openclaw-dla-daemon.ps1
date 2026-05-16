param(
  [string] $Serial = "",
  [string] $RemoteDir = "/data/local/tmp/openclaw-dla-daemon",
  [switch] $Start,
  [switch] $LaunchApp
)

$ErrorActionPreference = "Stop"

$repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$daemonDir = Join-Path $repo "tools/openclaw-dla-daemon"
$bridge = Join-Path $repo "app/src/main/assets/openclaw/dla/android-dla-bridge.mjs"
$nodeBin = Join-Path $repo "app/src/main/jniLibs/arm64-v8a/libopenclaw_node.so"
$nativeServerBin = Join-Path $repo "dist/openclaw-qwen-dla-server"
$nodeLibDir = Join-Path $repo "app/src/main/assets/openclaw/native-libs/arm64-v8a"
$nodeLibs = @(
  "libc++_shared.so",
  "libz.so.1",
  "libcares.so",
  "libsqlite3.so",
  "libcrypto.so.3",
  "libssl.so.3",
  "libicudata.so.78",
  "libicui18n.so.78",
  "libicuuc.so.78"
)
$adbArgs = @()
if ($Serial) {
  $adbArgs += @("-s", $Serial)
}

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Args)
  & adb @adbArgs @Args
  if ($LASTEXITCODE -ne 0) {
    throw "adb failed: $($Args -join ' ')"
  }
}

foreach ($path in @(
    (Join-Path $daemonDir "start.sh"),
    (Join-Path $daemonDir "stop.sh"),
    (Join-Path $daemonDir "status.sh"),
    $bridge,
    $nodeBin,
    $nativeServerBin
  )) {
  if (!(Test-Path $path)) {
    throw "Missing required file: $path"
  }
}
foreach ($lib in $nodeLibs) {
  $path = Join-Path $nodeLibDir $lib
  if (!(Test-Path $path)) {
    throw "Missing required Node native library: $path"
  }
}

Invoke-Adb shell "su 0 mkdir -p '$RemoteDir'"
Invoke-Adb shell "su 0 mkdir -p '/data/local/tmp/llm_sdk'"
Invoke-Adb shell "su 0 chmod 777 '$RemoteDir'"
Invoke-Adb push (Join-Path $daemonDir "start.sh") "$RemoteDir/start.sh"
Invoke-Adb push (Join-Path $daemonDir "stop.sh") "$RemoteDir/stop.sh"
Invoke-Adb push (Join-Path $daemonDir "status.sh") "$RemoteDir/status.sh"
Invoke-Adb push $bridge "$RemoteDir/android-dla-bridge.mjs"
Invoke-Adb push $nodeBin "$RemoteDir/libopenclaw_node.so"
Invoke-Adb push $nativeServerBin "/data/local/tmp/llm_sdk/openclaw-qwen-dla-server"
foreach ($lib in $nodeLibs) {
  Invoke-Adb push (Join-Path $nodeLibDir $lib) "$RemoteDir/$lib"
}
Invoke-Adb shell "su 0 chmod 755 '$RemoteDir'"
Invoke-Adb shell "su 0 chmod 755 '$RemoteDir/start.sh' '$RemoteDir/stop.sh' '$RemoteDir/status.sh' '$RemoteDir/libopenclaw_node.so'"
Invoke-Adb shell "su 0 chmod 755 '/data/local/tmp/llm_sdk/openclaw-qwen-dla-server'"
Invoke-Adb shell "su 0 chmod 644 '$RemoteDir/android-dla-bridge.mjs'"

if ($Start) {
  Invoke-Adb shell am force-stop io.github.openclawcn.app
  Invoke-Adb shell "su 0 sh '$RemoteDir/stop.sh'"
  Invoke-Adb shell "su 0 sh '$RemoteDir/start.sh'"
  Invoke-Adb forward tcp:18081 tcp:8081
}

if ($LaunchApp) {
  Invoke-Adb shell monkey -p io.github.openclawcn.app 1
}

Write-Host "OpenClaw DLA daemon deployed to $RemoteDir"
if ($Start) {
  Write-Host "Forwarded host http://127.0.0.1:18081 to device port 8081"
}
