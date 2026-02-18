@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  MG4 APK İmzalama ve Kurma Scripti
::  Kullanım: Android Studio'da "assembleDebug" çalıştırdıktan
::             sonra bu dosyayı çift tıkla veya CMD'den çalıştır.
:: ============================================================

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set APK_IN=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
set APK_OUT=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug-platform-signed.apk
set KEYSTORE=%PROJECT_DIR%\platform.p12

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
    echo        Önce Android Studio'da Build ^> Make Project yapın.
    pause & exit /b 1
)

if not exist "%KEYSTORE%" (
    echo.
    echo [HATA] Keystore bulunamadi: %KEYSTORE%
    echo        platform.p12 dosyası proje kökünde olmalı.
    pause & exit /b 1
)

if not defined APKSIGNER_JAR (
    echo.
    echo [HATA] apksigner.jar bulunamadi.
    echo        Android SDK build-tools yüklü olmalı.
    pause & exit /b 1
)

if not exist "%APKSIGNER_JAR%" (
    echo.
    echo [HATA] apksigner.jar bulunamadi: %APKSIGNER_JAR%
    pause & exit /b 1
)

echo.
echo ============================================================
echo  MG4 APK Imzalama ve Kurma
echo ============================================================
echo  Kaynak APK : %APK_IN%
echo  Hedef  APK : %APK_OUT%
echo  apksigner  : %APKSIGNER_JAR%
echo.

:: -------- APK kopyala --------
copy /Y "%APK_IN%" "%APK_OUT%" >nul
echo [1/3] APK kopyalandi.

:: -------- İmzala --------
java -jar "%APKSIGNER_JAR%" sign ^
    --ks "%KEYSTORE%" ^
    --ks-key-alias platform ^
    --ks-pass pass:android ^
    "%APK_OUT%"

if errorlevel 1 (
    echo.
    echo [HATA] Imzalama basarisiz!
    pause & exit /b 1
)
echo [2/3] APK imzalandi.

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
echo [3/3] Araca yukleniyor...
adb install -r "%APK_OUT%"

if errorlevel 1 (
    echo.
    echo [HATA] Yukleme basarisiz! Yukarıdaki hataya bak.
) else (
    echo.
    echo ============================================================
    echo  TAMAMLANDI! Uygulama araca yuklendi.
    echo  Logcat icin Android Studio Logcat sekmesini ac.
    echo ============================================================
)

echo.
pause
