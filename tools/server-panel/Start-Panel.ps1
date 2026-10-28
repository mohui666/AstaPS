param()

$ErrorActionPreference = 'Stop'

try {
    $panelDirectory = $PSScriptRoot
    $configPath = Join-Path $panelDirectory 'panel.config.json'
    $statePath = Join-Path $panelDirectory '.panel-state.json'
    $backendPath = Join-Path $panelDirectory 'server_panel.py'
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $port = [int]$config.port
    if ($port -lt 1 -or $port -gt 65535) { throw '面板端口配置无效。' }
    if (-not (Test-Path -LiteralPath $config.pythonExe -PathType Leaf)) { throw '配置的 Python 启动程序不存在。' }
    if (-not (Test-Path -LiteralPath $backendPath -PathType Leaf)) { throw '缺少 server_panel.py。请重新安装面板。' }
    $baseUrl = "http://127.0.0.1:$port"
    $health = $null
    try { $health = Invoke-RestMethod -Uri "$baseUrl/healthz" -TimeoutSec 2 -UseBasicParsing } catch { }
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener -and ($null -eq $health -or $health.app -ne 'mgc-server-panel')) {
        throw "端口 $port 已被其他程序占用，MGC 面板未启动。"
    }
    if (-not $listener) {
        $panelProcess = Start-Process -FilePath $config.pythonExe -ArgumentList @(('"' + $backendPath + '"'), '--config', ('"' + $configPath + '"')) -WorkingDirectory $panelDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $panelDirectory '.panel-stdout.log') -RedirectStandardError (Join-Path $panelDirectory '.panel-stderr.log') -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            if ($panelProcess.HasExited) { throw '面板进程已退出。请查看面板目录中的 .panel-stderr.log。' }
            Start-Sleep -Milliseconds 200
            try { $health = Invoke-RestMethod -Uri "$baseUrl/healthz" -TimeoutSec 1 -UseBasicParsing } catch { $health = $null }
        } while (($null -eq $health -or $health.app -ne 'mgc-server-panel') -and [DateTime]::UtcNow -lt $deadline)
        if ($null -eq $health -or $health.app -ne 'mgc-server-panel') { throw '面板未能在 15 秒内就绪。请查看 .panel-stderr.log。' }
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
    }
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw '已有面板进程，但当前目录没有对应会话文件。请使用原来的面板入口。' }
    $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($state.app -ne 'mgc-server-panel' -or [int]$state.port -ne $port -or [int]$state.pid -ne [int]$listener.OwningProcess -or [string]::IsNullOrWhiteSpace([string]$state.token)) {
        throw '面板会话与当前监听进程不一致。请使用该面板的原入口。'
    }
    $panelUrl = "$baseUrl/#token=$([Uri]::EscapeDataString([string]$state.token))"
    Start-Process -FilePath $panelUrl | Out-Null
} catch {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'MGC 面板启动失败', 'OK', 'Error') | Out-Null
    exit 1
}
