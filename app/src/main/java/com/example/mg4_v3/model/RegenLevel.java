package com.example.mg4_v3.model;

public enum RegenLevel {
    // VehicleSettingService setRegenerativeLevel() değerleri (araçta doğrulandı):
    LOW(0,      "Düşük"),
    MEDIUM(1,   "Orta"),
    HIGH(2,     "Yüksek"),
    ADAPTIVE(3, "Adaptif"),
    OFF(5,       "Kapalı"),      // setRegenerativeLevel(5) = regen tamamen kapalı
    ONE_PEDAL(6, "Tek Pedal");  // setRegenerativeLevel(6) = tek pedal modu

    public final int    value;
    public final String label;

    RegenLevel(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public RegenLevel next() {
        RegenLevel[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    public static RegenLevel fromValue(int value) {
        for (RegenLevel r : values()) {
            if (r.value == value) return r;
        }
        return MEDIUM;
    }
}