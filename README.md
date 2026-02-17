# MG4 Controller

> MG4 EH32 (SAIC Motor) Android Automotive multimedya sistemi üzerinde çalışan,  
> direksiyon hardkey tuşlarını dinleyen ve araç ayarlarını kontrol eden yardımcı uygulama.

---

## 📋 Proje Hakkında

MG4 EH32'nin Android 14 tabanlı infotainment sisteminde çalışan bu uygulama;  
direksiyon üzerindeki fiziksel tuşları (Hardkey 66) dinleyerek sürüş modunu,  
rejeneratif frenleme seviyesini, tek pedal modunu ve direksiyon ısıtmayı kontrol eder.

### Neden Bu Uygulama?

MG4'ün fabrika arayüzünde bazı ayarlara ulaşmak birden fazla dokunuş gerektiriyor.  
Bu uygulama sayesinde direksiyon üzerindeki favori tuşuna (Hardkey 66) tek basışla  
sürüş modu değiştirilebiliyor.

---

## ⚙️ Teknik Altyapı

| Bileşen | Detay |
|---|---|
| Araç | MG4 EH32 (SAIC Motor) |
| İşletim Sistemi | Android 14 (Android Automotive) |
| Haberleşme | SAIC Proprietary Binder Servisleri |
| Geliştirme | Android Studio Panda / Java |
| Min SDK | API 29 (Android 10) |

### Kullanılan Servisler

| Servis | Descriptor | Kontrol Edilen Özellikler |
|---|---|---|
| `vehiclesetting` | `com.saicmotor.sdk.vehiclesettings.IVehicleSettingService` | Sürüş Modu, Regen, Tek Pedal |
| `aircondition` | `com.saicmotor.sdk.vehiclesettings.IAirConditionService` | Direksiyon Isıtma |

### Property ID'leri

| Özellik | Property ID | Değerler |
|---|---|---|
| Sürüş Modu | `557883772` | Kar:6, Eco:2, Normal:3, Sport:4, Özel:7 |
| Regen Seviyesi | `557883793` | Düşük:1, Orta:2, Yüksek:3, Adaptif:4 |
| Tek Pedal | `557883795` | Açık:1, Kapalı:0 |

### MG4 Binder Protokolü (Kritik)

```
writeInterfaceToken(TOKEN)   → Binder güvenlik kontrolü
writeInt(16777216)           → Area ID (0x01000000) — global alan
writeInt(1)                  → Count — ZORUNLU, eksik olursa araç reddeder!
writeInt(value)              → Asıl değer
writeFloatArray(new float[0])→ Boş float listesi
writeByteArray(new byte[0])  → Boş byte listesi
```

---

## 📁 Proje Yapısı

```
MG4_2/
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/example/mg4_2/
        ├── hardware/
        │   └── MG4Hardware.java        # Binder haberleşme katmanı
        ├── model/
        │   ├── DriveMode.java          # Sürüş modu enum
        │   └── RegenLevel.java         # Regen seviyesi enum
        ├── service/
        │   └── MG4ControlService.java  # Foreground service + dinamik receiver
        ├── receiver/
        │   └── BootReceiver.java       # Araç açılışında otomatik başlatma
        └── ui/
            └── MainActivity.java       # Kontrol arayüzü
```

---

## 🚀 Kurulum

### Gereksinimler
- Android Studio Panda (2025.3.1+)
- ADB bağlantısı (USB veya Wi-Fi)
- MG4 EH32 aracı

### APK Derleme

```bash
# Android Studio'da
Build → Build Bundle(s) / APK(s) → Build APK(s)

# veya terminal ile
./gradlew assembleDebug
```

### Araca Yükleme

```bash
# USB bağlantısı
adb install app/build/outputs/apk/debug/app-debug.apk

# Wi-Fi ADB
adb connect 192.168.x.x
adb install app/build/outputs/apk/debug/app-debug.apk
```

### İzin Verme

Uygulama ilk açılışta **WRITE_SETTINGS** izni ister.  
Açılan ekranda uygulamayı bulup izni manuel olarak aktif edin.

---

## 🎮 Kullanım

### Hardkey 66 (Direksiyon Favori Tuşu)
Direksiyondaki yıldız/favori tuşuna her basışta sürüş modu döngüsel değişir:

```
ECO → NORMAL → SPORT → ECO → ...
```

### Manuel Kontrol (Uygulama Ekranı)
| Buton | İşlev |
|---|---|
| Eco / Normal / Sport / Kar | Direkt mod seçimi |
| Döngüsel Mod Değiştir | Hardkey 66 ile aynı işlev |
| Regen Seviyesi Değiştir | Düşük → Orta → Yüksek → Adaptif |
| Tek Pedal Aç/Kapat | One-pedal modu |
| Direksiyon Isıt | Direksiyon ısıtmayı açar |
| Binder Test | Servis bağlantısını kontrol eder |

---

## 🔍 Debug

### Servis Kontrolü
```bash
adb shell service list | grep -i vehicle
adb shell service list | grep -i air
```

### Canlı Log İzleme
```bash
adb logcat -s MG4_SERVICE MG4_HW MG4_BOOT
```

### SELinux Engeli Kontrolü
```bash
adb shell logcat | grep "avc.*denied"
```

### Manuel Binder Testi
```bash
# Normal moda geç (değer: 3)
adb shell service call vehiclesetting 151 i32 16777216 i32 1 i32 3
```

---

## ⚠️ Bilinen Kısıtlamalar

- **Normal APK** olarak çalışır, sistem uygulaması yetkisi gerekmez.
- Bazı Binder çağrıları SELinux kısıtlamasına takılabilir — logcat'te `permission denied` ara.
- UI senkronizasyonu: Yapılan değişiklikler aracın ana ekranında anlık güncellenmeyebilir.
- `vehiclesetting` servis adı araç firmware versiyonuna göre farklı olabilir.

---

## 🗺️ Yol Haritası

- [x] Hardkey 66 dinleme (dinamik BroadcastReceiver)
- [x] Sürüş modu kontrolü (vehiclesetting Binder)
- [x] Regen seviyesi kontrolü
- [x] Tek pedal kontrolü
- [x] Direksiyon ısıtma (aircondition Binder)
- [x] Boot'ta otomatik başlama
- [ ] UI senkronizasyonu (araç widget güncellemesi)
- [ ] Regen seviyesi Hardkey ile kontrol
- [ ] Mevcut mod okuma (getProperty)
- [ ] Araç durumu göstergesi

---

## 📄 Lisans

MIT License — Kişisel ve eğitim amaçlı kullanım serbesttir.

---

> **Not:** Bu proje tersine mühendislik çalışmaları sonucunda elde edilen  
> bilgilere dayanmaktadır. SAIC Motor resmi bir API sunmamaktadır.  
> Kullanım riski kullanıcıya aittir.