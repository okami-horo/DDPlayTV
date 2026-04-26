[CmdletBinding()]
param(
    [string]$EnvFile = "",
    [string]$HttpBaseUri = "",
    [string]$HttpHost = "",
    [string]$DeviceSerial,
    [string]$Token,
    [int]$LocalPort = 17010,
    [int]$RemotePort = 17010,
    [string]$PackageName = "com.okamihoro.ddplaytv",
    [string]$OutputDir = "",
    [switch]$KeepForward,
    [switch]$SkipDirectFallback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:InitialBoundParameters = @{} + $PSBoundParameters
$script:LastAdbExitCode = 0

function Resolve-EnvFile {
    param([string]$RequestedPath)

    if ($RequestedPath) {
        $resolved = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($RequestedPath)
        if (-not (Test-Path -LiteralPath $resolved)) {
            throw "Env file was not found: $resolved"
        }
        return $resolved
    }

    $candidates = @(
        (Join-Path $PSScriptRoot "..\.env"),
        (Join-Path (Get-Location) ".env")
    )

    foreach ($candidate in $candidates) {
        $resolved = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($candidate)
        if (Test-Path -LiteralPath $resolved) {
            return $resolved
        }
    }

    return ""
}

function Import-DotEnv {
    param([string]$Path)

    $values = @{}
    if (-not $Path) {
        return $values
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -le 0) {
            continue
        }

        $key = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($key) {
            $values[$key] = $value
        }
    }

    return $values
}

function Get-DotEnvValue {
    param(
        [hashtable]$Values,
        [string]$Name
    )

    if ($Values.ContainsKey($Name)) {
        return $Values[$Name]
    }
    return $null
}

function Set-DotEnvString {
    param(
        [hashtable]$Values,
        [string]$EnvName,
        [string]$ParameterName,
        [ref]$Target
    )

    if ($script:InitialBoundParameters.ContainsKey($ParameterName)) {
        return
    }
    $value = Get-DotEnvValue -Values $Values -Name $EnvName
    if ($null -ne $value -and $value.Trim().Length -gt 0) {
        $Target.Value = $value
    }
}

function Set-DotEnvInt {
    param(
        [hashtable]$Values,
        [string]$EnvName,
        [string]$ParameterName,
        [ref]$Target
    )

    if ($script:InitialBoundParameters.ContainsKey($ParameterName)) {
        return
    }
    $raw = Get-DotEnvValue -Values $Values -Name $EnvName
    if ($null -eq $raw -or $raw.Trim().Length -eq 0) {
        return
    }

    $parsed = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$parsed)) {
        throw "Invalid integer in .env: $EnvName=$raw"
    }
    $Target.Value = $parsed
}

function Set-DotEnvSwitch {
    param(
        [hashtable]$Values,
        [string]$EnvName,
        [string]$ParameterName,
        [ref]$Target
    )

    if ($script:InitialBoundParameters.ContainsKey($ParameterName)) {
        return
    }
    $raw = Get-DotEnvValue -Values $Values -Name $EnvName
    if ($null -eq $raw -or $raw.Trim().Length -eq 0) {
        return
    }

    $normalized = $raw.Trim().ToLowerInvariant()
    $Target.Value = $normalized -in @("1", "true", "yes", "on")
}

function Resolve-AdbPath {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) | Where-Object { $_ -and $_.Trim().Length -gt 0 }
    foreach ($sdkRoot in $sdkRoots) {
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "adb was not found in PATH, ANDROID_SDK_ROOT, or ANDROID_HOME."
}

function Resolve-HttpBaseUri {
    param(
        [string]$ConfiguredBaseUri,
        [string]$ConfiguredHost,
        [int]$Port
    )

    if ($ConfiguredBaseUri -and $ConfiguredBaseUri.Trim().Length -gt 0) {
        return $ConfiguredBaseUri.Trim().TrimEnd("/")
    }

    if ($ConfiguredHost -and $ConfiguredHost.Trim().Length -gt 0) {
        $hostValue = $ConfiguredHost.Trim().TrimEnd("/")
        if ($hostValue -match '^https?://') {
            return $hostValue
        }
        if ($hostValue -match '^[^\[][^/:]+:\d+$') {
            return "http://$hostValue"
        }
        return "http://$hostValue`:$Port"
    }

    return ""
}

function Join-CommandLine {
    param([string[]]$Arguments)

    return ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join " "
}

function Invoke-AdbText {
    param(
        [string[]]$Arguments,
        [switch]$IgnoreExitCode
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:AdbPath
    $psi.Arguments = Join-CommandLine $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $exitCode = $process.ExitCode
    $script:LastAdbExitCode = $exitCode
    $text = (($stdout, $stderr) -join "`n").Trim()
    if ($exitCode -ne 0 -and -not $IgnoreExitCode) {
        throw "adb $($Arguments -join ' ') failed with exit code $exitCode. $text"
    }
    return $text
}

function Save-AdbBinaryOutput {
    param(
        [string[]]$Arguments,
        [string]$Path
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:AdbPath
    $psi.Arguments = Join-CommandLine $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        $process.StandardOutput.BaseStream.CopyTo($stream)
    } finally {
        $stream.Dispose()
    }

    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        throw "adb $($Arguments -join ' ') failed with exit code $($process.ExitCode)."
    }

    $item = Get-Item -LiteralPath $Path
    if ($item.Length -le 0) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        throw "adb binary capture produced an empty file."
    }
}

function Select-AdbDevice {
    param([string]$RequestedSerial)

    $raw = Invoke-AdbText -Arguments @("devices", "-l")
    $devices = @()
    foreach ($line in ($raw -split "`r?`n" | Select-Object -Skip 1)) {
        $trimmed = $line.Trim()
        if (-not $trimmed) {
            continue
        }
        if ($trimmed -match '^(\S+)\s+(\S+)\s*(.*)$') {
            $devices += [pscustomobject]@{
                Serial = $Matches[1]
                State = $Matches[2]
                Detail = $Matches[3]
            }
        }
    }

    if ($RequestedSerial) {
        $match = $devices | Where-Object { $_.Serial -eq $RequestedSerial } | Select-Object -First 1
        if (-not $match) {
            throw "Requested adb device '$RequestedSerial' was not found. Raw devices output: $raw"
        }
        if ($match.State -ne "device") {
            throw "Requested adb device '$RequestedSerial' is '$($match.State)', not 'device'."
        }
        return $match.Serial
    }

    $usable = @($devices | Where-Object { $_.State -eq "device" })
    if ($usable.Count -eq 0) {
        throw "No adb device in 'device' state. Raw devices output: $raw"
    }
    if ($usable.Count -gt 1) {
        $serials = ($usable | ForEach-Object { $_.Serial }) -join ", "
        throw "Multiple adb devices are available. Re-run with -DeviceSerial. Devices: $serials"
    }

    return $usable[0].Serial
}

function Save-HttpFile {
    param(
        [string]$Uri,
        [hashtable]$Headers,
        [string]$Path,
        [switch]$Optional
    )

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Uri -Headers $Headers -OutFile $Path -TimeoutSec 30 -UseBasicParsing | Out-Null
            Write-Host "Saved $Path"
            return $true
        } catch {
            $statusCode = 0
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            if ($statusCode -eq 429 -and $attempt -lt 3) {
                Start-Sleep -Milliseconds 1200
                continue
            }
            if ($Optional) {
                Write-Warning "Optional HTTP request failed: $Uri. $($_.Exception.Message)"
                return $false
            }
            throw
        }
    }

    return $false
}

function Save-HttpText {
    param(
        [string]$Uri,
        [hashtable]$Headers,
        [string]$Path
    )

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -Headers $Headers -TimeoutSec 30 -UseBasicParsing
            $response.Content | Set-Content -LiteralPath $Path -Encoding UTF8
            Write-Host "Saved $Path"
            return
        } catch {
            $statusCode = 0
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            if ($statusCode -eq 429 -and $attempt -lt 3) {
                Start-Sleep -Milliseconds 1200
                continue
            }
            throw
        }
    }
}

function Save-DirectLogs {
    param(
        [string]$Serial,
        [string]$Package,
        [string]$TargetDir
    )

    $probe = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "run-as", $Package, "ls", "-ld", "files/http_logs") -IgnoreExitCode
    if ($script:LastAdbExitCode -ne 0 -or $probe -match "No such file|not debuggable|Permission denied") {
        Write-Warning "Direct run-as fallback is unavailable. $probe"
        return $false
    }

    try {
        $archivePath = Join-Path $TargetDir "direct-http-logs.tgz"
        Save-AdbBinaryOutput -Arguments @(
            "-s",
            $Serial,
            "exec-out",
            "run-as",
            $Package,
            "sh",
            "-c",
            "cd files && tar -czf - http_logs"
        ) -Path $archivePath
        Write-Host "Saved $archivePath"
        return $true
    } catch {
        Write-Warning "Direct run-as fallback failed. $($_.Exception.Message)"
        return $false
    }
}

$resolvedEnvFile = Resolve-EnvFile -RequestedPath $EnvFile
$dotEnv = Import-DotEnv -Path $resolvedEnvFile
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_BASE_URI" -ParameterName "HttpBaseUri" -Target ([ref]$HttpBaseUri)
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_HOST" -ParameterName "HttpHost" -Target ([ref]$HttpHost)
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_ADB_SERIAL" -ParameterName "DeviceSerial" -Target ([ref]$DeviceSerial)
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_TOKEN" -ParameterName "Token" -Target ([ref]$Token)
Set-DotEnvInt -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_LOCAL_PORT" -ParameterName "LocalPort" -Target ([ref]$LocalPort)
Set-DotEnvInt -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_REMOTE_PORT" -ParameterName "RemotePort" -Target ([ref]$RemotePort)
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_PACKAGE_NAME" -ParameterName "PackageName" -Target ([ref]$PackageName)
Set-DotEnvString -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_OUTPUT_DIR" -ParameterName "OutputDir" -Target ([ref]$OutputDir)
Set-DotEnvSwitch -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_KEEP_FORWARD" -ParameterName "KeepForward" -Target ([ref]$KeepForward)
Set-DotEnvSwitch -Values $dotEnv -EnvName "DDPLAYTV_HTTP_LOG_SKIP_DIRECT_FALLBACK" -ParameterName "SkipDirectFallback" -Target ([ref]$SkipDirectFallback)

$directBaseUri = Resolve-HttpBaseUri -ConfiguredBaseUri $HttpBaseUri -ConfiguredHost $HttpHost -Port $RemotePort
$usesAdbForward = -not $directBaseUri
$serial = ""
$deviceModel = ""
$deviceDate = ""
$packagePid = ""
$baseUri = $directBaseUri

if ($usesAdbForward) {
    $script:AdbPath = Resolve-AdbPath
    $serial = Select-AdbDevice -RequestedSerial $DeviceSerial
    $deviceModel = Invoke-AdbText -Arguments @("-s", $serial, "shell", "getprop", "ro.product.model") -IgnoreExitCode
    $deviceDate = Invoke-AdbText -Arguments @("-s", $serial, "shell", "date") -IgnoreExitCode
    $packagePid = Invoke-AdbText -Arguments @("-s", $serial, "shell", "pidof", $PackageName) -IgnoreExitCode
    $baseUri = "http://127.0.0.1:$LocalPort"
} else {
    Write-Host "Using direct HTTP log server: $baseUri"
}

if (-not $OutputDir) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path (Get-Location) "artifacts\ddplaytv-http-logs\$timestamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

@(
    "collectedAt=$(Get-Date -Format o)",
    "accessMode=$(if ($usesAdbForward) { 'adb-forward' } else { 'direct-http' })",
    "adbUsed=$usesAdbForward",
    "deviceSerial=$serial",
    "deviceModel=$deviceModel",
    "deviceDate=$deviceDate",
    "packageName=$PackageName",
    "packagePid=$packagePid",
    "envFile=$resolvedEnvFile",
    "localPort=$LocalPort",
    "remotePort=$RemotePort",
    "baseUri=$baseUri",
    "tokenProvided=$([bool]$Token)"
) | Set-Content -LiteralPath (Join-Path $OutputDir "collection-info.txt") -Encoding UTF8

$httpSucceeded = $false
$forwardCreated = $false
try {
    if ($usesAdbForward) {
        Invoke-AdbText -Arguments @("-s", $serial, "forward", "tcp:$LocalPort", "tcp:$RemotePort") | Out-Null
        $forwardCreated = $true
        Write-Host "Forwarded tcp:$LocalPort -> tcp:$RemotePort on $serial"
    }

    if ($Token) {
        $headers = @{ Authorization = "Bearer $Token" }
        Save-HttpText -Uri "$baseUri/api/v1/logs/segments" -Headers $headers -Path (Join-Path $OutputDir "segments.json")
        Start-Sleep -Milliseconds 1100
        Save-HttpFile -Uri "$baseUri/api/v1/logs/latest-segment" -Headers $headers -Path (Join-Path $OutputDir "latest-segment.ndjson") -Optional | Out-Null
        Start-Sleep -Milliseconds 1100
        Save-HttpFile -Uri "$baseUri/api/v1/logs/download" -Headers $headers -Path (Join-Path $OutputDir "ddplaytv-http-logs.zip") | Out-Null
        $httpSucceeded = $true
    } else {
        Write-Warning "No token was provided. Skipping authenticated HTTP endpoints."
    }
} catch {
    Write-Warning "HTTP collection failed. $($_.Exception.Message)"
} finally {
    if ($usesAdbForward -and $forwardCreated -and -not $KeepForward) {
        Invoke-AdbText -Arguments @("-s", $serial, "forward", "--remove", "tcp:$LocalPort") -IgnoreExitCode | Out-Null
    }
}

if (-not $httpSucceeded -and -not $SkipDirectFallback) {
    if (-not $serial -and $DeviceSerial) {
        try {
            $script:AdbPath = Resolve-AdbPath
            $serial = Select-AdbDevice -RequestedSerial $DeviceSerial
        } catch {
            Write-Warning "Configured adb fallback device is unavailable. $($_.Exception.Message)"
        }
    }

    if ($serial) {
        Save-DirectLogs -Serial $serial -Package $PackageName -TargetDir $OutputDir | Out-Null
    } else {
        Write-Warning "Direct run-as fallback was skipped because adb was not used. Configure DDPLAYTV_ADB_SERIAL for fallback, or omit direct HTTP settings to use adb-forward mode."
    }
}

Write-Host "DDPlayTV HTTP log collection output: $OutputDir"
