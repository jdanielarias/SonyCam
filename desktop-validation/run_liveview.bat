@echo off
REM Double-click launcher for the Phase 5 live-view window.
REM Close the window (or Ctrl+C) to stop. Keeps the console open on error.
cd /d "%~dp0"
echo Opening the live-view window (close it to stop)...
echo.
python phase5_liveview.py --view
echo.
echo ---- live view finished (or errored above) ----
pause
