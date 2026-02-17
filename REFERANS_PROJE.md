# Referans Proje: MG4 Custom Launcher

**URL:** https://github.com/adammcdonagh/MG4-Custom-Launcher

## Bu Projeden Öğrenilecekler

Bu repo MG4 EH32 için custom launcher yapmış. Bizim yaşadığımız "vehiclesetting servisi bulunamıyor" sorununu muhtemelen çözmüşler.

### İncelenmesi Gerekenler

#### 1. AndroidManifest.xml
- `android:sharedUserId="android.uid.system"` kullanıyor mu?
- Hangi system permission'ları eklenmiş?
- `android:protectionLevel="signature"` var mı?

#### 2. Sistem İmzalama
- Platform key ile nasıl imzalanmış?
- `signapk.jar` kullanılmış mı?
- `platform.x509.pem` ve `platform.pk8` nereden alınmış?

#### 3. Yükleme Prosedürü
- `/system/priv-app/` altına mı yüklenmiş?
- `/system/app/` mı kullanılmış?
- `adb root` ve `adb remount` gerekli mi?

#### 4. Build Yapılandırması
- `build.gradle` içinde özel signing config var mı?
- Gradle task'ları eklenmiş mi?
- ProGuard/R8 rules'lar var mı?

#### 5. Binder Erişimi
- ServiceManager kullanımı nasıl?
- AIDL dosyaları kullanılmış mı?
- CarPropertyManager alternatifi var mı?

#### 6. SELinux
- Özel policy dosyası var mı?
- `seapp_contexts` düzenlemesi gerekli mi?
- `file_contexts` değişikliği var mı?

---

## Claude Code'a Görev

Bu repo'yu analiz et ve bizim projede şu değişiklikleri yap:

1. **AndroidManifest.xml güncellemesi**
   - Gerekli system permission'ları ekle
   - sharedUserId ayarla

2. **Signing yapılandırması**
   - Platform key ile imzalama scripti hazırla
   - build.gradle'a signing config ekle

3. **Yükleme scripti**
   - `install.sh` dosyası oluştur
   - Root, remount, push, reboot adımları

4. **SELinux çözümü**
   - Gerekirse policy düzenleme komutları
   - Alternatif yaklaşımlar

5. **README güncelleme**
   - Sistem imzalama adımlarını ekle
   - Root gereksinimlerini belirt

---

## Beklenen Sonuç

Bu adımlar tamamlandıktan sonra:
- `ServiceManager.getService("vehiclesetting")` → başarılı
- `MG4_SERVICE: vehiclesetting: ✅`
- Sport butonuna basınca sürüş modu değişecek

---

## Alternatif: Root Olmadan Çözüm

Eğer repo'da root gerektirmeyen bir yöntem varsa (örneğin CarPropertyManager API), onu tercih et.
