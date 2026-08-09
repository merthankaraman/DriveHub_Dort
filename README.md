# DriveHub Dort

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
DriveHubDort/
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/example/DriveHubDort/
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

## ⚡ Hızlı Başlangıç (Yeni Kullanıcı)

Projeyi ilk kez kuracaksan sırasıyla şunları yapman gerekiyor:

1. **Android Studio** kur (Meerkat 2025.1.1+) → [developer.android.com/studio](https://developer.android.com/studio)
2. Android Studio'da **Android SDK** + **Build Tools** kur (SDK Manager'dan)
3. **ADB**'yi PATH'e ekle (Android Studio kurulumunda genellikle otomatik eklenir)
4. Projeyi klonla veya ZIP olarak indir
5. Android Studio'da projeyi aç → **Build > Build APK**
6. Aracı USB ile bilgisayara bağla, `adb devices` ile bağlantıyı doğrula
7. `tools/sign_and_install.bat` dosyasını çift tıkla → imzalar ve araca yükler
8. Uygulamayı araçta aç → **WRITE_SETTINGS** iznini ver

> Script (`sign_and_install.bat`) platform key dosyalarını otomatik bulur, SDK'yı otomatik bulur, taşınabilirdir — bilgisayara özel bir ayar gerektirmez.

---

## 🚀 Kurulum

### Gereksinimler

- Android Studio Meerkat (2025.1.1+)
- JDK — Android Studio ile birlikte gelir (`jbr/` klasörü)
- ADB bağlantısı (USB veya Wi-Fi)
- MG4 EH32 aracı
- Platform key dosyaları: `platform.pk8`, `platform.x509.pem` (`tools/` klasöründe mevcut)

---

### Adım 1 — Derleme

Android Studio'da:

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

---

### Adım 2 — ADB Bağlantısı

APK'yı araca göndermek için ADB bağlantısı gerekir.

**USB ile:**
```
adb devices
```
Cihaz listede görünüyorsa hazırsın.

**Wi-Fi ile (araç Wi-Fi'a bağlıyken):**
```
adb connect 192.168.x.x:5555
adb devices
```
> Araç IP adresini araç ayarlarından öğren: Ayarlar → Ağ → Wi-Fi → Bağlı ağ detayı

---

### Adım 3 — İmzalama ve Araca Yükleme

Proje kökündeki `tools/sign_and_install.bat` dosyasını **çift tıklayarak** çalıştır.

Script otomatik olarak şunları yapar:
1. `app-debug.apk` dosyasını `platform.pk8` + `platform.x509.pem` ile imzalar
2. Çıktıyı `app-debug-signed.apk` olarak kaydeder
3. ADB ile araçtaki eski sürümü kaldırır (`adb uninstall com.drivehub.dort`)
4. Yeni imzalı APK'yı yükler (`adb install app-debug-signed.apk`)

**Manuel yapmak istersen (script yerine):**
```bash
# 1. İmzala
java -jar apksigner.jar sign ^
    --key platform.pk8 --cert platform.x509.pem ^
    --out app-debug-signed.apk ^
    app-debug.apk

# 2. Eski sürümü kaldır (şifre değişikliği varsa zorunlu)
adb uninstall com.drivehub.dort

# 3. Yükle
adb install app-debug-signed.apk
```

> **Önemli:** Normal `adb install app-debug.apk` (imzasız) ile yükleme yapma.
> Araç servisleri platform key imzası olmayan APK'yı reddeder.

---

### Neden Platform Key?

MG4 EH32 araç servisleri (`vehiclesetting`, `aircondition`) SELinux politikası gereği
sadece sistem uygulamalarına görünür. APK, araç firmware'iyle aynı AOSP platform test
key'iyle imzalanmalıdır. Aksi hâlde `ServiceManager.getService()` `null` döner.

Platform key bilgileri:
- Seri: `b3998086d056cffa`
- SHA-1: `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`
- Keystore: `tools/platform.p12` (şifre: `android`)

---

### Adım 4 — İzin Verme

Uygulama ilk açılışta **WRITE_SETTINGS** izni ister.
Açılan ekranda uygulamayı bulup izni manuel olarak aktif edin.

---

## 🎮 Kullanım

### Direksiyon Hardkey Kısayolları

| Tuş | Davranış |
|---|---|
| ★ Favori tuşu (keycode 17) | Sürüş modunu döngüsel değiştirir: ECO → NORMAL → SPORT → ECO |
| Vol↑ + Vol↓ (300ms içinde) | Müziği oynat / duraklat |

> **Not:** Volume tuş kodları (24/25) araç firmware'ine göre farklı olabilir.
> Logcat'te `HARDKEY >>>` satırlarını izleyerek gerçek kodları öğren.

### Manuel Kontrol (Uygulama Ekranı)

| Buton | İşlev |
|---|---|
| Eco / Normal / Sport / Kar | Direkt mod seçimi |
| Döngüsel Mod Değiştir | ★ tuşu ile aynı işlev |
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

## 🙏 Teşekkürler

OTA (GitHub üzerinden sürüm kontrolü, indirme ve SHA-256 doğrulama) kodu için
[jamakr4](https://github.com/jamakr4) / [MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App)
projesine teşekkürler. Bu kısımdaki akış o projeden uyarlanmıştır.

---

## 📄 Lisans

MIT License — Kişisel ve eğitim amaçlı kullanım serbesttir.

---

> **Not:** Bu proje tersine mühendislik çalışmaları sonucunda elde edilen  
> bilgilere dayanmaktadır. SAIC Motor resmi bir API sunmamaktadır.  
> Kullanım riski kullanıcıya aittir.