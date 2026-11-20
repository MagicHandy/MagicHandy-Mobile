# Run from the backend checkout. The only files added to the source snapshot are the exporter.
param([string]$Output = 'fixtures')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$destination = [IO.Path]::GetFullPath((Join-Path $root $Output))
if (!$destination.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be in the backend checkout' }
$snapshot = Join-Path $root '.work/desktop-snapshot'
$sha = (Get-Content -LiteralPath (Join-Path $root '.work/desktop-snapshot.txt') -TotalCount 1).Trim()
if ($sha -ne '9a4c36cf41fa4ec6abeb4c72ae6b4790cab0430a') { throw 'Unexpected desktop snapshot' }
$command = Join-Path $snapshot 'cmd/port-fixtures'
New-Item -ItemType Directory -Force -Path $command | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot '*.go') -Destination $command
$env:GOPATH = Join-Path $root '.work/go/path'
$env:GOCACHE = Join-Path $root '.work/go/cache'
$env:GOMODCACHE = Join-Path $root '.work/go/mod'
$env:GOTMPDIR = Join-Path $root '.work/go/tmp'
$env:GOTELEMETRY = 'off'
$env:GOENV = 'off'
$env:GOFLAGS = '-modcacherw'
Push-Location -LiteralPath $snapshot
try {
    go run -tags magichandy_labs ./cmd/port-fixtures -output $destination -source $sha
    if ($LASTEXITCODE -ne 0) { throw "Fixture export failed ($LASTEXITCODE)" }
} finally { Pop-Location }
