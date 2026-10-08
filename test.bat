@echo off
rem Compiles and runs the self-test suite (data structures, algorithms, validation, JDBC).
setlocal
cd /d "%~dp0"

if exist out\test-classes rmdir /s /q out\test-classes
mkdir out\test-classes

javac -encoding UTF-8 -d out\test-classes ^
  --module-path lib\javafx --add-modules javafx.controls ^
  -cp "lib\*" ^
  -sourcepath "src\main\java;src\test\java" ^
  src\test\java\com\hospital\SelfTestRunner.java
if errorlevel 1 (
  echo TEST BUILD FAILED
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED -cp "out\test-classes;lib\*" com.hospital.SelfTestRunner
exit /b %errorlevel%
