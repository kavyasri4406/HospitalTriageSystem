@echo off
title Hospital Patient Triage ^& Bed Allocator
setlocal
cd /d "%~dp0"

call "%~dp0build.bat" || (pause & exit /b 1)

rem Optional MySQL settings (otherwise MySQL is tried on localhost:3306 and SQLite is used as fallback):
rem   set HOSPITAL_DB_MODE=mysql
rem   set HOSPITAL_DB_USER=root
rem   set HOSPITAL_DB_PASSWORD=yourpassword

echo Launching JavaFX application...
java --module-path lib\javafx --add-modules javafx.controls ^
  --enable-native-access=javafx.graphics,ALL-UNNAMED --sun-misc-unsafe-memory-access=allow ^
  -cp "out\classes;lib\*" ^
  com.hospital.MainApp
if errorlevel 1 pause
