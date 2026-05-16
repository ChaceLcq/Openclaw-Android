param(
  [string] $SourceDir = "D:\workspace\dyc_git\Openclaw-DLA-JNI-backup-20260514-225042\ai-server-llm_cmdline_tool-jni",
  [string] $Output = "dist\openclaw-qwen-dla-server"
)

$ErrorActionPreference = "Stop"

function Resolve-NdkBuild {
  $candidates = @()
  foreach ($envName in @("ANDROID_NDK_HOME", "NDK_HOME")) {
    $value = [Environment]::GetEnvironmentVariable($envName)
    if ($value) {
      $candidates += (Join-Path $value "ndk-build.cmd")
      $candidates += (Join-Path $value "ndk-build")
    }
  }

  foreach ($sdkEnv in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
    $sdk = [Environment]::GetEnvironmentVariable($sdkEnv)
    if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk "ndk"))) {
      $candidates += Get-ChildItem -LiteralPath (Join-Path $sdk "ndk") -Directory |
        Sort-Object Name -Descending |
        ForEach-Object {
          Join-Path $_.FullName "ndk-build.cmd"
          Join-Path $_.FullName "ndk-build"
        }
    }
  }

  $pathCommand = Get-Command ndk-build.cmd -ErrorAction SilentlyContinue
  if ($pathCommand) { $candidates += $pathCommand.Source }
  $pathCommand = Get-Command ndk-build -ErrorAction SilentlyContinue
  if ($pathCommand) { $candidates += $pathCommand.Source }

  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  throw "Unable to find ndk-build. Set ANDROID_NDK_HOME or install an NDK under ANDROID_HOME\ndk."
}

if (!(Test-Path -LiteralPath $SourceDir)) {
  throw "JNI/C++ source directory not found: $SourceDir"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if (![System.IO.Path]::IsPathRooted($Output)) {
  $Output = Join-Path $repoRoot $Output
}

$ndkBuild = Resolve-NdkBuild
$androidMk = Join-Path $SourceDir "Android.mk"
$applicationMk = Join-Path $SourceDir "Application.mk"

Write-Host "Building openclaw-qwen-dla-server with $ndkBuild"
& $ndkBuild `
  "-C" "$SourceDir" `
  "NDK_PROJECT_PATH=$SourceDir" `
  "APP_BUILD_SCRIPT=$androidMk" `
  "NDK_APPLICATION_MK=$applicationMk" `
  "openclaw-qwen-dla-server"

if ($LASTEXITCODE -ne 0) {
  throw "ndk-build failed with exit code $LASTEXITCODE"
}

$builtCandidates = @(
  (Join-Path $SourceDir "libs\arm64-v8a\openclaw-qwen-dla-server"),
  (Join-Path $SourceDir "obj\local\arm64-v8a\openclaw-qwen-dla-server")
)
$built = $builtCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$built) {
  throw "Expected build output not found. Checked: $($builtCandidates -join ', ')"
}

$outputDir = Split-Path -Parent $Output
if ($outputDir -and !(Test-Path -LiteralPath $outputDir)) {
  New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}
Copy-Item -LiteralPath $built -Destination $Output -Force
Write-Host "Built native DLA server: $Output"
