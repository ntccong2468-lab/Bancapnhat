@echo off
setlocal enabledelayedexpansion

if "%JAVA_HOME%"=="" (
    for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do set JAVA_HOME=%%i
    for /f "tokens=*" %%i in ("!JAVA_HOME!") do set JAVA_HOME=%%i
)
if "%JAVA_HOME%"=="" (
    echo JAVA_HOME could not be detected. Install JDK 25 and set JAVA_HOME.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "PACKAGE_TYPE=%~1"
if "%PACKAGE_TYPE%"=="" set "PACKAGE_TYPE=exe"
if /I not "%PACKAGE_TYPE%"=="app-image" if /I not "%PACKAGE_TYPE%"=="exe" if /I not "%PACKAGE_TYPE%"=="msi" (
    echo Usage: build.bat [app-image^|exe^|msi]
    exit /b 1
)

set "APP_NAME=VN code"
set "WINDOWS_UPGRADE_UUID=8CBBA0E2-6E73-4F56-9101-6BC0948D3C72"
set "PROFILE_JAVA_OPTIONS="
if /I "%VNCODE_BUILD_PROFILE%"=="znack-registration-test" (
    set "APP_NAME=VN code Znack Test"
    set "WINDOWS_UPGRADE_UUID=5570F943-39B6-43B8-A643-BBCB53910BF8"
    set "PROFILE_JAVA_OPTIONS=--java-options -Dvncode.data.profile=znack-registration-test"
)
for /f "delims=" %%a in ('mvnw.cmd help:evaluate -Dexpression^=app.version -q -DforceStdout 2^>nul') do set "APP_VERSION=%%a"
for /f "delims=" %%a in ('mvnw.cmd help:evaluate -Dexpression^=app.vendor -q -DforceStdout 2^>nul') do set "APP_VENDOR=%%a"
if "%APP_VERSION%"=="" exit /b 1
if "%APP_VENDOR%"=="" set "APP_VENDOR=VN code"

set "MAIN_JAR=VNcode-%APP_VERSION%.jar"
set "MAIN_CLASS=com.vncode.app.Launcher"
set "JPACKAGE_INPUT=target\jpackage-input"
echo Building JavaFX VN code %APP_VERSION% with Maven...
if /I "%VNCODE_REUSE_VERIFIED_BUILD%"=="true" (
    echo Reusing Maven output already verified by this CI job.
) else (
    call mvnw.cmd -q clean verify
    if errorlevel 1 exit /b 1
)
if not exist "target\%MAIN_JAR%" (
    echo Missing application JAR: target\%MAIN_JAR%
    exit /b 1
)
if not exist "target\lib\*.jar" (
    echo Missing verified runtime dependencies in target\lib.
    exit /b 1
)

if exist "%JPACKAGE_INPUT%" rmdir /s /q "%JPACKAGE_INPUT%"
if exist out rmdir /s /q out
if exist "target\jpackage-temp" rmdir /s /q "target\jpackage-temp"
mkdir "%JPACKAGE_INPUT%\lib"
mkdir out
copy /y "target\%MAIN_JAR%" "%JPACKAGE_INPUT%\%MAIN_JAR%" >nul
xcopy /e /i /y "target\lib" "%JPACKAGE_INPUT%\lib" >nul

set "INSTALLER_OPTIONS="
if /I "%PACKAGE_TYPE%"=="exe" set "INSTALLER_OPTIONS=--install-dir VNcodeApp --win-upgrade-uuid %WINDOWS_UPGRADE_UUID% --win-menu --win-shortcut --win-per-user-install"
if /I "%PACKAGE_TYPE%"=="msi" set "INSTALLER_OPTIONS=--install-dir VNcodeApp --win-upgrade-uuid %WINDOWS_UPGRADE_UUID% --win-menu --win-shortcut --win-per-user-install"
if /I "%VNCODE_BUILD_PROFILE%"=="znack-registration-test" if /I "%PACKAGE_TYPE%"=="exe" set "INSTALLER_OPTIONS=--install-dir VNcodeZnackRegistrationTestApp --win-upgrade-uuid %WINDOWS_UPGRADE_UUID% --win-menu --win-shortcut --win-per-user-install"
if /I "%VNCODE_BUILD_PROFILE%"=="znack-registration-test" if /I "%PACKAGE_TYPE%"=="msi" set "INSTALLER_OPTIONS=--install-dir VNcodeZnackRegistrationTestApp --win-upgrade-uuid %WINDOWS_UPGRADE_UUID% --win-menu --win-shortcut --win-per-user-install"

echo Packaging JavaFX application as %PACKAGE_TYPE%...
jpackage --verbose --type %PACKAGE_TYPE% --name "%APP_NAME%" --input "%JPACKAGE_INPUT%" --main-jar "%MAIN_JAR%" --main-class %MAIN_CLASS% --dest out --temp "target\jpackage-temp" --app-version %APP_VERSION% --vendor "%APP_VENDOR%" --icon src\main\resources\com\vncode\app\assets\images\logo.ico %INSTALLER_OPTIONS% --java-options "--enable-native-access=ALL-UNNAMED" %PROFILE_JAVA_OPTIONS% --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"
if errorlevel 1 exit /b 1

if /I "%PACKAGE_TYPE%"=="app-image" if exist check-portable.bat copy /y check-portable.bat "out\%APP_NAME%\check-portable.bat" >nul
echo Done. Output is in out\.
endlocal
