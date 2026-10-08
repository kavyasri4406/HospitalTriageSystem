@echo off
rem Compiles the application into out\classes using the bundled libraries in lib\.
setlocal
cd /d "%~dp0"

if exist out\classes rmdir /s /q out\classes
mkdir out\classes

echo Compiling Hospital Triage System...
javac -encoding UTF-8 -d out\classes ^
  --module-path lib\javafx --add-modules javafx.controls ^
  -cp "lib\*" ^
  -sourcepath src\main\java ^
  src\main\java\com\hospital\MainApp.java
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  exit /b 1
)
echo Build OK - classes in out\classes
exit /b 0
