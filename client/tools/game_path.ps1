# ---------------------------------------------------------------------------
#  Resolve the game install directory.
#
#  Priority:
#    1. GAME_PATH from the process environment
#    2. GAME_PATH from the checkout's .env.local, then .env
#    3. miHoYo launcher registry (HYP 1_1 / Genshin Impact keys)
#    4. fail
#
#  Prints ONLY the resolved path on stdout (this script is also used as a
#  Taskfile variable, so it must not emit anything else).  Diagnostics go to
#  stderr.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Test-GameRoot([string] $path) {
    if (-not $path) { return $false }
    return Test-Path (Join-Path $path 'YuanShen_Data\Plugins\Astrolabe.dll')
}

function Resolve-GamePath {
    # 1. explicit env / .env value
    $envPath = $env:GAME_PATH
    if ($envPath -and $envPath.Trim() -ne '') {
        return $envPath.Trim()
    }

    # Detached helpers and direct PowerShell invocations do not pass through
    # Task's dotenv loader. Read only this literal setting; never execute .env.
    $repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    foreach ($file in @('.env.local', '.env')) {
        $envFile = Join-Path $repo $file
        if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { continue }
        foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
            if ($line -notmatch '^\s*GAME_PATH\s*=\s*(.*?)\s*$') { continue }
            $value = $Matches[1].Trim()
            if ($value.Length -ge 2 -and (
                ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if ($value.Trim()) { return $value.Trim() }
        }
    }

    # Launcher registry -- prefer the HYP (new launcher) hk4e_cn subkey,
    #    then scan every HYP game subkey, then the legacy single-launcher keys.
    $candidates = @()
    $candidates += 'HKCU:\Software\miHoYo\HYP\1_1\hk4e_cn'
    $candidates += 'HKLM:\Software\WOW6432Node\miHoYo\HYP\1_1\hk4e_cn'
    foreach ($key in 'HKCU:\Software\miHoYo\HYP\1_1', 'HKLM:\Software\WOW6432Node\miHoYo\HYP\1_1') {
        try {
            foreach ($child in Get-ChildItem $key -ErrorAction SilentlyContinue) {
                $candidates += $child.PSPath
            }
        } catch { }
    }
    $candidates += 'HKCU:\Software\miHoYo\Genshin Impact'
    $candidates += 'HKLM:\Software\WOW6432Node\miHoYo\Genshin Impact'

    foreach ($key in $candidates) {
        try {
            $val = (Get-ItemProperty $key -ErrorAction SilentlyContinue).GameInstallPath
            if ($val -and $val.Trim() -ne '') {
                return $val.Trim()
            }
        } catch { }
    }

    return $null
}

$path = Resolve-GamePath

if (-not $path) {
    [Console]::Error.WriteLine(
        "GAME_PATH is not set and no miHoYo launcher install was found in the registry.`n" +
        "Set GAME_PATH in .env to the directory that contains YuanShen.exe.")
    # Distinguish a server-only host from a configured but invalid install.
    exit 2
}
if (-not (Test-GameRoot $path)) {
    [Console]::Error.WriteLine(
        "Resolved game path does not look like a Genshin client (missing YuanShen_Data\Plugins\Astrolabe.dll): $path")
    exit 1
}

Write-Output $path
