# Starts the local API server and the existing Pleos AVD, then installs the built APK.
# No SDK downloads, source builds, .env changes, or device permission changes.
[CmdletBinding()]
param([ValidateRange(30,600)][int]$BootTimeoutSeconds = 240)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$avdName = 'Pleos_Connect_v2.1.0_API_34'
$serverFile = Join-Path $projectRoot 'server\index.mjs'
$envFile = Join-Path $projectRoot '.env'
$apkFile = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$cacheDir = Join-Path $projectRoot '.cache'

function Join-NativeArguments([string[]]$Values) {
    # Windows CommandLineToArgvW escaping, including spaces and trailing backslashes.
    $quoted = foreach ($value in $Values) {
        '"' + [regex]::Replace([regex]::Replace($value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
    }
    return $quoted -join ' '
}

function Invoke-Native([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds = 20) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = Join-NativeArguments $Arguments
    $info.WorkingDirectory = $projectRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            throw "Command timed out: $([IO.Path]::GetFileName($File))."
        }
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = ($outTask.Result + $errTask.Result).Trim() }
    } finally { $process.Dispose() }
}

function Get-ServerPort {
    $port = 4173
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in [IO.File]::ReadLines($envFile)) {
            if ($line -match '^\s*(?:export\s+)?PORT\s*=') {
                if ($line -notmatch '^\s*(?:export\s+)?PORT\s*=\s*(?:"(\d+)"|''(\d+)''|(\d+))\s*(?:#.*)?$') {
                    throw 'PORT in .env must be an integer from 1 to 65535.'
                }
                $value = @($Matches[1], $Matches[2], $Matches[3]) | Where-Object { $_ }
                $parsed = 0
                if (-not [int]::TryParse([string]$value, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
                    throw 'PORT in .env must be an integer from 1 to 65535.'
                }
                $port = $parsed
            }
        }
    }
    return $port
}

function Find-Node {
    $candidates = @()
    $command = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    $candidates += Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $version = Invoke-Native $candidate @('--version') 10
        if ($version.ExitCode -eq 0 -and $version.Output -match '^v(\d+)\.(\d+)\.') {
            $major = [int]$Matches[1]; $minor = [int]$Matches[2]
            if (($major -eq 22 -and $minor -ge 19) -or ($major -eq 24 -and $minor -ge 6) -or $major -gt 24) { return $candidate }
        }
    }
    throw 'Node.js 22.19+ or 24.6+ was not found. Install a current Node.js LTS release, reopen this launcher, and try again.'
}

function Find-AndroidSdk {
    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    $properties = Join-Path $projectRoot 'android\local.properties'
    if (Test-Path -LiteralPath $properties) {
        foreach ($line in [IO.File]::ReadLines($properties)) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') { $candidates += $Matches[1].Replace('\:', ':').Replace('\\', '\') }
        }
    }
    $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        if ((Test-Path -LiteralPath (Join-Path $candidate 'platform-tools\adb.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'emulator\emulator.exe'))) { return [IO.Path]::GetFullPath($candidate) }
    }
    throw 'Android SDK tools were not found. In Android Studio, install Android Emulator and Android SDK Platform-Tools.'
}

function Test-ServerHealth([int]$Port) {
    try {
        $status = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/status" -TimeoutSec 3
        return ($null -ne $status.routing -and $null -ne $status.ai -and $null -ne $status.vehicle)
    } catch { return $false }
}

function Start-LocalServer([string]$Node, [int]$Port) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        if (@($listeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }).Count -gt 0) {
            throw "Port $Port has a non-loopback listener. Stop it yourself or choose another PORT in .env. No process was stopped."
        }
        $owners = @($listeners.OwningProcess | Select-Object -Unique)
        foreach ($ownerId in $owners) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId"
            $pathPattern = '(?i)(?<!\S)(?:"' + [regex]::Escape($serverFile) + '"|' + [regex]::Escape($serverFile) + ')(?=\s|$)'
            if (-not $owner -or $owner.Name -ine 'node.exe' -or -not $owner.CommandLine -or
                $owner.CommandLine.Replace('/', '\') -notmatch $pathPattern) {
                throw "Port $Port belongs to another or unidentified process (PID $ownerId). Stop it yourself or choose another PORT in .env. No process was stopped."
            }
        }
        if (-not (Test-ServerHealth $Port)) { throw "This project's server on port $Port is not healthy. Check its logs and restart that server before retrying." }
        Write-Host "Reusing this project's API server: http://127.0.0.1:$Port"
        return
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $cacheDir "server-$stamp.stdout.log"
    $stderr = Join-Path $cacheDir "server-$stamp.stderr.log"
    $previousPort = $env:PORT
    try {
        # Match the .env port even if this shell inherited a different PORT value.
        $env:PORT = [string]$Port
        # Trust the operating system's CA store while retaining certificate and hostname validation.
        $arguments = Join-NativeArguments @('--use-system-ca', "--env-file-if-exists=$envFile", $serverFile)
        $child = Start-Process -FilePath $Node -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    } finally { $env:PORT = $previousPort }
    $ready = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if ($child.HasExited) { break }
        if (Test-ServerHealth $Port) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        if (-not $child.HasExited) { $child.Kill() }
        throw "The API server did not become ready. See $stderr"
    }
    @{ pid = $child.Id; port = $Port; serverPath = $serverFile; stdout = $stdout; stderr = $stderr } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheDir 'server-state.json') -Encoding UTF8
    Write-Host "API server started: http://127.0.0.1:$Port (PID $($child.Id))"
}

function Get-TargetEmulator([string]$Adb) {
    $devices = Invoke-Native $Adb @('devices') 10
    if ($devices.ExitCode -ne 0) { throw 'ADB could not list devices. Check Android SDK Platform-Tools.' }
    $targets = @()
    foreach ($line in ($devices.Output -split '\r?\n' | ForEach-Object { $_.Trim() })) {
        if ($line -match '^(emulator-\d+)\s+device\s*$') {
            $serial = $Matches[1]
            $name = Invoke-Native $Adb @('-s', $serial, 'emu', 'avd', 'name') 5
            if ($name.ExitCode -eq 0 -and ($name.Output -split '\r?\n' | ForEach-Object { $_.Trim() }) -contains $avdName) { $targets += $serial }
        }
    }
    if ($targets.Count -gt 1) { throw "Multiple copies of $avdName are running. Close the duplicate emulator and retry." }
    if ($targets.Count -eq 1) { return $targets[0] }
    return $null
}

function Start-Pleos([string]$Adb, [string]$Emulator) {
    $available = Invoke-Native $Emulator @('-list-avds') 20
    if ($available.ExitCode -ne 0 -or ($available.Output -split '\r?\n' | ForEach-Object { $_.Trim() }) -notcontains $avdName) {
        throw "AVD '$avdName' was not found. Create it in Android Studio Device Manager with the Pleos Connect 2.1.0 API 34 image."
    }
    $serial = Get-TargetEmulator $Adb
    $avdPattern = '(?i)(?:-avd\s+"?' + [regex]::Escape($avdName) + '"?(?=\s|$)|@' + [regex]::Escape($avdName) + '(?=\s|$))'
    $running = @(Get-CimInstance Win32_Process -Filter "Name = 'emulator.exe' OR Name LIKE 'qemu-system%'" | Where-Object { $_.CommandLine -match $avdPattern })
    if (-not $serial -and $running.Count -eq 0) {
        $ports = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | ForEach-Object { $_.Port })
        $consolePort = $null
        for ($candidate = 5554; $candidate -le 5682; $candidate += 2) {
            if ($ports -notcontains $candidate -and $ports -notcontains ($candidate + 1)) { $consolePort = $candidate; break }
        }
        if ($null -eq $consolePort) { throw 'No free Android emulator console port was found.' }
        $arguments = Join-NativeArguments @('-avd', $avdName, '-port', [string]$consolePort, '-no-snapshot-load')
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
        # The emulator is an interactive application; its display remains visible.
        [void](Start-Process -FilePath $Emulator -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $cacheDir "emulator-$stamp.stdout.log") -RedirectStandardError (Join-Path $cacheDir "emulator-$stamp.stderr.log"))
        Write-Host "Starting $avdName..."
    } else { Write-Host "Reusing $avdName..." }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $BootTimeoutSeconds) {
        $serial = Get-TargetEmulator $Adb
        if ($serial) {
            $boot = Invoke-Native $Adb @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed') 10
            if ($boot.ExitCode -eq 0 -and $boot.Output.Trim() -eq '1') { return $serial }
        }
        Start-Sleep -Seconds 2
    }
    throw "Pleos did not finish booting within $BootTimeoutSeconds seconds. Check the emulator and .cache logs, then retry."
}

function Test-InstalledApk([string]$Adb, [string]$Serial, [string]$UserId) {
    $package = Invoke-Native $Adb @('-s', $Serial, 'shell', 'pm', 'path', '--user', $UserId, 'com.scenic.pleos') 15
    if ($package.ExitCode -ne 0 -or $package.Output -notmatch '(?m)^package:(/data/app/[A-Za-z0-9/_+=~.-]+/base\.apk)\s*$') { return $false }
    $installedPath = $Matches[1]
    $hash = Invoke-Native $Adb @('-s', $Serial, 'shell', 'sha256sum', $installedPath) 15
    if ($hash.ExitCode -ne 0 -or $hash.Output -notmatch '^([a-fA-F0-9]{64})\s') { return $false }
    $installedHash = $Matches[1]
    return $installedHash -ieq (Get-FileHash -LiteralPath $apkFile -Algorithm SHA256).Hash
}

try {
    if (-not (Test-Path -LiteralPath $apkFile)) {
        throw "APK not found: $apkFile`nOpen the android folder in Android Studio and build app (Build > Build App Bundle(s) / APK(s) > Build APK(s)), then rerun this launcher."
    }
    $node = Find-Node
    $sdk = Find-AndroidSdk
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $emulator = Join-Path $sdk 'emulator\emulator.exe'
    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) { $env:JAVA_HOME = $studioJbr }
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_HOME = $sdk
    $port = Get-ServerPort
    [void](New-Item -ItemType Directory -Path $cacheDir -Force)
    Start-LocalServer $node $port
    $serial = Start-Pleos $adb $emulator
    $currentUser = Invoke-Native $adb @('-s', $serial, 'shell', 'am', 'get-current-user') 10
    if ($currentUser.ExitCode -ne 0 -or $currentUser.Output.Trim() -notmatch '^\d+$') { throw 'Could not determine the active Pleos user.' }
    $userId = $currentUser.Output.Trim()
    if (Test-InstalledApk $adb $serial $userId) {
        Write-Host "The current APK is already installed on $serial (user $userId)."
    } else {
        Write-Host "Installing SCENIC on $serial (user $userId)..."
        $install = Invoke-Native $adb @('-s', $serial, 'install', '-r', '--user', $userId, $apkFile) 120
        if ($install.ExitCode -ne 0 -or $install.Output -notmatch '(?m)^Success\s*$') { throw "APK installation failed: $($install.Output)" }
    }
    $launch = Invoke-Native $adb @('-s', $serial, 'shell', 'am', 'start', '--user', $userId, '-W', '-n', 'com.scenic.pleos/.MainActivity') 30
    if ($launch.ExitCode -ne 0 -or $launch.Output -match '(?im)^Error:') { throw "SCENIC could not start: $($launch.Output)" }
    Write-Host ''
    Write-Host 'SCENIC is running. You may close this launcher window.'
    Write-Host "In SCENIC settings, use API server: http://10.0.2.2:$port"
    Write-Host 'Keep Pleos in P / 0 km/h. Use the app settings if it requests vehicle-speed permission.'
    Write-Host "Server and emulator logs: $cacheDir"
    exit 0
} catch {
    Write-Host ''
    Write-Host ("SCENIC startup failed: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
