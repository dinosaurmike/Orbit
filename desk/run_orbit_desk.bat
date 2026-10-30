@echo off
REM Double-click to start Orbit Desk. The first time, it installs its two small add-ons.
cd /d "%~dp0"
set PY=py
where py >nul 2>nul || set PY=python
%PY% -m pip install --quiet --user -r requirements.txt
%PY% orbit_desk.py %*
pause
