@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  MG4 APK İmzalama, SHA-256 Hash ve Kurma Scripti
::
::  Referans: adammcdonagh/MG4-Custom-Launcher/sign_apk.sh
::  İmzalama: apksigner --key platform.pk8 --cert platform.x509.pem
::  (p12 keystore değil — araç bu yöntemi kabul ediyor)
::
::  Çıktılar (tools\releases\):
::    DriveHub_Dort_{versionName}.apk
::    DriveHub_Dort_{versionName}.apk.sha256   ← GitHub OTA için
::
::  Kullanım: Android Studio'da assembleRelease yaptıktan sonra
::             bu dosyayı çift tıkla.
::
::  GitHub Release için ayrı script:
::    tools\publish_github_release.bat
:: ============================================================

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set RELEASES_DIR=%SCRIPT_DIR%releases
set APK_IN=%PROJECT_DIR%\app\build\outputs\apk\car\release\app-car-release-unsigned.apk
set APK_OUT=%PROJECT_DIR%\app\build\outputs\apk\car\release\app-release-signed.apk
set PLATFORM_PK8=%SCRIPT_DIR%platform.pk8
set PLATFORM_PEM=%SCRIPT_DIR%platform.x509.pem

REM build.gradle icinden versionName oku
set VERSION_NAME=unknown
for /f "tokens=2 delims== " %%v in ('findstr /R /C:"versionName" "%PROJECT_DIR%\app\build.gradle"') do (
    set VERSION_NAME=%%v
)
set VERSION_NAME=%VERSION_NAME:"=%
set APK_TOOLS_NAME=DriveHub_Dort_%VERSION_NAME%.apk
set APK_TOOLS_PATH=%RELEASES_DIR%\%APK_TOOLS_NAME%
set APK_HASH_PATH=%APK_TOOLS_PATH%.sha256

if not exist "%RELEASES_DIR%" mkdir "%RELEASES_DIR%"

:: apksigner.jar yolunu otomatik bul (en yüksek build-tools sürümü)
set APKSIGNER_JAR=
set BUILD_TOOLS_BASE=%LOCALAPPDATA%\Android\Sdk\build-tools
for /d %%v in ("%BUILD_TOOLS_BASE%\*") do (
    set APKSIGNER_JAR=%%v\lib\apksigner.jar
)

:: -------- Kontroller --------

if not exist "%APK_IN%" (
    echo.
    echo [HATA] APK bulunamadi: %APK_IN%
    echo        Android Studio'da Build ^> Make Project yapip tekrar dene.
    pause & exit /b 1
)

if not exist "%PLATFORM_PK8%" (
    echo.
    echo [HATA] platform.pk8 bulunamadi: %PLATFORM_PK8%
    pause & exit /b 1
)

if not exist "%PLATFORM_PEM%" (
    echo.
    echo [HATA] platform.x509.pem bulunamadi: %PLATFORM_PEM%
    pause & exit /b 1
)

if not exist "%APKSIGNER_JAR%" (
    echo.
    echo [HATA] apksigner.jar bulunamadi. Android SDK build-tools yuklu olmali.
    pause & exit /b 1
)

echo.
echo ============================================================
echo  MG4 APK Imzalama, Hash ve Kurma
echo ============================================================
echo  Kaynak APK : %APK_IN%
echo  Cikti  APK : %APK_OUT%
echo  Releases   : %APK_TOOLS_PATH%
echo  SHA-256    : %APK_HASH_PATH%
echo  Yontem     : --key platform.pk8 --cert platform.x509.pem
echo.

:: -------- İmzala --------
echo [1/3] Imzalaniyor...
java -jar "%APKSIGNER_JAR%" sign ^
    --key "%PLATFORM_PK8%" ^
    --cert "%PLATFORM_PEM%" ^
    --out "%APK_OUT%" ^
    "%APK_IN%"

if errorlevel 1 (
    echo.
    echo [HATA] Imzalama basarisiz!
    pause & exit /b 1
)
echo [1/3] Imzalama tamamlandi.

:: -------- tools/releases/ klasorune kopyala --------
copy /Y "%APK_OUT%" "%APK_TOOLS_PATH%" >nul
echo       Kopya: %APK_TOOLS_PATH%

:: -------- OTA icin SHA-256 sidecar --------
echo [2/3] SHA-256 hash olusturuluyor...
powershell -NoProfile -Command ^
  "$h = (Get-FileHash -LiteralPath '%APK_TOOLS_PATH%' -Algorithm SHA256).Hash.ToLowerInvariant();" ^
  "Set-Content -LiteralPath '%APK_HASH_PATH%' -Value ($h + '  %APK_TOOLS_NAME%') -Encoding Ascii -NoNewline"

if errorlevel 1 (
    echo.
    echo [HATA] SHA-256 hash olusturulamadi!
    pause & exit /b 1
)
if not exist "%APK_HASH_PATH%" (
    echo.
    echo [HATA] Hash dosyasi yazilamadi: %APK_HASH_PATH%
    pause & exit /b 1
)
echo [2/3] Hash hazir: %APK_HASH_PATH%

:: -------- ADB kurulum --------
echo [3/3] Araca yukleniyor...
adb devices 2>nul | findstr /v "List" | findstr "device" >nul
if errorlevel 1 (
    echo.
    echo [UYARI] ADB ile bagli cihaz bulunamadi.
    echo         Araci USB ile bagla ve tekrar dene.
    echo         Imzali APK hazir: %APK_OUT%
    echo         Hash dosyasi   : %APK_HASH_PATH%
) else (
    adb install -r "%APK_OUT%"
    if errorlevel 1 (
        echo.
        echo [HATA] Yukleme basarisiz! Yukaridaki hataya bak.
    ) else (
        echo.
        echo ============================================================
        echo  TAMAMLANDI! Uygulama araca yuklendi.
        echo  GitHub Release icin: tools\publish_github_release.bat
        echo ============================================================
    )
)

echo.
echo  APK  : %APK_TOOLS_PATH%
echo  Hash : %APK_HASH_PATH%
echo.
pause
