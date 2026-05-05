param(
  [string]$SourceMainRoot = "D:/projects/Translate/0409/Agenew_Translate/app/src/main",
  [string]$TtsBuildRoot = "D:/projects/Translate/0409/Agenew_Translate/app/build/intermediates/cxx/Debug/3im3i227/obj/arm64-v8a",
  [string]$BackupRef = "backup/main-with-models"
)

$ErrorActionPreference = "Stop"

function Add-LocalExclude {
  $excludePath = Join-Path (Get-Location) ".git/info/exclude"
  $block = @"

# Local voice runtime assets - keep out of GitHub/LFS pushes
app/src/main/assets/bert-vits2-MNN/
app/src/main/assets/sense/
app/src/main/assets/silero_vad_int8.mnn
app/src/main/jniLibs/arm64-v8a/libMNN.so
app/src/main/jniLibs/arm64-v8a/libcommon.so
app/src/main/jniLibs/arm64-v8a/libdrafter.so
app/src/main/jniLibs/arm64-v8a/libexecutorch.so
app/src/main/jniLibs/arm64-v8a/libfbjni.so
app/src/main/jniLibs/arm64-v8a/libhf-tokenizer.so
app/src/main/jniLibs/arm64-v8a/libllm_jni.so
app/src/main/jniLibs/arm64-v8a/libmnn_tts_SDK.so
app/src/main/jniLibs/arm64-v8a/libmtk_llm.so
app/src/main/jniLibs/arm64-v8a/libmtk_llm_jni.so
app/src/main/jniLibs/arm64-v8a/libneuronusdk_adapter.mtk.so
app/src/main/jniLibs/arm64-v8a/libnnrruntime.so
app/src/main/jniLibs/arm64-v8a/libre2.so
app/src/main/jniLibs/arm64-v8a/libsentencepiece.so
app/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so
app/src/main/jniLibs/arm64-v8a/libtaoavatar.so
app/src/main/jniLibs/arm64-v8a/libtokenizer.so
app/src/main/jniLibs/arm64-v8a/libyaml-cpp.so
"@
  $current = if (Test-Path -LiteralPath $excludePath) { Get-Content -Raw -LiteralPath $excludePath } else { "" }
  if ($current -notmatch "Local voice runtime assets") {
    Add-Content -LiteralPath $excludePath -Value $block
  }
}

function Restore-FromBackupRef {
  git rev-parse --verify $BackupRef *> $null
  if ($LASTEXITCODE -ne 0) {
    return $false
  }

  git restore --source=$BackupRef --worktree -- `
    app/src/main/assets/bert-vits2-MNN `
    app/src/main/assets/sense `
    app/src/main/assets/silero_vad_int8.mnn `
    app/src/main/jniLibs/arm64-v8a
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to restore voice assets from $BackupRef"
  }
  return $true
}

function Copy-DirectoryFresh([string]$Source, [string]$Destination) {
  if (!(Test-Path -LiteralPath $Source)) {
    throw "Missing source directory: $Source"
  }
  if (Test-Path -LiteralPath $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Copy-FileFresh([string]$Source, [string]$Destination) {
  if (!(Test-Path -LiteralPath $Source)) {
    throw "Missing source file: $Source"
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Restore-FromReferenceProject {
  if (!(Test-Path -LiteralPath $SourceMainRoot)) {
    return $false
  }

  Copy-DirectoryFresh `
    -Source (Join-Path $SourceMainRoot "assets/bert-vits2-MNN") `
    -Destination "app/src/main/assets/bert-vits2-MNN"

  New-Item -ItemType Directory -Force -Path "app/src/main/assets/sense" | Out-Null
  Copy-FileFresh `
    -Source (Join-Path $SourceMainRoot "assets/sense/sense_weight_quant.mnn") `
    -Destination "app/src/main/assets/sense/sense_weight_quant.mnn"
  Copy-FileFresh `
    -Source (Join-Path $SourceMainRoot "assets/sense/sense_tokens.txt") `
    -Destination "app/src/main/assets/sense/sense_tokens.txt"
  Copy-FileFresh `
    -Source (Join-Path $SourceMainRoot "assets/silero_vad_int8.mnn") `
    -Destination "app/src/main/assets/silero_vad_int8.mnn"

  Get-ChildItem -LiteralPath (Join-Path $SourceMainRoot "jniLibs/arm64-v8a") -File |
    Where-Object { $_.Name -ne "README.md" } |
    ForEach-Object {
      Copy-FileFresh -Source $_.FullName -Destination (Join-Path "app/src/main/jniLibs/arm64-v8a" $_.Name)
    }

  if (Test-Path -LiteralPath $TtsBuildRoot) {
    Copy-FileFresh `
      -Source (Join-Path $TtsBuildRoot "libmnn_tts_SDK.so") `
      -Destination "app/src/main/jniLibs/arm64-v8a/libmnn_tts_SDK.so"
    Copy-FileFresh `
      -Source (Join-Path $TtsBuildRoot "libtaoavatar.so") `
      -Destination "app/src/main/jniLibs/arm64-v8a/libtaoavatar.so"
  }

  return $true
}

Add-LocalExclude

if (!(Restore-FromBackupRef)) {
  if (!(Restore-FromReferenceProject)) {
    throw "No local voice asset source found. Provide -SourceMainRoot or keep $BackupRef locally."
  }
}

git update-index --skip-worktree app/src/main/jniLibs/arm64-v8a/libc++_shared.so
Write-Host "Voice runtime assets are ready locally and ignored by Git."
