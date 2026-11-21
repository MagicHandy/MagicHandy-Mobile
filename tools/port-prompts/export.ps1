param([string]$Output = 'core/src/main/resources/chat')
$ErrorActionPreference = 'Stop'
$checkout = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$work = Join-Path $checkout '.work'
$snapshot = Join-Path $work 'desktop-snapshot-1b80d72e'
if ((Get-Content (Join-Path $work 'desktop-snapshot-1b80d72e.txt') -TotalCount 1).Trim() -ne '1b80d72ee4b90a5ab80a9bb3fe373b1640388f80') { throw 'Unexpected snapshot' }
$command = Join-Path $snapshot 'cmd/mobile-prompts'
$resources = [IO.Path]::GetFullPath((Join-Path $checkout $Output))
if (!$resources.StartsWith($checkout + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be in the backend checkout' }
New-Item -ItemType Directory -Force -Path $command,$resources | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot '*.go') -Destination $command
$env:GOPATH = Join-Path $work 'go/path'
$env:GOCACHE = Join-Path $work 'go/cache'
$env:GOMODCACHE = Join-Path $work 'go/mod'
$env:GOTMPDIR = Join-Path $work 'go/tmp'
$env:TEMP = $env:GOTMPDIR
$env:TMP = $env:TEMP
$env:GOTELEMETRY = 'off'
$env:GOENV = 'off'
Push-Location -LiteralPath $snapshot
try {
    go run -tags magichandy_labs ./cmd/mobile-prompts -output (Join-Path $resources 'contracts.json') -persona-output (Join-Path $resources 'personas.json') -voice-deltas (Join-Path $checkout 'fixtures/chat/persona-voice-deltas.json')
    if ($LASTEXITCODE -ne 0) { throw 'Prompt generation failed' }
} finally { Pop-Location }
