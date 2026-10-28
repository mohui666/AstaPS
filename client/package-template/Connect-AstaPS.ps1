[CmdletBinding()]
param(
    [string]$GameDirectory,
    [string]$ProfilePath,
    [switch]$ConfigureOnly,
    [switch]$CheckOnly,
    [switch]$Reset
)
$ErrorActionPreference = 'Stop'

try {
    if ($CheckOnly -and $Reset) { throw 'Use CheckOnly or Reset, not both.' }
    if (-not $ProfilePath) { $ProfilePath = Join-Path $PSScriptRoot 'profile.json' }
    $ProfilePath = (Resolve-Path -LiteralPath $ProfilePath).Path
    $profile = Get-Content -LiteralPath $ProfilePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $serverUrl = [string]$profile.serverUrl
    $serverUri = $null
    $serverIp = $null
    if (-not [Uri]::TryCreate($serverUrl, [UriKind]::Absolute, [ref]$serverUri) -or
        $serverUri.Scheme -ne 'http' -or $serverUri.UserInfo -or
        $serverUri.AbsolutePath -ne '/' -or $serverUri.Query -or $serverUri.Fragment -or
        $serverUri.Port -lt 1 -or
        -not [Net.IPAddress]::TryParse($serverUri.Host, [ref]$serverIp) -or
        $serverIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $serverIp.Equals([Net.IPAddress]::Any) -or
        $serverIp.Equals([Net.IPAddress]::Broadcast) -or $serverIp.GetAddressBytes()[0] -ge 224) {
        throw 'Fill profile.json serverUrl with http://IPv4:port. SakuraFrp needs its node IPv4 and HTTP port.'
    }
    $serverUrl = 'http://' + $serverUri.Authority
    $localRecharge = $true
    if ($null -ne $profile.localRecharge) {
        if ($profile.localRecharge -isnot [bool]) { throw 'profile.json localRecharge must be true or false.' }
        $localRecharge = $profile.localRecharge
    }

    if (-not $GameDirectory) {
        $savedPath = Join-Path $PSScriptRoot 'game-path.txt'
        $candidates = @()
        if (Test-Path -LiteralPath $savedPath -PathType Leaf) {
            $candidates += [IO.File]::ReadAllText($savedPath).Trim()
        }
        $candidates += $env:GAME_PATH
        foreach ($key in @(
            'HKCU:\Software\miHoYo\HYP\1_1\hk4e_cn',
            'HKLM:\Software\WOW6432Node\miHoYo\HYP\1_1\hk4e_cn',
            'HKCU:\Software\miHoYo\Genshin Impact')) {
            $entry = Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue
            if ($entry.GameInstallPath) { $candidates += $entry.GameInstallPath }
        }
        $candidates += (Join-Path $env:ProgramFiles 'miHoYo Launcher\games\Genshin Impact Game')
        $GameDirectory = $candidates | Where-Object {
            $_ -and (Test-Path -LiteralPath (Join-Path $_ 'YuanShen.exe') -PathType Leaf)
        } | Select-Object -First 1
    }
    if (-not $GameDirectory) {
        if ($CheckOnly) { throw 'Game directory not found. Pass -GameDirectory.' }
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = New-Object Windows.Forms.OpenFileDialog
        try {
            $dialog.Title = 'Select the CN 7.1.0 game: YuanShen.exe'
            $dialog.Filter = 'YuanShen.exe|YuanShen.exe'
            if ($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { throw 'No game selected.' }
            $GameDirectory = Split-Path -Parent $dialog.FileName
        } finally { $dialog.Dispose() }
    }
    $GameDirectory = (Resolve-Path -LiteralPath $GameDirectory).Path
    foreach ($file in @('YuanShen.exe', 'config.ini',
        'YuanShen_Data\Plugins\Astrolabe.dll', 'YuanShen_Data\Plugins\AccountPlatNative.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $GameDirectory $file) -PathType Leaf)) {
            throw ('Game file missing: ' + $file)
        }
    }
    if (-not ((Get-Content -LiteralPath (Join-Path $GameDirectory 'config.ini')) -match '^game_version=7\.1\.0\s*$')) {
        throw 'This package requires the CN 7.1.0 client. Keep the client at this version.'
    }
    foreach ($file in @('ext.dll', 'passport-public.pem', 'patch_game.ps1', 'game_path.ps1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $file) -PathType Leaf)) {
            throw ('Package file missing: ' + $file)
        }
    }
    $dll = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'ext.dll'))
    if ($dll.Length -lt 64 -or $dll[0] -ne 0x4D -or $dll[1] -ne 0x5A -or
        -not [Text.Encoding]::ASCII.GetString($dll).Contains('Astrolabe_orig.dll')) {
        throw 'ext.dll is not the expected patch build.'
    }

    if (-not $Reset) {
        $status = Invoke-RestMethod -Uri ($serverUrl + '/status/server') -TimeoutSec 8
        if ($status.retcode -ne 0 -or $status.status.version -ne '7.1.0') {
            throw 'The endpoint is not serving AstaPS 7.1.0.'
        }
    }
    if ($CheckOnly) {
        Write-Output ('Validated: CN 7.1.0, package complete, server reachable at ' + $serverUrl)
        Write-Output 'No game files changed. Account sign-in and live PvP require in-game verification.'
        return
    }
    if (Get-Process -Name YuanShen,GenshinImpact -ErrorAction SilentlyContinue) {
        throw 'Exit the game first, then run this launcher again.'
    }

    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath +
            '" -GameDirectory "' + $GameDirectory + '" -ProfilePath "' + $ProfilePath + '"'
        if ($ConfigureOnly) { $arguments += ' -ConfigureOnly' }
        if ($Reset) { $arguments += ' -Reset' }
        $child = Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $arguments -Wait -PassThru
        exit $child.ExitCode
    }

    $mode = if ($Reset) { 'reset' } else { 'apply' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'patch_game.ps1') -Mode $mode -GameDirectory $GameDirectory -ServerUrl $serverUrl -PatchDllPath (Join-Path $PSScriptRoot 'ext.dll') -PassportPublicKeyPath (Join-Path $PSScriptRoot 'passport-public.pem')
    if ($LASTEXITCODE -ne 0) { throw ('Client patch ' + $mode + ' failed. The game was not launched.') }
    if ($Reset) {
        Write-Output 'The original client DLLs were restored by the existing patch installer.'
        return
    }

    $utf8 = New-Object Text.UTF8Encoding($false)
    $connection = @{ serverUrl = $serverUrl; localRecharge = $localRecharge } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText((Join-Path $GameDirectory 'astaps-client.json'), $connection, $utf8)
    $assetPath = Join-Path $GameDirectory 'YuanShen_Data\StreamingAssets\asb_settings.json'
    $assets = Get-Content -LiteralPath $assetPath -Raw | ConvertFrom-Json
    if ($assets.enable_download -ne $false) {
        $assets.enable_download = $false
        [IO.File]::WriteAllText($assetPath, ($assets | ConvertTo-Json -Depth 20 -Compress), $utf8)
    }
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'game-path.txt'), $GameDirectory, $utf8)
    Write-Output ('AstaPS configured: ' + $serverUrl)
    if ($profile.accountName) {
        Write-Output ('Account: ' + $profile.accountName + '    UID: ' + $profile.uid)
        Write-Output 'Use the password in profile.json at the game login screen.'
    }
    Write-Output 'PvP patch is enabled; the server must authorize both accounts.'
    if (-not $ConfigureOnly) {
        $env:ASTAPS_SERVER_URL = $serverUrl
        $env:LUNAGC_DISABLE = 'misc,console,ifix'
        $env:LUNAGC_LOCAL_RECHARGE = if ($localRecharge) { '1' } else { '0' }
        Start-Process -FilePath (Join-Path $GameDirectory 'YuanShen.exe') -WorkingDirectory $GameDirectory
    }
} catch {
    [Console]::Error.WriteLine('Setup failed: ' + $_.Exception.Message)
    if (-not $CheckOnly) {
        Add-Type -AssemblyName System.Windows.Forms
        [void][Windows.Forms.MessageBox]::Show($_.Exception.Message, 'AstaPS setup failed')
    }
    exit 1
}
