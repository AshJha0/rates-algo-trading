# Called by launch_detached.cmd. Freezes this build's classes under OUTDIR\lib, writes
# OUTDIR\java.args, and starts run_inner.cmd through WMI so the run is not a child of this
# console (a closed window, Ctrl-C or a tool restart does not stop it).
param(
  [Parameter(Mandatory = $true)][string]$Out,
  [Parameter(Mandatory = $true)][string]$Repo,
  [Parameter(Mandatory = $true)][string]$ArgLine
)
$RunnerArgs = $ArgLine.Trim() -split '\s+' 
$ErrorActionPreference = 'Stop'
$cpFile = Join-Path $Repo 'rates-app\target\sim-v2-cp.txt'
if (-not (Test-Path $cpFile)) { Write-Output 'classpath missing: run tools/sim-v2/build_classpath.sh first'; exit 1 }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
foreach ($m in 'DONE', 'FAILED.runner') { if (Test-Path (Join-Path $Out $m)) { Remove-Item (Join-Path $Out $m) } }
$entries = (Get-Content -Raw $cpFile).Trim() -split ';'
$frozen = @()
foreach ($e in $entries) {
  if ($e -eq '') { continue }
  $path = if ([System.IO.Path]::IsPathRooted($e)) { $e } else { Join-Path $Repo $e }
  if (Test-Path $path -PathType Container) {
    # module classes: copy so the run keeps the code it started with
    $module = ($e -replace '[\\/]target[\\/]classes$', '') -replace '[\\/]', '_'
    $dest = Join-Path $Out ('lib\' + $module)
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Recurse -Force (Join-Path $path '*') $dest
    $frozen += $dest
  } else {
    $frozen += $path
  }
}
$cp = ($frozen -join ';') -replace '\\', '/'
Set-Content -Encoding ascii -Path (Join-Path $Out 'java.args') -Value ('-cp "' + $cp + '"')
$quoted = ($RunnerArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '
Set-Content -Encoding ascii -Path (Join-Path $Out 'launch.txt') -Value ("repo $Repo", "args $quoted")
$inner = Join-Path $PSScriptRoot 'run_inner.cmd'
$line = 'cmd.exe /c ""' + $inner + '" "' + $Out + '" "' + $Repo + '" ' + $quoted + '"'
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $line; CurrentDirectory = $Repo }
if ($r.ReturnValue -ne 0) { Write-Output ('could not start the run, WMI code ' + $r.ReturnValue); exit 1 }
Write-Output ('started detached, process id ' + $r.ProcessId + '; heartbeat ' + (Join-Path $Out 'heartbeat.txt'))
exit 0
