@echo off
REM Atajo para Windows: run_server.bat [--pair] [otras opciones]
cd /d "%~dp0"
if not exist .venv (
    py -3 -m venv .venv
    .venv\Scripts\pip install -r requirements.txt
)
.venv\Scripts\python -m airwatch_server %*
