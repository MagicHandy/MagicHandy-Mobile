[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')]
    [string] $Tag
)
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot "../../docs/releases/$Tag.md"
$lines = [IO.File]::ReadAllLines($path)
$content = @($lines | Where-Object { $_.Trim().Length -gt 0 })
if ($lines.Count -eq 0 -or $lines[0] -cne "# MagicHandy Mobile $Tag" -or $content[1] -cne '## Changelog') {
    throw 'Use the tagged release title followed by a single Changelog heading.'
}
$bullets = 0
$inBullet = $false
foreach ($line in ($lines | Select-Object -Skip ([Array]::IndexOf($lines, '## Changelog') + 1))) {
    if ([string]::IsNullOrWhiteSpace($line)) { $inBullet = $false; continue }
    if ($line -cmatch '^- \S') { $bullets++; $inBullet = $true; continue }
    if ($inBullet -and $line -cmatch '^  \S') { continue }
    throw 'Use top-level bullets and indent any continuation by two spaces.'
}
$words = [regex]::Matches(($lines -join ' '), '\S+').Count
if ($bullets -lt 1 -or $bullets -gt 12 -or $lines.Count -gt 60 -or $words -gt 350) {
    throw 'Use 1 to 12 bullets, up to 60 lines and 350 words.'
}
Write-Output "$Tag release notes verified ($bullets bullets, $words words)."
