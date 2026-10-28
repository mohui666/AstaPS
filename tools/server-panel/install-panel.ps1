param(
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$Destination = (Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) 'MGC服务器面板'),
    [string]$PythonExe = 'C:\ProgramData\miniconda3\pythonw.exe',
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
$destinationPath = [IO.Path]::GetFullPath($Destination)
$markerPath = Join-Path $destinationPath '.mgc-server-panel'
$markerText = 'mgc-server-panel-v1'
$configPath = Join-Path $destinationPath 'panel.config.json'
$statePath = Join-Path $destinationPath '.panel-state.json'
$backendPath = Join-Path $destinationPath 'server_panel.py'
$entryPath = Join-Path $destinationPath 'MGC服务器面板.html'
$startup = [Environment]::GetFolderPath('Startup')
$startupShortcut = Join-Path $startup 'MGC面板后台.lnk'
$port = 17966
$baseUrl = "http://127.0.0.1:$port"
$utf8 = [Text.UTF8Encoding]::new($false)

if (Test-Path -LiteralPath $destinationPath) {
    if (-not (Test-Path -LiteralPath $destinationPath -PathType Container) -or -not (Test-Path -LiteralPath $markerPath -PathType Leaf) -or [IO.File]::ReadAllText($markerPath).Trim() -ne $markerText) {
        throw "目标已存在且不属于本工具，未覆盖任何内容：$destinationPath"
    }
}
if (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) { throw "已有 Python 不存在：$PythonExe" }
$pythonPath = (Resolve-Path -LiteralPath $PythonExe).Path

function Get-OwnedPanelProcessId {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return 0 }
    try { $health = Invoke-RestMethod -Uri "$baseUrl/healthz" -TimeoutSec 2 -UseBasicParsing } catch { throw "端口 $port 已被其他或无法识别的程序占用。" }
    if ($health.app -ne 'mgc-server-panel' -or -not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw "端口 $port 不属于当前目录的 MGC 管理后台。" }
    $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $backendPattern = '(?i)(?:^|\s)"?' + [regex]::Escape($backendPath) + '"?(?:\s|$)'
    $configPattern = '(?i)(?:^|\s)--config\s+"?' + [regex]::Escape($configPath) + '"?(?:\s|$)'
    if ($state.app -ne 'mgc-server-panel' -or [int]$state.port -ne $port -or [int]$state.pid -ne [int]$listener.OwningProcess -or -not $process -or $process.ExecutablePath -ine $pythonPath -or $process.CommandLine -notmatch $backendPattern -or $process.CommandLine -notmatch $configPattern) {
        throw '现有管理进程的路径、会话或监听 PID 不匹配，未停止任何进程。'
    }
    return [int]$listener.OwningProcess
}

$existingPanelPid = Get-OwnedPanelProcessId
$primaryShortcut = Join-Path $desktop 'MGC服务器面板.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcutPaths = @()
foreach ($file in Get-ChildItem -LiteralPath $desktop -Filter '*.lnk' -File) {
    $shortcut = $shell.CreateShortcut($file.FullName)
    $command = "$($shortcut.TargetPath) $($shortcut.Arguments)"
    $gameEntry = "$($file.Name) $command" -match '(?i)开服并|进入原神|客户端|Start-AstaPS-Game|YuanShen\.exe|GenshinImpact\.exe'
    $knownServer = $command -match '(?i)Start-AstaPS\.(cmd|ps1)|Start-Panel\.ps1|Launch-Panel\.vbs|MGC服务器面板\.html'
    $serverName = $file.BaseName -match '(?i)(MGC|AstaPS).*(服务器|服务端|开服|面板|server|panel)'
    $ownedPath = $command.IndexOf($project, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $command.IndexOf($destinationPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    if (-not $gameEntry -and $ownedPath -and ($knownServer -or $serverName)) { $shortcutPaths += $file.FullName }
}
if ((Test-Path -LiteralPath $primaryShortcut) -and $shortcutPaths -notcontains $primaryShortcut) { throw "同名快捷方式指向其他内容，未覆盖：$primaryShortcut" }
if ($shortcutPaths -notcontains $primaryShortcut) { $shortcutPaths += $primaryShortcut }
$pythonArguments = '"' + $backendPath + '" --config "' + $configPath + '"'
if (Test-Path -LiteralPath $startupShortcut) {
    $oldStartup = $shell.CreateShortcut($startupShortcut)
    if ($oldStartup.TargetPath -ine $pythonPath -or $oldStartup.Arguments -ne $pythonArguments) { throw "同名登录启动项属于其他内容，未覆盖：$startupShortcut" }
}

$legacyCommand = Join-Path $desktop 'AstaPS 开服.cmd'
$legacyContent = "@echo off`r`nchcp 65001 >nul`r`nstart `"`" `"$entryPath`"`r`nexit /b`r`n"
$oldVbsPath = Join-Path $destinationPath 'Launch-Panel.vbs'
$oldPanelCommand = "@echo off`r`nchcp 65001 >nul`r`nstart `"`" `"%SystemRoot%\System32\wscript.exe`" //nologo `"$oldVbsPath`"`r`nexit /b`r`n"
$repairLegacy = Test-Path -LiteralPath $legacyCommand -PathType Leaf
if ($repairLegacy) {
    $original = [IO.File]::ReadAllText($legacyCommand)
    $oldTarget = [regex]::Escape((Join-Path $project 'Start-AstaPS.cmd'))
    $knownLegacy = $original -match "(?is)^\s*(?:@echo\s+off\s*)?call\s+`"$oldTarget`"\s*(?:pause\s*)?$"
    $normalized = $original.Replace("`r`n", "`n").TrimEnd()
    if (-not $knownLegacy -and $normalized -ne $legacyContent.Replace("`r`n", "`n").TrimEnd() -and $normalized -ne $oldPanelCommand.Replace("`r`n", "`n").TrimEnd()) {
        throw "桌面开服命令包含其他内容，未覆盖：$legacyCommand"
    }
}

$retiredFiles = @()
foreach ($name in @('Start-Panel.ps1', 'Launch-Panel.vbs', 'index.html')) {
    $path = Join-Path $destinationPath $name
    $source = Join-Path $PSScriptRoot $name
    if ((Test-Path -LiteralPath $path -PathType Leaf) -and (Test-Path -LiteralPath $source -PathType Leaf)) {
        $item = Get-Item -LiteralPath $path
        if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -and [IO.File]::ReadAllText($path) -eq [IO.File]::ReadAllText($source)) { $retiredFiles += $item.FullName }
    }
}
$actions = @(
    [pscustomobject]@{ action = 'InstallPanelFiles'; path = $destinationPath; files = @('server_panel.py', '.panel-ui.template', 'README.md', 'panel.config.json'); generatedEntry = $entryPath },
    [pscustomobject]@{ action = 'CreateOrRepairHtmlShortcuts'; paths = $shortcutPaths; target = $entryPath },
    [pscustomobject]@{ action = 'CreateUserLoginBackgroundEntry'; path = $startupShortcut; target = $pythonPath; arguments = $pythonArguments },
    [pscustomobject]@{ action = 'StartOrReplaceOwnedManagementBackend'; existingPid = $existingPanelPid; changesJavaOrMongo = $false },
    [pscustomobject]@{ action = 'RemoveUnchangedRetiredToolLaunchers'; paths = $retiredFiles }
)
if ($repairLegacy) { $actions += [pscustomobject]@{ action = 'RepairServerOnlyCommandToOpenHtml'; path = $legacyCommand; target = $entryPath } }
$plan = [pscustomobject]@{ projectRoot = $project; desktopActions = $actions; preserve = @('AstaPS 开服并进入原神.cmd', 'AstaPS 后台.url', '客户端及其他桌面文件'); changesGameServerState = $false }
if ($PlanOnly) { $plan | ConvertTo-Json -Depth 7; return }

foreach ($name in @('server_panel.py', 'index.html', 'README.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "安装源文件缺失：$name" }
}
if (-not (Test-Path -LiteralPath $destinationPath)) { New-Item -ItemType Directory -Path $destinationPath -ErrorAction Stop | Out-Null }
[IO.File]::WriteAllText($markerPath, $markerText, $utf8)
foreach ($name in @('server_panel.py', 'README.md')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $destinationPath $name) -ErrorAction Stop }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'index.html') -Destination (Join-Path $destinationPath '.panel-ui.template') -ErrorAction Stop
$config = @{ projectRoot = $project; pythonExe = $pythonPath; port = $port } | ConvertTo-Json
[IO.File]::WriteAllText($configPath, $config, $utf8)

if ($existingPanelPid -gt 0) {
    $confirmedPid = Get-OwnedPanelProcessId
    if ($confirmedPid -ne $existingPanelPid) { throw '管理后台进程已变化，未停止进程。请重新运行安装器。' }
    $previousPanel = Get-Process -Id $confirmedPid -ErrorAction Stop
    Stop-Process -Id $confirmedPid -ErrorAction Stop
    if (-not $previousPanel.WaitForExit(5000)) { throw '旧管理后台未退出，未启动第二个后台。' }
}
$panelProcess = Start-Process -FilePath $pythonPath -ArgumentList $pythonArguments -WorkingDirectory $destinationPath -WindowStyle Hidden -RedirectStandardOutput (Join-Path $destinationPath '.panel-stdout.log') -RedirectStandardError (Join-Path $destinationPath '.panel-stderr.log') -PassThru
$deadline = [DateTime]::UtcNow.AddSeconds(15)
$ready = $false
do {
    if ($panelProcess.HasExited) { throw '管理后台已退出。请查看面板目录 .panel-stderr.log。' }
    Start-Sleep -Milliseconds 200
    try {
        $health = Invoke-RestMethod -Uri "$baseUrl/healthz" -TimeoutSec 1 -UseBasicParsing
        $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $ready = $health.app -eq 'mgc-server-panel' -and [int]$state.pid -eq $panelProcess.Id -and (Test-Path -LiteralPath $entryPath -PathType Leaf)
    } catch { $ready = $false }
} while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
if (-not $ready) { throw '管理后台未能在 15 秒内生成 HTML 入口并就绪。请查看 .panel-stderr.log。' }

if (-not (Test-Path -LiteralPath $startup)) { New-Item -ItemType Directory -Path $startup -ErrorAction Stop | Out-Null }
$shortcut = $shell.CreateShortcut($startupShortcut)
$shortcut.TargetPath = $pythonPath
$shortcut.Arguments = $pythonArguments
$shortcut.WorkingDirectory = $destinationPath
$shortcut.WindowStyle = 7
$shortcut.Description = 'MGC 本机管理后台；不自动启动游戏服务'
$shortcut.Save()
foreach ($path in $shortcutPaths) {
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $entryPath
    $shortcut.Arguments = ''
    $shortcut.WorkingDirectory = $destinationPath
    $shortcut.WindowStyle = 1
    $shortcut.Description = '打开 MGC 服务器网页面板'
    $shortcut.IconLocation = (Join-Path $env:SystemRoot 'System32\shell32.dll') + ',18'
    $shortcut.Save()
}
if ($repairLegacy) { [IO.File]::WriteAllText($legacyCommand, $legacyContent, $utf8) }
foreach ($path in $retiredFiles) {
    $absolute = [IO.Path]::GetFullPath($path)
    if (-not $absolute.StartsWith($destinationPath.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '旧工具文件路径超出目标目录，未删除。' }
    Remove-Item -LiteralPath $absolute -ErrorAction Stop
}
$plan | ConvertTo-Json -Depth 7
Write-Output '安装完成。双击“MGC服务器面板.html”或桌面快捷方式。仅启动了管理后台，未起停 Java/Mongo。'
