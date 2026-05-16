param(
  [string] $OutputDir = "dist"
)

$ErrorActionPreference = "Stop"

$repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$moduleSource = Join-Path $repo "tools/openclaw-dla-magisk"
$daemonSource = Join-Path $repo "tools/openclaw-dla-daemon"
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
$output = Join-Path $repo $OutputDir
$staging = Join-Path $output "openclaw-dla-daemon-magisk"
$zip = Join-Path $output "openclaw-dla-daemon-magisk.zip"

foreach ($path in @(
    (Join-Path $moduleSource "module.prop"),
    (Join-Path $moduleSource "service.sh"),
    (Join-Path $daemonSource "start.sh"),
    (Join-Path $daemonSource "stop.sh"),
    (Join-Path $daemonSource "status.sh"),
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

New-Item -ItemType Directory -Force -Path $output | Out-Null
if (Test-Path $staging) {
  Remove-Item -LiteralPath $staging -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $staging | Out-Null

Copy-Item (Join-Path $moduleSource "module.prop") (Join-Path $staging "module.prop")
Copy-Item (Join-Path $moduleSource "service.sh") (Join-Path $staging "service.sh")
Copy-Item (Join-Path $daemonSource "start.sh") (Join-Path $staging "start.sh")
Copy-Item (Join-Path $daemonSource "stop.sh") (Join-Path $staging "stop.sh")
Copy-Item (Join-Path $daemonSource "status.sh") (Join-Path $staging "status.sh")
Copy-Item $bridge (Join-Path $staging "android-dla-bridge.mjs")
Copy-Item $nodeBin (Join-Path $staging "libopenclaw_node.so")
Copy-Item $nativeServerBin (Join-Path $staging "openclaw-qwen-dla-server")
foreach ($lib in $nodeLibs) {
  Copy-Item (Join-Path $nodeLibDir $lib) (Join-Path $staging $lib)
}

if (Test-Path $zip) {
  Remove-Item -LiteralPath $zip -Force
}
Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $zip -Force

Write-Host "Wrote $zip"
