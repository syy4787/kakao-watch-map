@echo off
rem Battery Doctor launcher for Galaxy Watch9 (Windows). ASCII only - Korean text is printed by Java.
setlocal EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"

rem ---------- find java.exe: JAVA_HOME -> Android Studio JBR -> PATH ----------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE (
  for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
)
if not defined JAVA_EXE (
  echo [ERROR] java.exe not found.  ^(Java-reul chajji mot haetseumnida^)
  echo   Install Android Studio ^(it includes Java at ...\Android Studio\jbr^) or set JAVA_HOME.
  echo   Android Studio-reul seolchi hageona JAVA_HOME hwangyeong byeonsu-reul seoljeong haseyo.
  goto :end
)

rem ---------- find adb.exe: ANDROID_HOME / ANDROID_SDK_ROOT -> default SDK path -> PATH ----------
set "ADB_EXE="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE (
  for /f "delims=" %%i in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%i"
)
if not defined ADB_EXE (
  echo [ERROR] adb.exe not found.  ^(adb-reul chajji mot haetseumnida^)
  echo   Android Studio: Settings ^> SDK Manager ^> SDK Tools ^> check "Android SDK Platform-Tools" ^> OK.
  echo   Or download platform-tools and set ANDROID_HOME to the SDK folder.
  echo   Android Studio-ui SDK Manager-eseo Platform-Tools-reul seolchi haseyo.
  goto :end
)

if not exist "%~dp0battery-doctor.jar" (
  echo [ERROR] battery-doctor.jar is missing next to this .bat file.
  goto :end
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dbd.adb="%ADB_EXE%" -jar "%~dp0battery-doctor.jar" %*

:end
if "%~1"=="" pause
endlocal
