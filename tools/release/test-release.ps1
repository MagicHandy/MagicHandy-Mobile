[CmdletBinding()]
param([switch] $Offline)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$properties = Join-Path $root 'keystore.properties'
if (Test-Path -LiteralPath $properties) { throw 'The pipeline test requires a checkout without owner signing properties.' }
$work = (Resolve-Path -LiteralPath (Join-Path $root '.work')).Path
$id = 'signing-test-' + [Guid]::NewGuid().ToString('N')
$testDir = [IO.Path]::GetFullPath((Join-Path $work $id))
if (![IO.Path]::GetFullPath($testDir).StartsWith($work + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected test directory.' }
[IO.Directory]::CreateDirectory($testDir) | Out-Null
$key = Join-Path $testDir 'disposable.p12'
$variable = 'MAGICHANDY_TEST_' + [Guid]::NewGuid().ToString('N')
$owned = $false
Push-Location -LiteralPath $root
try {
    [Environment]::SetEnvironmentVariable($variable, [Guid]::NewGuid().ToString('N'), 'Process')
    $tool = Join-Path $env:JAVA_HOME 'bin/keytool'
    & $tool -genkeypair -alias pipeline-test -keyalg RSA -keysize 2048 -validity 1 -storetype PKCS12 -keystore $key -storepass:env $variable -keypass:env $variable -dname 'CN=Disposable pipeline test' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Throwaway key generation failed.' }
    $stream = [IO.File]::Open($properties, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $owned = $true
    try {
        $lines = @("storeFile=.work/$id/disposable.p12", 'keyAlias=pipeline-test', "storePasswordEnv=$variable", "keyPasswordEnv=$variable")
        $bytes = [Text.Encoding]::ASCII.GetBytes(($lines -join [char]10) + [char]10)
        $stream.Write($bytes,0,$bytes.Length)
    } finally { $stream.Dispose() }
    & (Join-Path $PSScriptRoot 'build-release.ps1') -OutputDirectory (".work/$id/verified") -Offline:$Offline
    $published = @(Get-ChildItem -LiteralPath (Join-Path $testDir 'verified') -Directory)
    if ($published.Count -ne 1) { throw 'Expected one version directory.' }
    $name = 'MagicHandy-' + $published[0].Name + '.apk'
    $apk = Join-Path $published[0].FullName $name
    $checksum = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([IO.File]::ReadAllText((Join-Path $published[0].FullName 'SHA256SUMS')) -cne ($checksum + '  ' + $name + [char]10)) {
        throw 'SHA256SUMS failed an independent readback.'
    }
} finally {
    if ($owned) { [IO.File]::Delete($properties) }
    [Environment]::SetEnvironmentVariable($variable, $null, 'Process')
    [IO.File]::Delete($key)
    $resolved = (Resolve-Path -LiteralPath $testDir).Path
    if ($resolved -ne $testDir -or [IO.Path]::GetDirectoryName($resolved) -ne $work -or [IO.Path]::GetFileName($resolved) -ne $id) {
        throw 'Unexpected disposable directory at cleanup.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    Pop-Location
}
# Leave the normal release output unsigned after exercising owner signing.
if (Test-Path -LiteralPath $properties) { throw 'Signing configuration appeared during cleanup.' }
$wrapper = if ([IO.Path]::DirectorySeparatorChar -eq '\') { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
$options = @('--console=plain','--no-configuration-cache','-p',$root,':app:assembleRelease')
if ($Offline) { $options += '--offline' }
& $wrapper @options
if ($LASTEXITCODE -ne 0) { throw 'Unsigned rebuild failed.' }
$metadata = Get-Content -LiteralPath (Join-Path $root 'app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
if (@($metadata.elements).Count -ne 1 -or $metadata.elements[0].outputFile -notlike '*-unsigned.apk') { throw 'Unsigned output was not restored.' }
Write-Output 'Disposable key removed. Signature and checksum verified. Unsigned output restored.'
