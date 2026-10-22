@echo off
rem Battery Doctor launcher for Galaxy Watch9 (Windows). ASCII only - Korean text is printed by Java.
setlocal EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"

rem ---------- find java.exe: bundled runtime -> JAVA_HOME -> Android Studio JBR -> PATH ----------
rem The release zip carries its own Java (runtime\, Eclipse Temurin 21) so nothing has to be installed.
set "JAVA_EXE="
if exist "%~dp0runtime\bin\java.exe" set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE (
  for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
)
if not defined JAVA_EXE (
  echo [ERROR] java.exe not found.  ^(Java-reul chajji mot haetseumnida^)
  echo   The "runtime" folder next to this file is missing. Download battery-doctor.zip again
  echo   and unzip ALL files ^(Extract All^): https://github.com/syy4787/kakao-watch-map/releases/latest
  echo   Apchuk-eul jeonbu pureo runtime pold-eoga BatteryDoctor.bat yeope itge haseyo.
  goto :end
)

rem ---------- find adb.exe: this folder -> ANDROID_HOME / ANDROID_SDK_ROOT -> default SDK path -> PATH ----------
rem Not found is fine: the program then asks before downloading Google's platform-tools into this folder.
set "ADB_EXE="
if exist "%~dp0platform-tools\adb.exe" set "ADB_EXE=%~dp0platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE (
  for /f "delims=" %%i in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%i"
)

if not exist "%~dp0battery-doctor.jar" (
  echo [ERROR] battery-doctor.jar is missing next to this .bat file.
  goto :end
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dbd.adb="%ADB_EXE%" -jar "%~dp0battery-doctor.jar" %*

:end
if "%~1"=="" pause
endlocal
