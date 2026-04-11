## DriveHub Dort – ProGuard/R8 rules

# Vosk (yerel STT)
-keep class org.vosk.** { *; }
-dontwarn org.jportaudio.**
#
# Not: R8, manifest'te kayıtlı Activity/Service/BroadcastReceiver sınıflarını
# zaten otomatik olarak korur (silmez). Bu dosyada sadece özel durumları
# tanımlıyoruz.

# Telemetri paketi bütünüyle kalsın (Kadran ContentResolver ile okur)
-keep class com.drivehub.dort.telemetry.** { *; }
-dontwarn com.drivehub.dort.telemetry.**

# AndroidX Core: yansıma ile yüklendiği için R8 silebiliyor (CoreComponentFactory)
-keep class androidx.core.app.** { *; }
-dontwarn androidx.core.app.**

# Eğer ileride @Keep anotasyonu kullanırsan, bu üyeler olduğu gibi korunur.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

## İsteğe bağlı: stack trace debug için satır numaralarını koru
#-keepattributes SourceFile,LineNumberTable

## Satır numaralarını koruyup sadece kaynak dosya adını gizlemek için
#-renamesourcefileattribute SourceFile
