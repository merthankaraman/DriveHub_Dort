## DriveHub Dort – ProGuard/R8 rules
#
# Not: R8, manifest'te kayıtlı Activity/Service/BroadcastReceiver sınıflarını
# zaten otomatik olarak korur (silmez). Bu dosyada sadece özel durumları
# tanımlıyoruz.

# Eğer ileride @Keep anotasyonu kullanırsan, bu üyeler olduğu gibi korunur.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

## İsteğe bağlı: stack trace debug için satır numaralarını koru
#-keepattributes SourceFile,LineNumberTable

## Satır numaralarını koruyup sadece kaynak dosya adını gizlemek için
#-renamesourcefileattribute SourceFile
