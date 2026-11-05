@echo off
python -X utf8 "%~dp0tools\package_apk.py" %*
exit /b %errorlevel%
