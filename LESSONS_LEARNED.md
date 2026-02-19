# MG4 EH32 Android Automotive — Lessons Learned

> Bu doküman, MG4 EH32 araca etki eden bir Android uygulaması geliştirirken öğrenilen tüm kritik bilgileri içerir.
> Yeni bir proje başlarken bu dosyayı Claude Code'a vererek sıfırdan başlamak yerine doğrudan çalışan bir temelden başlayabilirsiniz.

---

## 1. Hedef Platform

| Özellik | Değer |
|---|---|
| Araç | MG4 EH32 |
| OS | Android Automotive OS (custom SAIC build) |
| Ekran | 1920×720 px landscape |
| Android HAL | `android.hardware.automotive.vehicle@2.0` |
| Min SDK | 28 (Android 9) |
| Target SDK | 36 |
| Dil | Java |

---

## 2. En Kritik Keşif: SELinux + Platform Signature

### Problem
Normal Android uygulaması olarak yüklenince `ServiceManager.getService("vehiclesetting")` her zaman `null` döner.

### Neden
`vehiclesetting` ve `aircondition` servisleri SELinux politikaları gereği yalnızca `android.uid.system` UID'ine sahip uygulamalara görünür. Normal user-space app bu UID'e sahip olamaz.

### Çözüm (Root veya /system/priv-app GEREKMİYOR)

**Adım 1 — AndroidManifest.xml'e ekle:**
```xml
<manifest
    android:sharedUserId="android.uid.system"
    android:persistent="true"
    ...>
```

**Adım 2 — APK'yı AOSP platform test key ile imzala:**
```bash
# Derleme
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
.\gradlew assembleDebug

# İmzalama (platform.p12 şifresi: android)
apksigner sign --ks platform.p12 --ks-key-alias platform --ks-pass pass:android app-debug.apk

# Yükleme
adb install -r app-debug-platform-signed.apk
```

### Neden AOSP Test Key Çalışıyor?
MG4 EH32 firmware'indeki TÜM sistem APK'ları (launcher, vehiclesettings, HVAC vb.) açık AOSP platform test key ile imzalanmış:
- **SHA-1:** `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`
- Key dosyaları: `platform.x509.pem`, `platform.pk8`, `platform.p12` (şifre: `android`)
- İndirme: `https://github.com/aosp-mirror/platform_build/tree/master/target/product/security/`
- Referans repo: `adammcdonagh/MG4-Custom-Launcher` (aynı keşfi daha önce yapmış)

---

## 3. Servis Erişimi ve Binder Protokolü

### Mevcut Servisler

| Servis Adı | Descriptor | Görev |
|---|---|---|
| `vehiclesetting` | `com.saicmotor.sdk.vehiclesettings.IVehicleSettingService` | Sürüş modu, regen, tek pedal |
| `aircondition` | `com.saicmotor.sdk.vehiclesettings.IAirConditionService` | Direksiyon ısıtma, koltuk ısıtma |

### Doğru Binder Parcel Formatı
```java
Parcel data = Parcel.obtain();
Parcel reply = Parcel.obtain();
try {
    data.writeInterfaceToken("com.saicmotor.sdk.vehiclesettings.IVehicleSettingService");
    data.writeInt(0x01000000); // Area ID — ZORUNLU, yoksa araç reddeder
    data.writeInt(1);          // Count — ZORUNLU, yoksa araç reddeder
    data.writeInt(value);      // Ayarlanacak değer
    data.writeFloatArray(new float[0]);
    data.writeByteArray(new byte[0]);
    binder.transact(TX_ID, data, reply, 0);
    reply.readException();
} finally {
    data.recycle();
    reply.recycle();
}
```

**Kritik:** `writeInt(AREA_ID)` ve `writeInt(COUNT)` olmadan araç transaction'ı reddeder. Bu ikisi kaçırılınca yüzlerce deneme boşa gidebilir.

### Transaction ID'ler (vehiclesetting)

| Fonksiyon | TX ID | Değer Aralığı |
|---|---|---|
| `setDriveMode` | 151 | 2=Eco, 3=Normal, 4=Sport, 6=Kar, 7=Özel |
| `setRegenerativeLevel` | 180 | 0=Düşük, 1=Orta, 2=Yüksek, 3=Adaptif |
| `setOnePedalConfigCode` | 181 | 0=Kapalı, 1=Açık |
| `setRegenerativeBrakeSwitch` | 182 | 0=Kapalı (regen tamamen kapat), 1=Açık |

### Transaction ID (aircondition)

| Fonksiyon | TX ID | Değer |
|---|---|---|
| `setSteeringWheelHeat` | 52 | 0=Kapalı, 1=Açık |

---

## 4. CarPropertyManager ile Alternatif Erişim (Reflection)

Binder'ın yedeği olarak `android.car.Car` + `CarPropertyManager` kullanılabilir. Bu API doğrudan import edilemez (AOSP system library), reflection gerekir:

```java
Class<?> carClass = Class.forName("android.car.Car");
Method createCarMethod = carClass.getMethod("createCar", Context.class);
Object car = createCarMethod.invoke(null, context);

Class<?> carPropertyManagerClass = Class.forName("android.car.hardware.property.CarPropertyManager");
Object cpm = ((Car) car).getCarManager(Car.PROPERTY_SERVICE);
```

### Property ID'ler (CarPropertyManager için)

| Özellik | Property ID (hex) | Property ID (decimal) |
|---|---|---|
| Drive Mode | `0x2140a17c` | 557,983,100 |
| Regen Level | `0x2140a191` | 557,983,121 |
| One Pedal | `0x2140a193` | 557,983,123 |
| Steering Heat | `0x1540253a` | 354,499,898 (area: 0x75) |
| Seat Heat L | `0x15402513` | 354,498,835 |
| Seat Heat R | `0x15402514` | 354,498,836 |

**Önemli:** `CLAUDE.md`'deki eski değerler hatalıydı. Yukarıdaki logcat'ten doğrulanan değerler doğrudur.

---

## 5. Drive Mode Değerleri (Araç Doğrulamalı)

**CLAUDE.md'deki eski tablo hatalıydı.** Gerçek değerler:

| Mod | Değer |
|---|---|
| Eco | 2 |
| Normal | 3 |
| Sport | 4 |
| Kar (Snow) | 6 |
| Özel (Custom) | 7 |

---

## 6. Regen Level — OFF Modu

Regen'i tamamen kapatmak için sadece level değeri göndermek yetmez. Ayrı bir "switch" transaction'ı gönderilmeli:

```java
// Regen'i kapat
binder.transact(182, data_with_value_0, reply, 0); // setRegenerativeBrakeSwitch = OFF

// Regen'i aç ve level ayarla
binder.transact(182, data_with_value_1, reply, 0); // setRegenerativeBrakeSwitch = ON
binder.transact(180, data_with_level, reply, 0);   // setRegenerativeLevel = level
```

`RegenLevel.OFF` enum değeri için sentinel `99` kullanılıyor (araç tarafında geçersiz değer) — kod bu durumu özel olarak ele alıp TX 182 gönderiyor.

---

## 7. Hardkey Entegrasyonu (Direksiyon Tuşları)

### Broadcast Adı
```
com.saic.keyevent.hardkey.report
```

### Extra Anahtarları (Logcat'ten Doğrulanan)
```java
int keyCode  = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1);
boolean down = intent.getBooleanExtra("android.intent.extra.hardkey.down", false);
boolean longPress = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false);
```

### Bilinen Keycode'lar

| Tuş | Keycode |
|---|---|
| Sol Star (*) | 17 |
| Vol Up | 24 |
| Vol Down | 25 |

### Hardkey Mantığı (Mevcut Uygulama)
- **Star, long press (≥2000ms):** One Pedal'i aktifleştirir. Komutu iki kez gönderir (250ms arayla) — araç bazen ilk komutu görmezden geldiği için.
- **Star, short press:** Eğer One Pedal aktifse kapatır; değilse hiçbir şey yapmaz.
- **Vol Up + Vol Down (300ms içinde):** Medya oynatmayı toggle eder.

### Receiver Kaydı (Android 13+)
```java
IntentFilter filter = new IntentFilter("com.saic.keyevent.hardkey.report");
registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
```
`RECEIVER_EXPORTED` flag'i Android 13'te zorunlu hale geldi, unutulursa receiver çalışmaz.

---

## 8. Servis Mimarisi

### Neden Foreground Service?
- Sistem tarafından öldürülmez (araç açık olduğu sürece çalışır)
- Hardkey receiver'ı barındırır
- Boot'ta otomatik başlar

### Servis Başlatma (START_STICKY)
```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    // komut işle...
    return START_STICKY; // sistem kill etse de yeniden başlat
}
```

### Boot'ta Otomatik Başlatma
```java
// BootReceiver — 4 farklı boot action dinlenmeli:
android.intent.action.BOOT_COMPLETED
android.intent.action.LOCKED_BOOT_COMPLETED
android.intent.action.QUICKBOOT_POWERON        // Bazı cihazlar
com.htc.intent.action.QUICKBOOT_POWERON        // Eski HTC kaynaklı
```

Manifest'te `android:persistent="true"` da gerekli — sistem app process'ini canlı tutar.

---

## 9. AndroidManifest.xml Özeti

```xml
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="android.uid.system"
    android:persistent="true">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.MEDIA_CONTENT_CONTROL" />
    <uses-permission android:name="android.car.permission.CAR_ENERGY" />
    <uses-permission android:name="android.car.permission.CAR_POWERTRAIN" />
    <uses-permission android:name="android.car.permission.CAR_VENDOR_EXTENSION" />
    <uses-permission android:name="android.car.permission.CONTROL_CAR_CLIMATE" />

    <!-- Automotive feature — required=false: geliştirme sırasında PC'de de çalışır -->
    <uses-feature android:name="android.hardware.type.automotive" android:required="false" />

    <application ...>

        <service
            android:name=".service.MG4ControlService"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vehicle_control" />
        </service>

        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="true"
            android:enabled="true">
            <intent-filter android:priority="1000">
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

---

## 10. Build ve İmzalama Workflow

### Tam Komut Sırası
```bash
# 1. Temizle ve derle
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
.\gradlew clean assembleDebug

# 2. APK kopyala ve imzala
set APKSIGNER=C:\Users\<kullanici>\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat
copy app\build\outputs\apk\debug\app-debug.apk app\build\outputs\apk\debug\app-debug-platform-signed.apk
%APKSIGNER% sign --ks platform.p12 --ks-key-alias platform --ks-pass pass:android app\build\outputs\apk\debug\app-debug-platform-signed.apk

# 3. Araca yükle
adb install -r app\build\outputs\apk\debug\app-debug-platform-signed.apk
```

### Signing Keystore Bilgileri
| Alan | Değer |
|---|---|
| Dosya | `platform.p12` (proje kökünde) |
| Alias | `platform` |
| Şifre | `android` |
| Tip | PKCS12 |

---

## 11. Reflection Zorunluluğu

`android.car.*` ve `android.os.ServiceManager` sınıfları normal Android SDK'da mevcut değil — bunlar AOSP/OEM sistem kütüphaneleri. Bu yüzden reflection gerekli:

```java
// ServiceManager (Binder erişimi)
Class<?> smClass = Class.forName("android.os.ServiceManager");
Method getService = smClass.getMethod("getService", String.class);
IBinder binder = (IBinder) getService.invoke(null, "vehiclesetting");

// Car (CarPropertyManager erişimi)
Class<?> carClass = Class.forName("android.car.Car");
```

Alternatif: Projeye `android.car.jar` veya AOSP sistem stubs eklenirse reflection'dan kaçınılabilir. Ancak reflection da çalışır ve daha taşınabilir.

---

## 12. Logcat Filtreleme

### Faydalı Filtreler
```
tag:MG4_SERVICE | tag:MG4_HW | tag:MG4_BOOT
```

### Gürültülü Tag'leri Gizle
```
-tag:CarBMSManager -tag:SaicAdapte -tag:CAR.HAL -tag:IPCLSERVICE -tag:TboxLteManager -tag:radio_hw -tag:chatty
```

### Kritik Log Satırları
```
MG4_HW: vehiclesetting service: CONNECTED      ← Binder çalışıyor
MG4_HW: vehiclesetting service: NOT FOUND       ← İmza/sharedUserId sorunu
MG4_HW: Drive mode set: Sport (4)              ← Komut gönderildi
MG4_BOOT: Service started on boot              ← Boot receiver çalıştı
```

---

## 13. Debug Komutları

```bash
# Çalışan servisleri listele
adb shell service list | grep vehicle

# SELinux ihlallerini gör
adb shell logcat -b events | grep avc

# Sistem servisi durumu
adb shell dumpsys vehiclesetting

# Uygulama loglarını izle (gerçek zamanlı)
adb logcat -s MG4_SERVICE MG4_HW MG4_BOOT

# APK imzasını doğrula
apksigner verify --verbose app-debug-platform-signed.apk
```

---

## 14. Yaygın Hatalar ve Çözümleri

| Hata | Neden | Çözüm |
|---|---|---|
| `vehiclesetting: null` | Platform key yok veya sharedUserId eksik | `android:sharedUserId="android.uid.system"` + platform key imzası |
| Transaction kabul edilmiyor | AREA_ID veya COUNT eksik | `data.writeInt(0x01000000)` ve `data.writeInt(1)` eklenmeli |
| Receiver çalışmıyor | Android 13'te `RECEIVER_EXPORTED` unutulmuş | `registerReceiver(r, filter, Context.RECEIVER_EXPORTED)` |
| Boot'ta başlamıyor | `RECEIVE_BOOT_COMPLETED` izni eksik veya manifest yanlış | Permission + BootReceiver manifest kaydı |
| Drive mode değerleri yanlış | Eski/belgelenmiş değerler hatalı | Logcat'ten doğrulanan değerleri kullan (Eco=2, Snow=6) |
| Regen OFF çalışmıyor | Sadece level göndermek yeterli değil | TX 182 (switch) + TX 180 (level) ayrı ayrı gönder |
| Hardkey gelmiyor | Broadcast filter scope | `RECEIVER_EXPORTED` + doğru action string |
| APK yüklenmiyor | İmzalanmamış veya eski APK üstüne farklı imza | `adb install -r` + platform-signed APK kullan |
| Foreground service hata | `foregroundServiceType` eksik (Android 14+) | `specialUse` type + property tanımla |

---

## 15. Proje Dosya Yapısı (Referans)

```
ProjectRoot/
├── platform.x509.pem          # AOSP platform sertifikası
├── platform.pk8               # AOSP platform private key
├── platform.p12               # PKCS12 keystore (şifre: android)
├── app/src/main/
│   ├── java/com/example/<pkg>/
│   │   ├── ui/
│   │   │   └── MainActivity.java       # UI + servis başlatma
│   │   ├── service/
│   │   │   └── MG4ControlService.java  # Foreground service + hardkey
│   │   ├── receiver/
│   │   │   └── BootReceiver.java       # Boot'ta otomatik başlatma
│   │   ├── hardware/
│   │   │   └── MG4Hardware.java        # Binder IPC katmanı
│   │   └── model/
│   │       ├── DriveMode.java          # Enum + değerler
│   │       └── RegenLevel.java         # Enum + değerler
│   ├── res/layout/
│   │   └── activity_main.xml           # 1920×720 landscape layout
│   └── AndroidManifest.xml
└── app/build.gradle
```

---

## 16. Yeni Proje İçin Checklist

- [ ] `android:sharedUserId="android.uid.system"` manifest'e eklendi
- [ ] `android:persistent="true"` manifest'e eklendi
- [ ] `platform.p12` proje kökünde mevcut
- [ ] Build sonrası `apksigner` ile platform key imzalandı
- [ ] `adb install -r` ile **signed** APK yüklendi (unsigned değil)
- [ ] Foreground service `foregroundServiceType="specialUse"` tanımlı
- [ ] BootReceiver 4 farklı boot action dinliyor
- [ ] `RECEIVER_EXPORTED` flag hardkey receiver'a eklendi
- [ ] Binder parcel'da AREA_ID (`0x01000000`) ve COUNT (`1`) var
- [ ] Regen OFF için TX 182 ayrıca gönderiliyor
- [ ] Drive mode değerleri logcat doğrulamalı (Eco=2, Snow=6)
- [ ] Logcat filtreleri ayarlandı

---

## 17. Başka SAIC/MG Araçlara Uyarlama

Bu araştırma MG4 EH32 için yapıldı. Farklı MG modelleri veya farklı EH sürümleri için:

1. **Transaction ID'leri doğrula:** APK'ları jadx ile decompile et, aynı servis descriptor'ını kullanan TX ID'leri bul.
2. **Değerleri doğrula:** Logcat'ten property değişimlerini izleyerek gerçek değerleri öğren.
3. **Platform key'i doğrula:** `apksigner verify --verbose` ile araçta yüklü APK'ların imzasını kontrol et. AOSP test key değilse farklı bir key gerekebilir.
4. **Broadcast action'ı doğrula:** `adb shell logcat | grep hardkey` ile araca bas ve gerçek action string'i gör.

---

*Bu doküman MG4 EH32 projesinde elde edilen pratik deneyimlere dayanmaktadır. Tüm değerler araç üzerinde test edilerek doğrulanmıştır.*
