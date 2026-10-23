@echo off
REM Double-click launcher for the Phase 2 live state reader.
REM Keeps the console window open so you can read the output / errors.
cd /d "%~dp0"
echo Reading live camera state (Ctrl+C to stop the live watch)...
echo.
python phase2_read_state.py --watch
echo.
echo ---- script finished (or errored above) ----
pause
