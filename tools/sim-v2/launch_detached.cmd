@echo off
rem Starts a simulator v2 experiment run (or pilot) detached from this console, so closing the
rem window, a Ctrl-C or a tool restart does not kill it. From the repository root:
rem
rem   tools\sim-v2\launch_detached.cmd OUTDIR run   --prereg research/prereg/R5-007.yaml [--workers 8]
rem   tools\sim-v2\launch_detached.cmd OUTDIR pilot --prereg research/prereg/SIM3-BASE-001.yaml --pilot 8
rem
rem OUTDIR must be outside git (the runner refuses otherwise). The class directories named in
rem rates-app\target\sim-v2-cp.txt (tools/sim-v2/build_classpath.sh) are copied into OUTDIR\lib
rem first, so rebuilding the checkout cannot change a run in progress. The run writes
rem OUTDIR\heartbeat.txt at least once a minute and OUTDIR\runner.log; on exit it leaves
rem OUTDIR\DONE, or OUTDIR\FAILED.runner with the exit code (OUTDIR\FAILED is the runner's own
rem marker for a session that failed twice). Watch it with tools\sim-v2\status.cmd OUTDIR.
setlocal EnableExtensions
rem shift moves %0 too, so the script's own folder is kept first
set "HERE=%~dp0"
if "%~2"=="" (
  echo usage: launch_detached.cmd OUTDIR run^|pilot --prereg FILE [options]
  exit /b 1
)
set "OUT=%~f1"
pushd "%HERE%..\.." || exit /b 1
set "REPO=%CD%"
popd
rem the runner's arguments travel as one line; they must not contain spaces
set "ARGS="
:collect
shift
if "%~1"=="" goto launch
set "ARGS=%ARGS% %~1"
goto collect
:launch
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%launch_detached.ps1" -Out "%OUT%" -Repo "%REPO%" -ArgLine "%ARGS%"
exit /b %ERRORLEVEL%
