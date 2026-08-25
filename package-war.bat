@echo off
setlocal

cd /d "%~dp0"

echo.
echo ========================================
echo  EAC File Directory WAR Package
echo ========================================
echo.

echo [1/2] Building Tailwind CSS...
tools\tailwindcss.exe -i src\main\resources\static\css\input.css -o src\main\resources\static\css\output.css
if errorlevel 1 (
    echo.
    echo [ERROR] Tailwind build failed.
    pause
    exit /b 1
)

echo.
echo [2/2] Packaging WAR...
set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
call mvnw.cmd clean package
if errorlevel 1 (
    echo.
    echo [ERROR] Maven package failed.
    pause
    exit /b 1
)

echo.
echo WAR created:
echo   %CD%\target\eacmnl#filedirectory.war
echo.
echo Deploy this exact file name to Tomcat webapps:
echo   eacmnl#filedirectory.war  - app URL /eacmnl/filedirectory
echo.
pause
