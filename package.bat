@echo off
rem Builds a self-contained Windows app in dist\ERTriage (bundles its own Java runtime - no Java needed on target PCs).
setlocal
cd /d "%~dp0"

call "%~dp0build.bat" || exit /b 1

rem jar/jpackage are often not on PATH: find the JDK from JAVA_HOME or from the running java.
if defined JAVA_HOME (set "JDK=%JAVA_HOME%") else (
  for /f "tokens=2 delims==" %%h in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JDK=%%h"
)
for /f "tokens=* delims= " %%a in ("%JDK%") do set "JDK=%%a"
if not exist "%JDK%\bin\jpackage.exe" (
  echo jpackage not found. Install a full JDK 21+ and set JAVA_HOME.
  exit /b 1
)

echo Creating application jar...
if exist out\package rmdir /s /q out\package
mkdir out\package
"%JDK%\bin\jar.exe" --create --file out\package\hospital-triage.jar --main-class com.hospital.MainApp -C out\classes .
if errorlevel 1 exit /b 1
copy /y lib\*.jar out\package >nul

echo Running jpackage (this takes a minute)...
if exist dist\ERTriage rmdir /s /q dist\ERTriage
"%JDK%\bin\jpackage.exe" --type app-image ^
  --name ERTriage ^
  --app-version 2.0.0 ^
  --vendor "Hospital Triage System" ^
  --input out\package ^
  --main-jar hospital-triage.jar ^
  --main-class com.hospital.MainApp ^
  --module-path lib\javafx ^
  --add-modules javafx.controls,java.sql,java.naming,java.desktop ^
  --java-options "--enable-native-access=javafx.graphics,ALL-UNNAMED" ^
  --java-options "--sun-misc-unsafe-memory-access=allow" ^
  --java-options "-Ddb.sqlite.file=$APPDIR\..\data\hospital_triage.db" ^
  --dest dist
if errorlevel 1 (
  echo PACKAGING FAILED
  exit /b 1
)
echo.
echo Done: dist\ERTriage\ERTriage.exe
echo Copy the whole dist\ERTriage folder to any Windows PC and run ERTriage.exe.
exit /b 0
