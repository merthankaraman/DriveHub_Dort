package com.example.mg4_2.model;

public enum RegenLevel {
    OFF(0,      "Kapalı"),
    LOW(1,      "Düşük"),
    MEDIUM(2,   "Orta"),
    HIGH(3,     "Yüksek"),
    ADAPTIVE(4, "Adaptif");

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
}