param(
    [Parameter(Mandatory=$true)][int]$ActivityId,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f-]{36}$')][string]$ResultName
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$archiveRoot = Join-Path $repoRoot 'local\activity-assets\7.1.0'
$resultPath = Join-Path $archiveRoot ('restore-' + $ResultName + '.json')
function Resolve-ContainedPath([string]$Root, [string]$Relative) {
    if ([IO.Path]::IsPathRooted($Relative) -or [string]::IsNullOrWhiteSpace($Relative)) { throw 'Invalid relative resource path.' }
    $full = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    if (-not $full.StartsWith(([IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Resource path escapes its root.' }
    return $full
}
try {
    if ($ActivityId -lt 1) { throw 'Invalid activity ID.' }
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $arguments = @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',('"' + $PSCommandPath + '"'),'-ActivityId',$ActivityId,'-ResultName',$ResultName)
        Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $arguments -Wait
        exit
    }
    if (Get-Process -Name YuanShen -ErrorAction SilentlyContinue) { throw 'Close YuanShen before restoring event resources.' }
    $environment = Get-Content -LiteralPath (Join-Path $repoRoot 'local\environment.json') -Raw | ConvertFrom-Json
    $gameRoot = [IO.Path]::GetFullPath($environment.gameDirectory)
    if ((Get-Content -LiteralPath (Join-Path $gameRoot 'config.ini') -Raw) -notmatch 'game_version\s*=\s*7\.1\.0(\s|$)') { throw 'Resource restore requires the same 7.1.0 client.' }
    $packPath = Join-Path $archiveRoot ('packages\' + $ActivityId + '\package.json')
    $pack = Get-Content -LiteralPath $packPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($pack.formatVersion -ne 1 -or $pack.activityId -ne $ActivityId -or $pack.resourceVersion -ne '7.1.0') { throw 'Event package identity/version mismatch.' }
    $copies = @()
    foreach ($file in $pack.files) {
        if ($file.kind -notin @('client','server')) { throw 'Unknown resource kind.' }
        $source = Resolve-ContainedPath (Join-Path $archiveRoot ('files\' + $file.kind)) $file.path
        $targetRoot = if ($file.kind -eq 'client') { $gameRoot } else { Join-Path $repoRoot 'resources' }
        $destination = Resolve-ContainedPath $targetRoot $file.path
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).Length -ne $file.length) { throw ('Archived resource missing or incomplete: ' + $file.path) }
        $copies += [pscustomobject]@{Source=$source;Destination=$destination;Length=$file.length}
    }
    foreach ($copy in $copies) {
        $parent = Split-Path -Parent $copy.Destination
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
        Copy-Item -LiteralPath $copy.Source -Destination $copy.Destination -ErrorAction Stop
        if ((Get-Item -LiteralPath $copy.Destination).Length -ne $copy.Length) { throw 'Copied resource size mismatch.' }
    }
    @{status='complete';activityId=$ActivityId;fileCount=$copies.Count;message='Original resources restored. Restart the server if server tables/scripts were restored, then load saved settings and reopen the event.';completedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $resultPath -Encoding UTF8
} catch {
    @{status='failed';activityId=$ActivityId;message=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath $resultPath -Encoding UTF8
    exit 1
}
