param(
  [Parameter(Mandatory = $true)]
  [string]$AsrRoot,

  [Parameter(Mandatory = $true)]
  [string]$TtsRoot,

  [Parameter(Mandatory = $true)]
  [string]$Output
)

$ErrorActionPreference = "Stop"

$asrRootPath = (Resolve-Path -LiteralPath $AsrRoot).Path
$ttsRootPath = (Resolve-Path -LiteralPath $TtsRoot).Path
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("openclaw-voice-models-" + [System.Guid]::NewGuid().ToString("N"))
$packageRoot = Join-Path $tempRoot "OpenClawVoiceModels"

function Copy-RequiredFile {
  param(
    [string]$Source,
    [string]$Destination
  )
  if (!(Test-Path -LiteralPath $Source -PathType Leaf)) {
    throw "Missing required model file: $Source"
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

try {
  New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null

  $manifest = @{
    packageType = "openclaw-voice-model"
    version = 1
    asr = @{ engine = "sensevoice-mnn" }
    tts = @{ engine = "bert-vits2-mnn" }
  } | ConvertTo-Json -Depth 4
  Set-Content -LiteralPath (Join-Path $packageRoot "openclaw-voice-model.json") -Value $manifest -Encoding UTF8

  Copy-RequiredFile `
    -Source (Join-Path $asrRootPath "sense/sense_weight_quant.mnn") `
    -Destination (Join-Path $packageRoot "models/asr/sense/sense_weight_quant.mnn")
  Copy-RequiredFile `
    -Source (Join-Path $asrRootPath "sense/sense_tokens.txt") `
    -Destination (Join-Path $packageRoot "models/asr/sense/sense_tokens.txt")
  Copy-RequiredFile `
    -Source (Join-Path $asrRootPath "vad/silero_vad_int8.mnn") `
    -Destination (Join-Path $packageRoot "models/asr/vad/silero_vad_int8.mnn")

  $ttsTarget = Join-Path $packageRoot "models/tts/bert-vits2-MNN"
  if (!(Test-Path -LiteralPath (Join-Path $ttsRootPath "config.json") -PathType Leaf)) {
    throw "Missing required TTS config.json: $ttsRootPath"
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ttsTarget) | Out-Null
  Copy-Item -LiteralPath $ttsRootPath -Destination $ttsTarget -Recurse -Force

  if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
  Compress-Archive -LiteralPath $packageRoot -DestinationPath $outputPath -CompressionLevel Optimal
  Write-Host "Created OpenClaw voice model package: $outputPath"
} finally {
  if (Test-Path -LiteralPath $tempRoot) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
  }
}
