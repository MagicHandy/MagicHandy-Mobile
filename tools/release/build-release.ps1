[CmdletBinding()]
param(
    [string] $OutputDirectory = 'build/releases',
    [switch] $Offline
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
function Within-Checkout([string] $path) {
    $absolute = [IO.Path]::GetFullPath($path)
    if (!$absolute.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Choose an output directory inside this checkout.'
    }
    return $absolute
}
if (!(Test-Path -LiteralPath (Join-Path $root 'keystore.properties') -PathType Leaf)) {
    throw 'Release signing requires keystore.properties. See docs/release.md.'
}
$destination = Within-Checkout (Join-Path $root $OutputDirectory)
$wrapper = if ([IO.Path]::DirectorySeparatorChar -eq '\') { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
$options = @('--console=plain', '--no-configuration-cache', '--no-build-cache')
if ($Offline) { $options += '--offline' }
function Invoke-ReleaseGradle([string[]] $tasks) {
    & $wrapper @options @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Release Gradle task failed.' }
}
Push-Location -LiteralPath $root
try {
    Invoke-ReleaseGradle @(':app:assembleRelease', ':app:checkReleaseContents')
    $apkDir = Join-Path $root 'app/build/outputs/apk/release'
    $metadata = Get-Content -LiteralPath (Join-Path $apkDir 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.variantName -ne 'release' -or @($metadata.elements).Count -ne 1) {
        throw 'Expected one release APK.'
    }
    $item = $metadata.elements[0]
    if ($item.versionName -cnotmatch '^[0-9]+\.[0-9]+\.[0-9]+$' -or [IO.Path]::GetFileName($item.outputFile) -cne $item.outputFile) {
        throw 'Invalid release output metadata.'
    }
    $name = 'MagicHandy-' + $item.versionName + '.apk'
    $final = Within-Checkout (Join-Path $destination $item.versionName)
    [IO.Directory]::CreateDirectory($destination) | Out-Null
    $stage = Within-Checkout (Join-Path $destination ('.staging-' + [Guid]::NewGuid().ToString('N')))
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    $artifact = Join-Path $stage $name
    $sums = Join-Path $stage 'SHA256SUMS'
    try {
        [IO.File]::Copy((Join-Path $apkDir $item.outputFile), $artifact)
        Invoke-ReleaseGradle @(':app:verifyReleaseSignature', ("-PreleaseApkToVerify=" + $artifact))
        $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText($sums, $digest + '  ' + $name + [char]10, [Text.Encoding]::ASCII)
        if ([IO.Directory]::Exists($final)) {
            $prior = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $final $name)).Hash
            if ($prior -ine $digest -or [IO.File]::ReadAllText((Join-Path $final 'SHA256SUMS')) -cne [IO.File]::ReadAllText($sums)) {
                throw 'Different artifacts already exist for this version. Choose a fresh output directory.'
            }
        } else {
            [IO.Directory]::Move($stage, $final)
        }
        Write-Output ('Verified release artifacts in ' + $final)
    } finally {
        if ([IO.Directory]::Exists($stage)) {
            [IO.File]::Delete($artifact)
            [IO.File]::Delete($sums)
            [IO.Directory]::Delete($stage)
        }
    }
} finally { Pop-Location }
