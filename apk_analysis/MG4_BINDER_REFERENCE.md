# MG4 EH32 — Binder / Property ID Referansı
> Kaynak: JADX ile decompile edilmiş binder dosyaları analizi
> Dosyalar: VehicleSettingBinder, VehicleControlBinder, VehicleConditionBinder, VehicleChargingBinder, VehicleScreenBinder, AirConditionBinder
> Ek: `saic_saicmaintenance` Track Mode SDK — `CarSensorManager` sensör config ID’leri (smali); lastik sıcaklığı + teşhis tork indeksleri
> Tarih: 2026-02-19
> Durum: VehicleSettingBinder ✅ | AirConditionBinder ✅ | VehicleControlBinder ✅ | Diğerleri ⏳

---

## 1. Sürüş Kontrolü — `vehiclesetting` servisi

Servis: `CarAdvancedAssistedDrivingManager`
Metot: `setGlobalProperty(Integer.class, PROP_ID, value)` / `getGlobalProperty(Integer.class, PROP_ID)`

| Metot | Property ID (decimal) | Hex | Değer Aralığı |
|---|---|---|---|
| getDriveMode / setDriveMode | 557883772 | 0x2140A17C | 0=Kar, 1=Eco, 3=Normal, 4=Sport |
| getRegenerativeLevel / setRegenerativeLevel | 557883793 | 0x2140A191 | 0=Düşük, 1=Orta, 2=Yüksek, 3=Adaptif |
| getRegenerativeBrakeSwitch / setRegenerativeBrakeSwitch | 557883791 | 0x2140A18F | 0=Kapalı, 1=Açık (ana switch) |
| getRegenerativeLevelDisable | 557883804 | 0x2140A19C | 🆕 Regen OFF için ayrı property |
| getLongerEnduranceDisable | 557883805 | 0x2140A19D | LongerEndurance ile bağlantılı |
| getSignalPedal / setSignalPedal (OnePedal) | 557883795 | 0x2140A193 | 0=Kapalı, 1=Açık |
| getSignalPedalLnhbReg | 557883794 | 0x2140A192 | iç flag (lnhb regen) |
| getBrakeToStandstill / setBrakeToStandstill | 557883796 | 0x2140A194 | ? |
| getLongerEndurance / setLongerEndurance | 557883797 | 0x2140A195 | Uzun menzil modu |
| getLongerEnduranceRecommend | 557883798 | 0x2140A196 | ? |
| getElectricPowertrainLevel / setElectricPowertrainLevel | 557883788 | 0x2140A18C | Motor güç seviyesi |
| getSteeringLevel / setSteeringLevel | 557883789 | 0x2140A18D | Direksiyon ağırlığı |
| getBrakePedalLevel / setBrakePedalLevel | 557883790 | 0x2140A18E | Fren pedal hassasiyeti |
| getAutoHoldSwitch / setAutoHoldSwitch | 557883808 | 0x2140A1A0 | Auto Hold |

### ⚠️ Önemli: Regen OFF Mantığı
```
Normal regen seviyeleri → setRegenerativeLevel(0-3)
Regen tamamen kapalı   → setRegenerativeBrakeSwitch(0)  [prop: 557883791]
Alternatif OFF         → getRegenerativeLevelDisable prop: 557883804 (değer aralığı bilinmiyor)
```

---

## 2. Kapı / Kilit / Pencere — `vehiclecontrol` servisi

Servis: `CarCabinManager` / `CarDoorLockManager` / `CarParkingAssistanceManager`

| Metot | Property ID (decimal) | Hex | Değer / Notlar |
|---|---|---|---|
| getDoorLock / setDoorLock | 289421312 | 0x11402800 | 0=Açık, 1=Kilitli, zone=16777216 |
| getDriveWindow / setDriveWindow | 291518465 | 0x13400C01 | float 0.0–100.0 (0=kapalı, 100=tam açık) |
| getPassengerWindow / setPassengerWindow | 291518466 | 0x13400C02 | float 0.0–100.0 |
| getLeftRearWindow / setLeftRearWindow | 291518467 | 0x13400C03 | float 0.0–100.0 |
| getRightRearWindow / setRightRearWindow | 291518468 | 0x13400C04 | float 0.0–100.0 |
| getElectricTailgateLock / setElectricTailgateLock | 559990308 | 0x21600024 | float, CarDoorLockManager |
| getElectricTailgatePos / setElectricTailgatePos | 559990304 | 0x21600020 | float 0.0–100.0, pozisyon |
| getSunroofSwitch / setSunroofSwitch | 289421320 | 0x11402808 | int, CarCabinManager |
| getSunroofVentilation / setSunroofVentilation | 289421321 | 0x11402809 | int |
| getEspSwitch / setEspSwitch | 289421317 | 0x11402805 | 0=Kapalı, 1=Açık |
| getHdcSwitch / setHdcSwitch | 289421318 | 0x11402806 | 0=Kapalı, 1=Açık (yardımlı iniş) |
| getPdcSwitch / setPdcSwitch | 557889904 | 0x214099B0 | CarParkingAssistanceManager |
| resetVehicleSettings | 289421334 | 0x11402816 | Fabrika ayarlarına sıfırla |

---

## 3. Kapı Kilit Otomasyonu — `vehiclesetting` servisi

Servis: `CarDoorLockManager` / `CarComfortableManager`

| Metot | Property ID (decimal) | Hex | Notlar |
|---|---|---|---|
| getDrivingAutoLock / setDrivingAutoLock | 557893141 | 0x21409A95 | Sürüşte otomatik kilitleme |
| getStallingAutoUnlock / setStallingAutoUnlock | 557893142 | 0x21409A96 | Durdurmada otomatik açma |
| getKeyUnlockMode / setKeyUnlockMode | 625002007 | 0x25402017 | zone=85 |
| getNearfieldUnlockMode / setNearfieldUnlockMode | 625002008 | 0x25402018 | Yakın alan, zone=85 |
| getApproachUnlockMode / setApproachUnlockMode | 557897489 | 0x2140A911 | Yaklaşma ile açma |
| getLeaveAutoLockMode / setLeaveAutoLockMode | 557897488 | 0x2140A910 | Uzaklaşma ile kilitleme |
| getInductiveTailgate / setInductiveTailgate | 557893153 | 0x21409AA1 | İndüktif bagaj açma |
| getInductiveDoorHandle / setInductiveDoorHandle | 557893145 | 0x21409A99 | İndüktif kapı kolu |
| getPowerModeSwitch / setPowerModeSwitch | 557897487 | 0x2140A90F | CarComfortableManager |

---

## 4. Ayna / Koltuk / Konfor — `vehiclesetting` servisi

Servis: `CarComfortableManager`

| Metot | Property ID (decimal) | Hex | Notlar |
|---|---|---|---|
| getLeftRearviewDowndip / setLeftRearviewDowndip | 557897473 | 0x2140A901 | Sol ayna aşağı yatma, 0/1 |
| getRightRearviewDowndip / setRightRearviewDowndip | 557897474 | 0x2140A902 | Sağ ayna aşağı yatma, 0/1 |
| getOuterRearviewFold / setOuterRearviewFold | 557897475 | 0x2140A903 | Dış ayna katlanma, 0/1 |
| getSeatAutoAdjust / setSeatAutoAdjust | 557897482 | 0x2140A90A | Otomatik koltuk ayarı |
| getDriverSeatAutoWlcm / setDriverSeatAutoWlcm | 557897477 | 0x2140A905 | Sürücü karşılama pozisyonu |

---

## 5. Klima / HVAC — `aircondition` servisi

Servis: `CarHvacManager`
Metot: `setCarServiceIntValue(signalValue, zone, propId)` veya `setCarServiceIntValue(signalValue, propId)`

### Ana Kontrol
| Metot | Property ID (decimal) | Hex | Değer / Notlar |
|---|---|---|---|
| setHvacPowerStatus / getHvacPowerStatus | 356525315 | 0x15402503 | Klima ana switch |
| setAutoStatus / getAutoStatus | 356525314 | 0x15402502 | Otomatik mod |
| setAcStatus / getAcSwitch | 356525312 | 0x15402500 | AC kompresör |
| setLoopMode / getLoopMode | 356525319 | 0x15402507 | 0=İç, 1=Dış, 2=Oto |
| setEconStatus / getEconStatus | 356525316 | 0x15402504 | Ekon modu |
| setBlowerDirectionMode | 356525326 | 0x1540250E | Fan yönü |
| setAirVolumeLevel / getAirVolumeLevel | 356525325 | 0x1540250D | Fan hızı, 1-11 |
| setTempDualZoneOn / getTempDualZoneOn | 356525313 | 0x15402501 | İkili bölge sıcaklık |

### Sıcaklık
| Metot | Property ID (decimal) | Zone | Notlar |
|---|---|---|---|
| setDrvTemp / getDrvTemp | 358622475 | 49 | Sürücü, float °C |
| setPsgTemp / getPsgTemp | 358622476 | 68 | Yolcu, float °C |
| getOutCarTemp | 358622481 | — | Dış ortam sıcaklığı |

### Isıtma / Soğutma
| Metot | Property ID (decimal) | Hex | Değer / Notlar |
|---|---|---|---|
| setSteeringWheelHeat / getSteeringWheelHeatLevel | 356525370 | 0x1540253A | ✅ 0=Kapalı, 1-3=Seviye. TX ID=52 (Binder) |
| setDrvSeatHeatLevel / getDrvSeatHeatLevel | 356525331 | 0x15402513 | ✅ 0=Kapalı, 1-3=Seviye |
| setPsgSeatHeatLevel / getPsgSeatHeatLevel | 356525332 | 0x15402514 | ✅ 0=Kapalı, 1-3=Seviye |
| setDrvSeatWindLevel / getDrvSeatWindLevel | 356525349 | 0x15402525 | Sürücü koltuk havalandırma |
| setPsgSeatWindLevel / getPsgSeatWindLevel | 356525350 | 0x15402526 | Yolcu koltuk havalandırma |

### ⚠️ Koltuk Isıtma Kapatma — Toggle Mantığı!
```
setDrvSeatHeatLevel(0)  → DOĞRUDAN ÇALIŞMAZ
Kapatmak için mevcut seviyeye göre setDrvSeatHeatLevel(1) çağrısı tekrarlanır:
  Seviye 1 iken: 1x setDrvSeatHeatLevel(1)
  Seviye 2 iken: 2x setDrvSeatHeatLevel(1)
  Seviye 3 iken: 3x setDrvSeatHeatLevel(1)
⭐ Önerilen: Direkt 0 göndermek CPM üzerinden dene — toggle sadece orijinal AIDL içindir.
```

### Ön/Arka Cam Isıtma
| Metot | Property ID (decimal) | Notlar |
|---|---|---|
| getFrontWindowDefroster | 356525333 | Okuma, 0=Kapalı 1=Açık |
| getBackWindowDefroster | 356525334 | Okuma, 0=Kapalı 1=Açık |
| (set için) value=1 gönder | 356525333 / 356525334 | Toggle çalışır |

### Hava Kalitesi / Diğer
| Metot | Property ID | Notlar |
|---|---|---|
| getPm25Concentration | 356525321 | PM2.5 konsantrasyon |
| getPm25Filter | 356525322 | Filtre durumu |
| getAnionStatus | 356525328 | Negatif iyon |
| getWindOutletCanStatus | 356525353 | — |

---

## 6. Lambalar / Ambiyant Işık — `vehiclesetting` servisi

Servis: `CarLampManager`

| Metot | Property ID (decimal) | Hex | Notlar |
|---|---|---|---|
| getAmbtLightGlbOn / setAmbtLightGlbOn | 557880065 | 0x21409A41 | Ambiyant ışık genel switch |
| getAmbtLightOpenMode / setAmbtLightOpenMode | 557880086 | 0x21409A56 | Açılış modu |
| getAmbtLightDrvMode / setAmbtLightDrvMode | 557880087 | 0x21409A57 | Sürüş modu bağlantısı |
| getAmbtLightColor / setAmbtLightColor | 561025816 | 0x21700B18 | byte[] array, renk |
| getAmbtLightBrightness / setAmbtLightBrightness | 557880073 | 0x21409A49 | Parlaklık |
| getAmbtLightBreathingOn / setAmbtLightBreathingOn | 557880074 | 0x21409A4A | Nefes efekti |
| getAmbtLightWlcmOn / setAmbtLightWlcmOn | 557880083 | 0x21409A53 | Karşılama ışığı |
| getAmbtLightWlcmMode / setAmbtLightWlcmMode | 557880078 | 0x21409A4E | Karşılama modu |
| getWelcomeLightTime / setWelcomeLightTime | 557880081 | 0x21409A51 | Karşılama ışık süresi |
| getHomeLightTime / setHomeLightTime | 557880079 | 0x21409A4F | Ev ışık süresi |
| getAutoMainBeamControl / setAutoMainBeamControl | 557880107 | 0x21409A6B | Otomatik uzun far |
| getLightRearFogSwitch / setLightRearFogSwitch | 557880110 | 0x21409A6E | Arka sis lambası |
| getLightFrontFogSwitch / setLightFrontFogSwitch | 557880118 | 0x21409A76 | Ön sis lambası |
| getCarSearchFeedback / setCarSearchFeedback | 557880093 | 0x21409A5D | Araç arama (korna+ışık) |

---

## 7. ADAS / Sürüş Yardım Sistemleri — `vehiclesetting` servisi

| Metot | Property ID (decimal) | Notlar |
|---|---|---|
| getSpeedAsstMode / setSpeedAsstMode | 557883650 | Hız yardım modu |
| getAccTjaMode / setAccTjaMode | 557883775 | ACC+TJA modu (1=Açık, 4=Kapalı) |
| getLaneKeepingAsstMode / setLaneKeepingAsstMode | 557883652 | Şerit koruma |
| getLaneKeepingAsstSen / setLaneKeepingAsstSen | 557883653 | Şerit koruma hassasiyeti |
| getLaneKeepingWarningSound / setLaneKeepingWarningSound | 557883654 | Şerit uyarı sesi |
| getLaneKeepingVibration / setLaneKeepingVibration | 557883655 | Şerit uyarı titreşim |
| getTrafficJamAsstOn / setTrafficJamAsstOn | 557883656 | Trafik sıkışıklığı yardımı |
| getFcwAlarmMode / setFcwAlarmMode | 557883659 | Çarpışma uyarısı modu |
| getFcwAutoBrakeMode / setFcwAutoBrakeMode | 557883658 | Otomatik fren |
| getFcwSensitivity / setFcwSensitivity | 557883660 | FCW hassasiyeti |
| getAutoEmergencyBraking / setAutoEmergencyBraking | 557883661 | Acil otomatik frenleme |
| getBlindSpotDetection / setBlindSpotDetection | 557883663 | Kör nokta algılama |
| getLaneChangeAsst / setLaneChangeAsst | 557883768 | Şerit değiştirme yardımı |
| getRearCrossTrafficSys / setRearCrossTrafficSys | 557883783 | Geri çarpışma sistemi |
| getRearCollisionWarning / setRearCollisionWarning | 557883784 | Arka çarpışma uyarısı |
| getParkingWarning / setParkingWarning | 557883785 | Park uyarısı |
| getDriverMonitorSys / setDriverMonitorSys | 557883754 | Sürücü izleme |
| getPsgSafetyAirbagOn / setPsgSafetyAirbagOn | 557883752 | Yolcu hava yastığı |
| getSpeedAsstSlifWarning / setSpeedAsstSlifWarning | 557883756 | Hız işareti uyarısı |

---

## 8. Zone (Alan) Sabitleri

```
AREA_GLOBAL  = 16777216 (0x01000000)  — vehiclesetting tüm property'ler
HVAC_ALL     = 117      (0x75)        — aircondition her iki zone
HVAC_LEFT    = 49       (0x31)        — sürücü tarafı
HVAC_RIGHT   = 68       (0x44)        — yolcu tarafı
DOOR_ALL     = 85       (0x55)        — tüm kapılar
DOOR_ROW_1   = 5                      — ön kapılar
DOOR_ROW_2   = 80                     — arka kapılar
```

---

## 9. Mevcut Kodumuzdaki Durum

### ✅ Doğrulandı ve Kullanılıyor
```text
PROP_DRIVE_MODE          = 0x2140A17C  (557883772)  ✅
PROP_REGEN_LEVEL         = 0x2140A191  (557883793)  ✅
PROP_ONE_PEDAL           = 0x2140A193  (557883795)  ✅
PROP_STEERING_HEAT       = 0x1540253A  (356525370)  ✅
PROP_SEAT_HEAT_L         = 0x15402513  (356525331)  ✅
PROP_SEAT_HEAT_R         = 0x15402514  (356525332)  ✅
```

### 🆕 Yeni Eklenmesi Gerekenler
```text
PROP_REGEN_BRAKE_SWITCH  = 0x2140A18F  (557883791)  — CPM katmanında regen OFF
PROP_REGEN_LEVEL_DISABLE = 0x2140A19C  (557883804)  — alternatif regen OFF
PROP_STEERING_LEVEL      = 0x2140A18D  (557883789)  — direksiyon ağırlığı
PROP_BRAKE_PEDAL_LEVEL   = 0x2140A18E  (557883790)  — fren hassasiyeti
PROP_AUTO_HOLD           = 0x2140A1A0  (557883808)  — auto hold
PROP_DOOR_LOCK           = 0x11402800  (289421312)  — kapı kilidi
PROP_MIRROR_FOLD         = 0x2140A903  (557897475)  — ayna katlanma
PROP_AMB_LIGHT_ON        = 0x21409A41  (557880065)  — ambiyant ışık
PROP_FRONT_DEFROST       = 0x15402521  (356525345?) — ön cam ısıtma (doğrulanmalı)
PROP_REAR_DEFROST        = 0x15402522  (356525346?) — arka cam ısıtma (doğrulanmalı)
```

---

## 10. Araç Durum Okumaları — `VehicleConditionBinder`

Servis: `CarSensorManager` + `CarVendorInstrumentClusterManager` + `CarInfoManager`

### Gerçek Zamanlı Sensör Verileri
| Metot | Property ID | Tip | Notlar |
|---|---|---|---|
| getCarSpeed() | 291504647 | float | m/s cinsinden araç hızı |
| getVehicleIgnition() | 289412477 | int | 0=kapalı, 2=çalışıyor |
| getCarGear() | 557847918 | int | vites konumu |
| getEngineState() | 557847932 | int | motor/EV sistemi durumu |
| getTotalMileage() | 557873939 | int | toplam km (VendorInstrumentCluster) |
| getSwcFunctionChangeSwa() | 557873940 | int | direksiyon tuşu SWC sinyali |
| getCrashSignal() | 557847954 | int | çarpışma sinyali |
| getMileageUnit() | 557847955 | int | km/mil birimi |

### ECU Erişilebilirlik Sinyalleri
| Metot | Property ID | Notlar |
|---|---|---|
| getBmsAvlbly() | 557847941 | BMS çevrimiçi mi? 1=evet |
| getHcuAvlbly() | 557847942 | HCU (EV kontrol) |
| getAcAvlbly()  | 557847934 | Klima ECU |
| getBcmAvlbly() | 557847935 | Body Control Module |
| getVcuAvlbly() | 557847975 | Vehicle Control Unit |
| getRadarAvlbly() | 557847943 | Radar sistemi |
| getApaAvlbly() | 557847937 | Otomatik park |

### Araç Konfigürasyon Kodları (CarInfoManager — sabit, değişmez)
| Metot | Property ID | Notlar |
|---|---|---|
| getVinNumber() | 554713683 | VIN numarası (String) |
| getOnePedalConfigCode() | 561005164 | Tek pedal donanım var mı? |
| getBatteryConfigCode() | 561005125 | Batarya tipi kodu |
| getConfig360() | 561005062 | 2=360 yok, 10=HD360 var |
| getSunRoofControlConfigCode() | 561005094 | Sunroof var mı? |
| getSeatHeatingConfigCode() | 561005058 | Koltuk ısıtma donanımı |

### Sabitler
```
VEHICLE_IGNITION_RUN  = 2   (kontak açık)
HIGH_CONFIG = 2 / MIDDLE_CONFIG = 1 / LOW_CONFIG = 0
HD360 = 10 / NO_HD360 = 2
MAINTENANCE_STATUS_OK = 0 / SUGGEST = 1 / IMMEDIATELY = 2
```

---

## 11. Şarj Yönetimi — `VehicleChargingBinder`

Servis: `CarBMSManager` — `car.getCarManager("bms")`
Metot: `carBMSManager.setGlobalProperty(Integer.class, PROP_ID, value)`

### Anlık Şarj Durumu (Read Only)
| Metot | Property ID | Tip | Notlar |
|---|---|---|---|
| getCurrentElectricQuantity() | 560002052 | float | **SOC (%)** — en kritik! |
| getCurrentEnduranceMileage() | 557904924 | int | **Menzil (km)** |
| getChargingStatus() | 557904905 | int | 7=bağlı ama şarj etmiyor |
| getPowerBatteryVol() | 560002054 | float | Batarya voltajı (V) |
| getActualChargingCurrent() | 560002055 | float | Gerçek şarj akımı (A) |
| getExpectedCurrent() | 560002058 | float | Beklenen şarj akımı (A) |
| getAcCurrent() | 560002108 | float | AC giriş akımı (A) |
| getAcVoltage() | 560002109 | float | AC giriş voltajı (V) |
| getPredictChargingTime() | 557904919 | int | Tahmini şarj süresi |
| getChargingClosePredictMileage() | 557904918 | int | Şarj bitişinde tahmini menzil |
| getChargingStopReason() | 557904917 | int | Şarj durma nedeni |
| getChrgngDoorPos() | 557904955 | int | Şarj kapısı pozisyonu |
| getWirelessChargeStat() | 557904965 | int | Kablosuz şarj durumu |
| getEnergyFlowInfo() | 557883738 | int | Enerji akış bilgisi |
| getElectricityLevel() | 557883813 | int | Yük kesme seviyesi |

### Şarj Ayarları (Read/Write)
| Metot | Property ID | Notlar |
|---|---|---|
| getChargingCurrent / setChargingCurrent | 557904907 | AC şarj akımı limiti (A) |
| getChargingCloseSoc / setChargingCloseSoc | 557904908 | **Hedef SOC (%)** |
| getChargingControlSwitch / setChargingControlSwitch | 557904914 | Şarj kontrol on/off |
| getChargingLockSwitch / setChargingLockSwitch | 557904915 | Şarj kilidi |
| getDrivingBatteryHeat / setDrivingBatteryHeat | 557904937 | Batarya ısıtıcısı (PTC) |

### Zamanlı Şarj
| Metot | Property ID | Notlar |
|---|---|---|
| getReserChrgControl / setReserChrgControl | 557904909 | Zamanlama on/off |
| getReserChrgStartHour / setReserChrgStartHour | 557904910 | Başlangıç saati |
| getReserChrgStartMinute / setReserChrgStartMinute | 557904911 | Başlangıç dakikası |
| getReserChrgStopHour / setReserChrgStopHour | 557904912 | Bitiş saati |
| getReserChrgStopMinute / setReserChrgStopMinute | 557904913 | Bitiş dakikası |

### V2G (Vehicle-to-Grid / Desarj)
| Metot | Property ID | Notlar |
|---|---|---|
| getDischrgCloseSoc / setDischrgCloseSoc | 557904943 | Desarj hedef SOC (%) |
| getDischrgControlSwitch / setDischrgControlSwitch | 557904944 | Desarj kontrol |
| getPredictDischrgTime() | 557904939 | Tahmini desarj süresi |

### Enerji Tüketim İstatistikleri
| Metot | Property ID | Notlar |
|---|---|---|
| getElecCsumpPerKm() | 560002077 | kWh/km tüketim |
| getElecCsumpPerKmh() | 560002114 | kWh/kmh |
| getTotalRegenEnrgAfterCharge() | 559980962 | Şarjdan sonra toplam regen enerjisi |
| getTotalRegenRngAfterCharge() | 559980964 | Şarjdan sonra regen menzil katkısı |
| getAccConsumptionAfterCharge() | 559980951 | Şarjdan sonra anlık tüketim |

### Geçerlilik Sinyalleri
| Property ID | Notlar |
|---|---|
| 555807778 | SOC geçerlilik |
| 555807783 | Menzil geçerlilik |
| 555807782 | Tahmini şarj süresi geçerlilik |

---

## 12. Ekran Yönetimi — `VehicleScreenBinder`

Servis: `CarPowerManager` — property ID kullanmaz, doğrudan API çağrısı

| Metot | Notlar |
|---|---|
| screenWakeup(pid, trans) | Ekranı aç. trans=true → geçici aç |
| screenSleep(pid, showTime, theme) | Ekranı kapat. showTime=true → saat göster modu |
| resumeScreenSleep(pid) | Geçici açma biten pid'in işini bitirince uyku'ya dön |
| getCurrentPowerMode() | CarPowerManager modu |
| registerListener(IScreenStateListener) | Ekran durum değişikliklerini dinle |

### Ekran State Değerleri
```
0 = Tam kapalı
1 = Saat/minimal göster modu
2 = Tam açık (normal)
3 = Geçici açık (trans=true)
```

### Broadcast
```
Action : "com.saic.conn.display.report"
Extra  : "state" (boolean) — true=ekran kapalı
```
Bu broadcast'i dinleyerek uygulamadan ekran durumunu takip edebiliriz.

---

## 13. Test Panelinde Denenecekler (Öncelik Sırası)

### Grup A — Sürüş Dinamiği
1. `PROP_REGEN_BRAKE_SWITCH` (557883791) — regen OFF testi
2. `PROP_STEERING_LEVEL` (557883789) — direksiyon ağırlığı 0/1/2/3
3. `PROP_BRAKE_PEDAL_LEVEL` (557883790) — fren hassasiyeti
4. `PROP_AUTO_HOLD` (557883808) — auto hold toggle

### Grup B — Konfor
5. `PROP_DOOR_LOCK` (289421312) — kapı kilidi
6. `PROP_MIRROR_FOLD` (557897475) — ayna katlanma
7. `PROP_AMB_LIGHT_ON` (557880065) — ambiyant ışık
8. Ön cam ısıtma (356525333 toggle)

### Grup C — Durum Okuma
9. SOC okuma (560002052) — batarya yüzdesi
10. Menzil okuma (557904924) — kalan km
11. Hız okuma (291504647) — m/s
12. BMS erişilebilirlik (557847941)

---

## 14. Track Mode Telemetrisi — `CarSensorManager` (saic_saicmaintenance)

Kaynak: `com.saicmotor.hmi.trackmodesdk` — `VehicleDataManager` (`e/c.smali`) kayıtlı sensör listesi + `onSensorChanged` (`e/c$d.smali`) eşlemesi.  
API: `Car.createCar(...)` → `getCarManager("sensor")` → `CarSensorManager.registerListener(..., sensorConfigId, rate)` — **config ID aşağıdaki hex/decimal ile aynıdır.**

### 14.1 Kayıtlı 15 sensör config ID (Track Mode dizisi)

| `CarSensorManager` sabiti (stub) | Decimal | Hex | Not |
|---|---|---|---|
| `ID_VEHICLE_LATERAL_ACCELERATION` | 559945125 | 0x216015A5 | Yanal ivme (g/9.8 ölçek) |
| `ID_VEHICLE_LATERAL_ACCELERATION_VALID` | 557847967 | 0x2140159F | Geçerlilik flag’i |
| `ID_ACCELERATION_PORTRAIT` | 559945057 | 0x21601561 | Boyuna ivme (portrait) |
| `ID_ACCELERATION_PORTRAIT_VALID` | 557847969 | 0x214015A1 | Geçerlilik |
| `ID_DRIVE_EFFICIENCY_INDICATION` | 557847964 | 0x2140159C | Sürüş verimliliği göstergesi (0–100) |
| `ID_DRIVE_EFFICIENCY_INDICATION_VALID` | 557847966 | 0x2140159E | Geçerlilik |
| `ID_FAST_ACCELERATION_DECELERATION` | 559945060 | 0x21601564 | Hızlı ivme/fren → `throttle_open` benzeri (0–100 clamp) |
| `ID_FAST_ACCELERATION_DECELERATION_VALID` | 557847970 | 0x214015A2 | Geçerlilik |
| `ID_BRAKE_PEDAL_DRIVER_APPLIED_PRESSURE` | 557847965 | 0x2140159D | Fren pedal basıncı → `intValues[0]` |
| `ID_BRAKE_PEDAL_DRIVER_APPLIED_PRESSURE_VALID` | 557847971 | 0x214015A3 | Geçerlilik |
| `ID_DISTANCE_ROLLING_COUNT_AVERAGE_DRIVEN` | 559945126 | 0x216015A6 | Yuvarlanan mesafe sayacı (tur/mesafe) |
| `ID_DISTANCE_ROLLING_COUNT_AVERAGE_DRIVEN_RESET_OCCURRED` | 557847972 | 0x214015A4 | Reset oldu mu |
| `SENSOR_TYPE_CAR_SPEED` | 291504647 | 0x11600207 | Araç hızı (Track Mode içinde `floatValues[0]` → km/h benzeri int) |
| `SENSOR_TYPE_CAR_SPEED_VALID` | 557847968 | 0x214015A0 | Hız geçerlilik |
| `ID_WHEEL_ANGLE` | 559945059 | 0x21601563 | Direksiyon açısı → `steering_speed` kolonuna yazılıyor |

### 14.2 `onSensorChanged` içinde işlenen ID’ler (sparse-switch)

Bu ID’ler için listener içinde `floatValues[0]` veya `intValues[0]` okunur (indeks **0**).

| Property ID (decimal) | Hex | Track DB / iç alan | Kısa açıklama |
|---|---|---|---|
| 559945126 | 0x216015A6 | `distance` (mesafe hesabı) | Rolling distance |
| 559945125 | 0x216015A5 | `lateral_a` | Yanal ivme (max abs takibi) |
| 559945057 | 0x21601561 | `longitudinal_a` | Boyuna ivme |
| 557847964 | 0x2140159C | **`power`** | **Motor kW değil** — `ID_DRIVE_EFFICIENCY_INDICATION` (0–100); state `h` → DB `power` |
| 557847965 | 0x2140159D | `breake` | Fren pedal basıncı (int) |
| 559945060 | 0x21601564 | `throttle_open` | Hızlı ivme/fren sinyali (0–100) |
| 291504647 | 0x11600207 | `speed` | Hız |
| 559945059 | 0x21601563 | `steering_speed` | Direksiyon açısı (int’e cast) |

> `*_VALID` satırları (0x2140159F, 0x214015A1, …) dizide **aynı şekilde register** edilir; bu APK’daki `sparse-switch` içinde **ayrı case yok** — validasyon için kendi kodunda dinleyebilirsin.

### 14.3 DriveHub / kadran için kullanım

- Aynı **hex ID**’leri `CarSensorManager.registerListener(OnSensorChangedListener, configId, SENSOR_RATE_FAST)` ile kullan.
- Binder `CarBMSManager` property ID’leriyle karıştırma: bunlar **sensor config** katmanı; sayısal değerler yine aynı ID uzayında olabilir, `CarPropertyManager` ile de doğrulanabilir.
- `power` kolonu Track Mode’da **verimlilik göstergesi** sinyalinden geliyor; gerçek **motor gücü (kW)** için ayrı property gerekir (ör. başka `CarBMSManager` / `CarInfoManager` sinyali).

---

## 15. Lastik basıncı + sıcaklığı — `YFVehicleProperty` / `CarSensorManager`

Kaynak: `SaicAdapterService_out` (ve aynı stub’lu APK’lar) — `android.hardware.automotive.YFvehicle.V2_0.YFVehicleProperty`.  
API: `CarSensorManager.registerListener(..., sensorConfigId, ...)` veya `CarPropertyManager` ile **aynı sayısal ID** (global area).

| Sabit (stub) | Decimal | Hex | Not |
|---|---|---|---|
| `SENSOR_TIRE_PRESURE_FL` | 557847891 | 0x21401553 | Ön sol lastik basıncı (OEM birimi; araçta doğrula) |
| `SENSOR_TIRE_PRESURE_FR` | 557847892 | 0x21401554 | Ön sağ lastik basıncı |
| `SENSOR_TIRE_PRESURE_RL` | 557847893 | 0x21401555 | Arka sol lastik basıncı |
| `SENSOR_TIRE_PRESURE_RR` | 557847894 | 0x21401556 | Arka sağ lastik basıncı |
| `SENSOR_TIRE_TEMP_FL` | 557847899 | 0x2140155B | Ön sol lastik sıcaklığı |
| `SENSOR_TIRE_TEMP_FR` | 557847900 | 0x2140155C | Ön sağ lastik sıcaklığı |
| `SENSOR_TIRE_TEMP_RL` | 557847901 | 0x2140155D | Arka sol lastik sıcaklığı |
| `SENSOR_TIRE_TEMP_RR` | 557847902 | 0x2140155E | Arka sağ lastik sıcaklığı |

**Kod sabitleri:** `MG4Hardware` içinde `PROP_TIRE_PRESSURE_*` ve `PROP_TIRE_TEMP_*` (CarProperty / sensör için 32 bit ID).

> Track Mode’un 15 sensörlük dizisinde **yok**; ayrı property’dir. Birim/format (°C vs ham) araçta doğrulanmalı.

---

## 16. Tork — yüzde (maks oranı)

**Newton-metre (Nm)** için bu analizde **ayrı VehicleProperty yok.** “Maks torkun oranı” pratikte **yüzde** ile gösterilir.

### 16.1 CarProperty ile güç/tork hissi (OEM Track ile aynı sinyal)

| Sabit (stub) | Decimal | Hex | Not |
|---|---|---|---|
| `ID_DRIVE_EFFICIENCY_INDICATION` | 557847964 | 0x2140159C | 0–100 gösterge; motor kW değil; Track’te `power` kolonu |

`MG4Hardware`: `TRACK_SENSOR_DRIVE_EFFICIENCY` — `getProperty` / sensör ile **aynı 32 bit adres**.

### 16.2 OBD yüzde tork — teşhis indeksi (CarProperty adresi değil)

`android.car.diagnostic.IntegerSensorIndex` — `CarDiagnosticManager` tamsayı sensör **indeksi**; **`getProperty(propId)` burada kullanılmaz.**

| `IntegerSensorIndex` / anlam | İndeks (hex) | İndeks (dec) | `MG4Hardware` |
|---|---|---|---|
| `DRIVER_DEMAND_PERCENT_TORQUE` | 0x18 | 24 | `PROP_TORQUE_PERCENT_DRIVER_DEMAND_INDEX` |
| `ENGINE_ACTUAL_PERCENT_TORQUE` | 0x19 | 25 | `PROP_TORQUE_PERCENT_ENGINE_ACTUAL_INDEX` |
| `ENGINE_REFERENCE_PERCENT_TORQUE` | 0x1A | 26 | `PROP_TORQUE_PERCENT_ENGINE_REFERENCE_INDEX` |
| `ENGINE_PERCENT_TORQUE_DATA_IDLE` | 0x1B | 27 | (teşhis eğri noktası) |
| … | 0x1C–0x1F | 28–31 | Ek eğri noktaları |

> EV’de 16.2 indekslerinin **dolu** olacağı garanti değildir. Öncelik: **16.1** (tek CarProperty adresi); teşhis için **16.2** indeksleri.
