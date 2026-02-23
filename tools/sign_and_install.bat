@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  MG4 APK İmzalama ve Kurma Scripti
::
::  Referans: adammcdonagh/MG4-Custom-Launcher/sign_apk.sh
::  İmzalama: apksigner --key platform.pk8 --cert platform.x509.pem
::  (p12 keystore değil — araç bu yöntemi kabul ediyor)
::
::  Kullanım: Android Studio'da assembleDebug yaptıktan sonra
::             bu dosyayı çift tıkla.
:: ============================================================

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set APK_IN=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
set APK_OUT=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug-signed.apk
set PLATFORM_PK8=%SCRIPT_DIR%platform.pk8
set PLATFORM_PEM=%SCRIPT_DIR%platform.x509.pem

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
echo  MG4 APK Imzalama ve Kurma
echo ============================================================
echo  Kaynak APK : %APK_IN%
echo  Cikti  APK : %APK_OUT%
echo  Yontem     : --key platform.pk8 --cert platform.x509.pem
echo.

:: -------- İmzala (referans projeyle aynı yöntem) --------
echo [1/2] Imzalaniyor...
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
echo [1/2] Imzalama tamamlandi.

:: -------- ADB kontrol --------
adb devices 2>nul | findstr /v "List" | findstr "device" >nul
if errorlevel 1 (
    echo.
    echo [UYARI] ADB ile bagli cihaz bulunamadi.
    echo         Araci USB ile bagla ve tekrar dene.
    echo         Imzali APK hazir: %APK_OUT%
    pause & exit /b 0
)

:: -------- Yükle --------
echo [2/2] Araca yukleniyor...
echo        Once eski surum kaldiriliyor...
::adb uninstall com.example.mg4_v3 >nul 2>&1
::adb install "%APK_OUT%"
adb install -r "%APK_OUT%"

if errorlevel 1 (
    echo.
    echo [HATA] Yukleme basarisiz! Yukaridaki hataya bak.
) else (
    echo.
    echo ============================================================
    echo  TAMAMLANDI! Uygulama araca yuklendi.
    echo  Logcat icin Android Studio Logcat sekmesini ac.
    echo ============================================================
)

echo.
pause
