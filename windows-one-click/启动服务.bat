@echo off
cd /d "%~dp0"
echo Starting muse2api service... Do not close this window.
".\venv\Scripts\python.exe" -m uvicorn app:app --host 0.0.0.0 --port 18610
pause
