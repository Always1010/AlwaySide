@echo off
if exist "%~dp0.local\android\package-release-upload.py" (
    python -X utf8 "%~dp0.local\android\package-release-upload.py" %*
) else (
    python -X utf8 "%~dp0tools\package_apk.py" %*
)
exit /b %errorlevel%
