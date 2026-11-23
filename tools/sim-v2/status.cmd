@echo off
rem Status of a detached simulator v2 run: sessions done, wall-clock, heartbeat, failure marker.
rem Reads only the manifest, timing files and markers, never a session's results.
rem   tools\sim-v2\status.cmd OUTDIR
setlocal
if "%~1"=="" (
  echo usage: status.cmd OUTDIR
  exit /b 1
)
if exist "%~f1\FAILED.runner" type "%~f1\FAILED.runner"
if exist "%~f1\DONE" echo runner finished
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED @"%~f1\java.args" io.ratesalgo.app.backtest.DeskExperiment status --out "%~f1"
