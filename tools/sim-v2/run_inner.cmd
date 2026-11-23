@echo off
rem Started by launch_detached.cmd through WMI, outside any console: runs the desk's experiment
rem runner with the frozen classpath in OUTDIR\java.args and leaves DONE or FAILED.runner.
rem   run_inner.cmd OUTDIR REPO COMMAND [options]
setlocal EnableExtensions
set "OUT=%~1"
set "REPO=%~2"
shift
shift
set "ARGS="
:collect
if "%~1"=="" goto run
set "ARGS=%ARGS% %1"
shift
goto collect
:run
cd /d "%REPO%"
java -Xmx1g @"%OUT%\java.args" io.ratesalgo.app.backtest.DeskExperiment %ARGS% --repo "%REPO%" --out "%OUT%" >> "%OUT%\runner.log" 2>&1
set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" (
  > "%OUT%\DONE" echo exit 0
) else (
  > "%OUT%\FAILED.runner" echo exit %CODE%: see %OUT%\runner.log
)
exit /b %CODE%
