@echo off
setlocal enabledelayedexpansion

echo === Stereo WAV ^> Mono donusturucu (BAT) ===

REM Script'in bulundugu klasoru referans al
set "SCRIPT_DIR=%~dp0"
REM Varsayilan root: ..\app\src\main\res\raw
set "ROOT_FOLDER=%SCRIPT_DIR%..\app\src\main\res\raw"

REM Istersen komut satirindan farkli klasor verebilirsin:
REM convert-wav-to-mono.bat "C:\yol\...\raw"
if not "%~1"=="" (
    set "ROOT_FOLDER=%~1"
)

REM ffmpeg ve ffprobe PATH'teyse direkt kullan; degilse buraya tam yol yazabilirsin
set "FFMPEG_CMD=ffmpeg"
set "FFPROBE_CMD=ffprobe"

echo Klasor      : %ROOT_FOLDER%
echo ffmpeg komut: %FFMPEG_CMD%
echo ffprobe komut: %FFPROBE_CMD%
echo.

if not exist "%ROOT_FOLDER%" (
    echo HATA: Klasor bulunamadi: %ROOT_FOLDER%
    echo.
    pause
    exit /b 1
)

REM ffmpeg kontrol
echo ffmpeg kontrol ediliyor...
%FFMPEG_CMD% -version >nul 2>&1
if errorlevel 1 (
    echo HATA: ffmpeg calistirilamadi. Lurfen ffmpeg.exe yolunu veya PATH ayarini kontrol edin.
    echo.
    pause
    exit /b 1
)

REM ffprobe kontrol
echo ffprobe kontrol ediliyor...
%FFPROBE_CMD% -version >nul 2>&1
if errorlevel 1 (
    echo HATA: ffprobe calistirilamadi. Lurfen ffprobe.exe yolunu veya PATH ayarini kontrol edin.
    echo.
    pause
    exit /b 1
)

echo.
echo UYARI:
echo   - Zaten mono (1 kanal) olan .wav dosyalara dokunulmayacak.
echo   - Sadece stereo (2 kanal) dosyalar mono'ya donusturulecek.
echo   - Her dosya yerinde (ismi degismeden) guncellenecek.
echo.

REM WAV dosyalarini rekursif tara
set "COUNT=0"
for /r "%ROOT_FOLDER%" %%F in (*.wav) do (
    set /a COUNT+=1
)

if "%COUNT%"=="0" (
    echo Hic .wav dosyasi bulunamadi.
    echo.
    pause
    exit /b 0
)

echo Bulunan .wav dosyasi sayisi: %COUNT%
echo.

for /r "%ROOT_FOLDER%" %%F in (*.wav) do (
    call :PROCESS_FILE "%%F"
)

echo Islem tamamlandi.
echo.
pause

endlocal
exit /b 0

:PROCESS_FILE
setlocal enabledelayedexpansion
set "INPUT=%~1"

echo Isleniyor: !INPUT!

REM ffprobe ile kanal sayisini ogren
set "CHANNELS="
for /f "delims=" %%C in ('%FFPROBE_CMD% -v error -select_streams a:0 -show_entries stream^=channels -of default^=nk^=1:nw^=1 "!INPUT!"') do (
    set "CHANNELS=%%C"
)

if "!CHANNELS!"=="1" (
    echo   Zaten mono 1 kanal, atlandi.
    echo.
    endlocal & goto :EOF
)

set "TEMP=%~dpn1.mono.tmp.wav"

REM 1) ffmpeg ile mono'ya downmix (L ve R ortalamasi)
REM    pan=mono|c0=0.5*c0+0.5*c1  -> Audacity "Stereo to mono" benzeri
"%FFMPEG_CMD%" -y -i "!INPUT!" -ac 1 -af "pan=mono|c0=0.5*c0+0.5*c1" "!TEMP!" >nul 2>&1
if errorlevel 1 (
    echo   HATA: ffmpeg donusumu basarisiz, dosya atlandi.
    if exist "!TEMP!" del /q "!TEMP!" >nul 2>&1
    echo.
    endlocal & goto :EOF
)

REM Orijinal dosyayi sil ve gecici mono dosyayi orijinal isme tasima
del /q "!INPUT!" >nul 2>&1
move /y "!TEMP!" "!INPUT!" >nul

echo   OK: Stereo ^> mono donusturuldu.
echo.

endlocal
goto :EOF

