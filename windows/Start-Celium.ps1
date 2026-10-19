[CmdletBinding()]
param([switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
try {
    $configPath = Join-Path $PSScriptRoot 'celium-windows.json'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Missing celium-windows.json. Copy the example and set your SDK path and AVD name.' }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($config.sdkPath)) { throw 'Set sdkPath in celium-windows.json to your existing Android SDK folder.' }
    if ($config.avdName -notmatch '^[a-zA-Z0-9_.-]+$') { throw 'Set avdName to a valid existing Android Virtual Device name.' }
    $port = [int]$config.emulatorPort
    if ($port -lt 5554 -or $port -gt 5682 -or $port % 2 -ne 0) { throw 'emulatorPort must be an even port between 5554 and 5682.' }
    $sdk = [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($config.sdkPath))
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $emulator = Join-Path $sdk 'emulator\emulator.exe'
    $apk = Join-Path $PSScriptRoot 'celium.apk'
    foreach ($file in @($adb, $emulator, $apk)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file is missing: $file" }
    }
    if ($ValidateOnly) { Write-Host 'Configuration and required files are valid. No emulator or installation was started.'; exit 0 }
    $serial = "emulator-$port"
    $deviceLines = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'Android device service could not start.' }
    $present = @($deviceLines | Where-Object { $_ -match ("^" + [regex]::Escape($serial) + "\s") }).Count -gt 0
    if (-not $present) {
        $avds = & $emulator -list-avds
        if ($LASTEXITCODE -ne 0 -or $config.avdName -notin $avds) { throw 'Configured AVD does not exist. Create it in Android Studio first.' }
        # Visible emulator window is the requested Windows application surface.
        Start-Process -FilePath $emulator -ArgumentList @('-avd', $config.avdName, '-port', $port, '-no-boot-anim') | Out-Null
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $booted = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ($LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) { throw 'Emulator did not finish booting within 180 seconds. Open Android Studio to inspect it, then retry.' }
    $avdResult = @(& $adb -s $serial emu avd name)
    if ($LASTEXITCODE -ne 0 -or $config.avdName -notin $avdResult) { throw 'The configured emulator port belongs to another AVD. Choose a different port; no app was installed.' }
    $package = 'app.celium.android.debug'
    $fingerprint = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $markerPath = Join-Path $PSScriptRoot '.installed-apk.json'
    $installed = & $adb -s $serial shell pm path $package
    $marker = if (Test-Path -LiteralPath $markerPath) { Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json } else { $null }
    if (-not $installed -or $null -eq $marker -or $marker.sha256 -ne $fingerprint -or $marker.serial -ne $serial -or $marker.avdName -ne $config.avdName) {
        & $adb -s $serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK update failed. Existing app data was not removed. Check the installation error above.' }
        @{ sha256 = $fingerprint; serial = $serial; avdName = $config.avdName } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding UTF8
    }
    $activityResult = @(& $adb -s $serial shell cmd package resolve-activity --brief $package)
    $component = $activityResult | Where-Object { $_ -match '^app\.celium\.android\.debug/[A-Za-z0-9_.$]+$' } | Select-Object -Last 1
    if (-not $component) { throw 'The Celium launcher activity could not be resolved.' }
    & $adb -s $serial shell am start -n $component
    if ($LASTEXITCODE -ne 0) { throw 'Celium could not launch.' }
    Write-Host 'Celium is open in the configured Android emulator.'
    exit 0
} catch {
    Write-Host ("Celium: " + $_.Exception.Message)
    exit 1
}

