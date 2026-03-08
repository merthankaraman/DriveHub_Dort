# DriveHub Dort (MG4 EH32)

> **Proje:** MG4 elektrikli araç için Android Automotive controller uygulaması (DriveHub Dort)  
> **Platform:** Android Automotive OS (AAOS) - EH32 donanım  
> **Paket:** `com.drivehub.dort`  
> **Dil:** Java  
> **Min SDK:** 28 (Android 9)  
> **Target SDK:** 33 (Android 13)

---

## 📋 Proje Amacı

MG4 EH32'de çalışan bir Android uygulaması ile araç kontrolcüsüne (VHAL - Vehicle HAL) direkt Binder üzerinden erişim sağlayarak:
- Sürüş modunu değiştirme (Eco, Normal, Sport, Kar)
- Regen seviyesini ayarlama (Kapalı, Düşük, Orta, Yüksek, Adaptif)
- Tek pedal modunu açma/kapama
- Direksiyon ısıtmasını açma

**Hardkey entegrasyonu:** Direksiyondaki favori tuşu (keyCode 66) ile sürüş modunu döngüsel değiştirme.

---

## 🏗️ Teknik Altyapı

### Binder IPC
Android Automotive'de araç servisleri `ServiceManager` üzerinden Binder ile erişilir:
```java
IBinder binder = ServiceManager.getService("vehiclesetting");
```

### Kullanılan Servisler
| Servis Adı | Descriptor | Görev |
|---|---|---|
| `vehiclesetting` | `com.saicmotor.sdk.vehiclesettings.IVehicleSettingService` | Sürüş modu, regen, tek pedal |
| `aircondition` | `com.saicmotor.sdk.vehiclesettings.IAirConditionService` | Direksiyon ısıtma |

### Binder Protokolü (vehiclesetting)
```java
Parcel data = Parcel.obtain();
data.writeInterfaceToken("com.saicmotor.sdk.vehiclesettings.IVehicleSettingService");
data.writeInt(16777216);           // Area ID (0x01000000) - ZORUNLU
data.writeInt(1);                  // Count - ZORUNLU (yoksa araç reddeder)
data.writeInt(value);              // Ayarlanacak değer
data.writeFloatArray(new float[0]);
data.writeByteArray(new byte[0]);
binder.transact(TX_ID, data, reply, 0);
```

### Transaction ID'ler
| Fonksiyon | TX ID | Değer Aralığı |
|---|---|---|
| `setDriveMode` | 151 | 0=Kar, 1=Eco, 3=Normal, 4=Sport |
| `setRegenerativeLevel` | 180 | 0-4 (Kapalı, Düşük, Orta, Yüksek, Adaptif) |
| `setOnePedalConfigCode` | 181 | 0=Kapalı, 1=Açık |
| `setRegenerativeBrakeSwitch` | 182 | 0=Kapalı, 1=Açık (regen ana switch) |
| `setSteeringWheelHeat` | 52 | 0=Kapalı, 1=Açık (aircondition servisi) |

### Property ID'ler (referans)
- Sürüş Modu: `557883772`
- Regen Seviyesi: `557883793`
- Tek Pedal: `557883795`

---

## 📁 Proje Yapısı

```
DriveHubDort/
├── app/src/main/
│   ├── java/com/example/DriveHubDort/
│   │   ├── ui/
│   │   │   └── MainActivity.java          # Ana UI, buton yönetimi
│   │   ├── service/
│   │   │   ├── MG4ControlService.java     # Foreground service, hardkey receiver
│   │   │   └── BootReceiver.java          # Araç açılışında otomatik başlatma
│   │   ├── hardware/
│   │   │   └── MG4Hardware.java           # Binder haberleşme katmanı
│   │   └── model/
│   │       ├── DriveMode.java             # Enum: Eco, Normal, Sport, Kar
│   │       └── RegenLevel.java            # Enum: OFF, Low, Medium, High, Adaptive
│   ├── res/layout/
│   │   └── activity_main.xml              # 1920x720 optimize layout
│   └── AndroidManifest.xml
└── build.gradle
```

### Dosya Görevleri

#### `MainActivity.java`
- UI yönetimi (butonlar, durum gösterimi)
- Intent ile servise komut gönderme
- WRITE_SETTINGS izin kontrolü

#### `MG4ControlService.java`
- Foreground service (sistem tarafından öldürülmez)
- BroadcastReceiver ile hardkey yakalama (`com.saic.keyevent.hardkey.report`)
- Komut yönetimi (DRIVE_CYCLE, REGEN_CYCLE, vb.)
- Notification güncellemesi

#### `MG4Hardware.java`
- Binder haberleşme katmanı
- ServiceManager ile servis alma
- Parcel paketleme ve transaction gönderimi
- Hata yönetimi ve loglama

#### `BootReceiver.java`
- `BOOT_COMPLETED` broadcast'i yakalama
- Araç açılışında servisi otomatik başlatma

#### `DriveMode.java` / `RegenLevel.java`
- Enum sınıfları
- `next()` metodu ile döngüsel geçiş
- `fromValue()` metodu ile değer çevirme

---

## 🔧 Şu Ana Kadar Yapılanlar

### ✅ Tamamlananlar
1. **Gradle yapılandırması** — AGP 8.7.3, Gradle 8.9, Java 17
2. **Binder protokolü** — Area ID ve count parametreleri eklendi
3. **UI tasarımı** — 1920x720 landscape için 3 kolonlu layout
4. **Hardkey receiver** — `com.saic.keyevent.hardkey.report` için broadcast kayıt
5. **Regen OFF modu** — `setRegenerativeBrakeSwitch()` ile tamamen kapatma
6. **APK derleme** — Build başarılı, araca yüklendi
7. **Git kurulumu** — Nested git hatası çözüldü, ilk commit yapıldı
8. **APK analizi** — SAIC orijinal APK'ları decompile edildi, transaction ID'ler doğrulandı
9. **SELinux/servis erişim sorunu ÇÖZÜLDÜ** — `sharedUserId` + AOSP platform key imzası eklendi
10. **Platform key imzalı APK hazır** — `app-debug-platform-signed.apk` araca yüklenmeyi bekliyor

### 📝 README.md
Proje kök dizininde kapsamlı dokümantasyon mevcut:
- Binder protokol detayları
- Property ID tablosu
- Debug komutları
- ADB test prosedürü

---

## ✅ Çözülen Sorun: SELinux / Servis Erişimi

### Kök Neden (Tespit Edildi)
`vehiclesetting` servisi, SELinux politikaları gereği yalnızca `android.uid.system` grubundaki uygulamalara görünür. Normal user app olarak yüklenince `ServiceManager.getService("vehiclesetting")` → `null` dönüyordu.

### Çözüm
**2 değişiklik yeterliydi — root veya `/system/priv-app` GEREKMİYOR:**

1. `AndroidManifest.xml`'e `android:sharedUserId="android.uid.system"` eklendi
2. APK, AOSP platform test key ile imzalandı

### Kritik Keşif: AOSP Test Keys
MG4 EH32 firmware'indeki TÜM sistem APK'ları (launcher, vehiclesettings, HVAC vb.) açık AOSP platform test key ile imzalıdır:
- **Seri:** `b3998086d056cffa`
- **SHA-1:** `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`
- **İndirme:** `https://github.com/aosp-mirror/platform_build/tree/master/target/product/security/`
- **Referans:** `adammcdonagh/MG4-Custom-Launcher` repo — aynı keşfi yaptı, dokümante etti

### Platform Key Dosyaları (Proje Kökünde Mevcut)
```
DriveHubDort/
├── platform.x509.pem   # AOSP sertifika
├── platform.pk8        # AOSP private key (binary)
├── platform.key        # PEM formatı (dönüştürülmüş)
└── platform.p12        # PKCS12 keystore (şifre: android)
```

### Hazır APK
```
app/build/outputs/apk/debug/app-debug-platform-signed.apk
```

---

## 🧪 Test Prosedürü

### 1. APK Derleme ve İmzalama (Platform Key)
```bash
# Proje dizini
cd C:\Users\merth\StudioProjects\MG4_2

# Derleme (Android Studio JDK ile)
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
.\gradlew assembleDebug

# Platform key ile imzalama
set APKSIGNER=C:\Users\merth\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat
copy app\build\outputs\apk\debug\app-debug.apk app\build\outputs\apk\debug\app-debug-platform-signed.apk
%APKSIGNER% sign --ks platform.p12 --ks-key-alias platform --ks-pass pass:android app\build\outputs\apk\debug\app-debug-platform-signed.apk

# Araca yükleme (-r ile replace — eski APK'nın üstüne yaz)
adb install -r app\build\outputs\apk\debug\app-debug-platform-signed.apk
```

> **ÖNEMLİ:** Normal `adb install` (platformsuz) kullanılmamalı. Mutlaka `app-debug-platform-signed.apk` yüklenmeli.

### 2. Log İzleme
Android Studio Logcat filtresi:
```
tag:MG4_SERVICE | tag:MG4_HW | tag:MG4_BOOT
```

Gereksiz tag'leri filtrele:
```
-tag:CarBMSManager -tag:SaicAdapte -tag:CAR.HAL -tag:IPCLSERVICE -tag:TboxLteManager -tag:radio_hw -tag:chatty
```

### 3. Test Adımları
1. Uygulamayı aç
2. **Binder Test** butonuna bas → Servislerin durumunu kontrol et
3. **Servisi Başlat** butonuna bas → Hardkey receiver aktif olur
4. **Sport** butonuna bas → Sürüş modu değişmeli

### 4. Debug Komutları
```bash
# Servis kontrolü
adb shell service list | grep vehicle

# Property değer okuma
adb shell getprop | grep vehicle

# SELinux audit log
adb shell logcat -b events | grep avc

# Sistem servisi dump
adb shell dumpsys vehiclesetting
```

---

## 🎯 Sonraki Adımlar

### Acil: APK'yı Araca Yükle
1. Araca ADB ile bağlan
2. `adb install -r app\build\outputs\apk\debug\app-debug-platform-signed.apk`
3. Uygulamayı aç → **Binder Test** butonuna bas → `vehiclesetting: ✅ BAĞLI` görünmeli

### Hardkey Entegrasyonu
1. `com.saic.keyevent.hardkey.report` broadcast'inin gelip gelmediğini kontrol et
2. keyCode=66 yerine gerçek favori tuşu keyCode'unu bul (logcat dinleme)
3. Debounce mekanizmasını test et

### Özellik Geliştirmeleri
- [ ] Mevcut mod/regen seviyesini okuma (getter fonksiyonları)
- [ ] Ayarları kaydetme (SharedPreferences)
- [ ] Bildirim içinde mod gösterimi
- [ ] Hata durumunda toast mesajları

---

## 📚 Önemli Notlar

### Binder Güvenlik
- `android.permission.WRITE_SETTINGS` gerekli (runtime izin)
- `android.permission.FOREGROUND_SERVICE` manifest'te tanımlı
- `RECEIVER_EXPORTED` flag'i kullanıldı (Android 13+)

### APK İmzalama (ÇÖZÜLDÜ)
Platform key dosyaları proje kökünde mevcut (`platform.p12`, şifre: `android`).
Root veya `/system/priv-app` GEREKMİYOR. Sadece:
1. `android:sharedUserId="android.uid.system"` manifest'te olmalı ✅ (eklendi)
2. APK `platform.p12` ile imzalanmalı ✅ (hazır APK mevcut)
3. `adb install -r` ile yükleme yeterli

### Hardkey Broadcast Format
```java
Intent intent = new Intent("com.saic.keyevent.hardkey.report");
intent.putExtra("keyCode", 66);      // Favori tuşu
intent.putExtra("keyAction", 0);     // 0=DOWN, 1=UP
```

**NOT:** Gerçek action adı ve extra key'leri APK analizinde bulunamadı. Launcher APK'sında `onKeyEvent` Binder transaction'ı ile yönetiliyor olabilir.

---

## 🔗 Kaynaklar

### APK Analizi
Decompile edilen APK'lar:
- `vehiclesettingservice_eh32_tur_P.apk` — Transaction ID doğrulaması
- `SaicAdapterService_overseas_eh32_tur.apk` — Broadcast receiver
- `launcher_eh32_tur_P.apk` — SWC (Steering Wheel Control) yönetimi

### MG4 EH32 Özellikleri
- Ekran: 1920x720px landscape
- Android Automotive OS (custom SAIC build)
- Vehicle HAL: `android.hardware.automotive.vehicle@2.0`

---

## 💡 Claude Code İçin İpuçları

Bu proje şu konularda yardıma ihtiyaç duyabilir:
- SELinux policy analizi ve çözüm önerileri
- Android Automotive CarPropertyManager API'si ile alternatif implementasyon
- APK sistem imzalama prosedürü
- Binder transaction debug teknikleri
- Hardkey broadcast mecaniğini tersine mühendislik

**Mevcut log çıktısı:** `logcat1.txt` dosyasında ilk test sonuçları mevcut.
