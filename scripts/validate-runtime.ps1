param(
    [string]$AdbPath = "C:\Users\Leonardo\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$PackageName = "com.meta.wearable.dat.externalsampleapps.cameraaccess",
    [int]$BackendPort = 8080,
    [int]$DevicePort = 8081,
    [string]$ReportPath = "$PSScriptRoot\..\build\reports\runtime-validation.txt"
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[string]]::new()

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $status = if ($Passed) { "PASS" } else { "FAIL" }
    $script:results.Add("[$status] $Name - $Detail")
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $AdbPath @Arguments 2>&1
    return ($output | Out-String).Trim()
}

try {
    $deviceState = Invoke-Adb get-state
    Add-Check "phone" ($deviceState -eq "device") $deviceState

    $processId = Invoke-Adb shell pidof $PackageName
    Add-Check "app-process" (-not [string]::IsNullOrWhiteSpace($processId)) $(if ($processId) { "pid=$processId" } else { "not running" })

    $packageInfo = Invoke-Adb shell dumpsys package $PackageName
    $validatedApk = $packageInfo -match "versionName=.*-faro-validated"
    Add-Check "installed-apk" $validatedApk $(if ($validatedApk) { "validated CameraAccess build" } else { "another project copy is installed" })

    $listener = Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    Add-Check "realtime-backend" ($null -ne $listener) $(if ($listener) { "listening on $BackendPort, pid=$($listener.OwningProcess)" } else { "no listener on $BackendPort" })

    $reverse = Invoke-Adb reverse --list
    $reverseOk = $reverse -match "tcp:$DevicePort\s+tcp:$BackendPort"
    if (-not $reverseOk) {
        [void](Invoke-Adb reverse "tcp:$DevicePort" "tcp:$BackendPort")
        $reverse = Invoke-Adb reverse --list
        $reverseOk = $reverse -match "tcp:$DevicePort\s+tcp:$BackendPort"
    }
    Add-Check "adb-reverse" $reverseOk $(if ($reverseOk) { "phone:$DevicePort -> pc:$BackendPort" } else { "mapping could not be created" })

    [void](Invoke-Adb shell input keyevent KEYCODE_WAKEUP)
    [void](Invoke-Adb shell wm dismiss-keyguard)
    [void](Invoke-Adb shell am start -n "$PackageName/.MainActivity")
    Start-Sleep -Seconds 2
    [void](Invoke-Adb shell uiautomator dump /sdcard/cameraaccess-validation.xml)
    $ui = Invoke-Adb shell cat /sdcard/cameraaccess-validation.xml
    $registeredUi = $ui -match "Falar con Faro|Deixar de falar con Faro|Start session|Session:"
    Add-Check "dat-registration-ui" $registeredUi $(if ($registeredUi) { "registered screen visible" } else { "registration screen or unavailable UI" })

    $glassesActive = $ui -notmatch "Waiting for an active device|Put on your glasses"
    Add-Check "glasses-active" $glassesActive $(if ($glassesActive) { "active device available" } else { "put on/unfold the glasses" })

    $sessionState = if ($ui -match "Session: started") { "started" } elseif ($ui -match "Session: ([a-z]+)") { $Matches[1] } else { "unknown" }
    $streamState = if ($ui -match "Stream: streaming") { "streaming" } elseif ($ui -match "Stream: ([a-z]+)") { $Matches[1] } else { "unknown" }
    Add-Check "dat-live-state" ($sessionState -eq "started" -and $streamState -eq "streaming") "session=$sessionState, stream=$streamState"

    $chatState =
        if ($ui -match "Deixar de falar con Faro") { "listening" }
        elseif ($ui -match "Conectando con Faro") { "connecting" }
        elseif ($ui -match "Volver conectar con Faro") { "error" }
        elseif ($ui -match "Falar con Faro") { "stopped" }
        else { "unknown" }
    Add-Check "faro-ui" ($chatState -in @("listening", "stopped")) $chatState

    $audio = Invoke-Adb shell dumpsys audio
    $hfpAvailable = $audio -match "type:bt_sco.*Meta Glasses|SCO (sink|source).*now available"
    Add-Check "glasses-hfp" $hfpAvailable $(if ($hfpAvailable) { "Meta Glasses SCO available" } else { "Meta Glasses SCO unavailable" })

    if ($processId) {
        $logs = Invoke-Adb logcat -d --pid=$processId -t 3000
        $datUnknownType = $logs -match "UNKNOWN_TYPE|START_ERROR_DEVICE_UNAVAILABLE"
        Add-Check "dat-session" (-not $datUnknownType) $(if ($datUnknownType) { "0xd001 UNKNOWN_TYPE / device unavailable" } else { "no recent terminal DAT error" })

        $voiceError = [regex]::Matches($logs, "(Realtime connection failed|Realtime connection closed|Connect the Meta glasses|Realtime audio queue is full)") | Select-Object -Last 1
        $voiceConnected = $logs -match "Realtime voice connected"
        Add-Check "faro-runtime" ($null -eq $voiceError) $(if ($voiceError) { $voiceError.Value } elseif ($voiceConnected) { "voice connected" } else { "no recent terminal voice error" })
    }
} catch {
    Add-Check "validator" $false $_.Exception.Message
}

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss K"
$report = @("CameraAccess runtime validation - $timestamp", "") + $results
$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | Set-Content -Path $ReportPath -Encoding utf8
$report

if ($results -match "^\[FAIL\]") { exit 1 }
