package com.example.mg4_2.model;

public enum RegenLevel {
    // Araçta doğrulanan değerler (log 1902260053):
    // setProperty value:0 → Düşük, value:1 → Orta, value:2 → Yüksek, value:3 → Adaptif
    LOW(0,      "Düşük"),
    MEDIUM(1,   "Orta"),
    HIGH(2,     "Yüksek"),
    ADAPTIVE(3, "Adaptif"),
    OFF(99,     "Kapalı"); // Kapalı için ayrı property (onePedal) kullanılıyor

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