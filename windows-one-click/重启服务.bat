@echo off
cd /d "%~dp0"
echo Stopping old service on port 18610...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :18610 ^| findstr LISTENING') do taskkill /F /PID %%p >nul 2>&1
timeout /t 2 /nobreak >nul
echo Restarting muse2api service... Do not close this window.
".\venv\Scripts\python.exe" -m uvicorn app:app --host 0.0.0.0 --port 18610
pause
