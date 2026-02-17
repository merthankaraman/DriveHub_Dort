package com.example.mg4_2.model;

public enum RegenLevel {
    LOW     ("Düşük",   1),
    MEDIUM  ("Orta",    2),
    HIGH    ("Yüksek",  3),
    ADAPTIVE("Adaptif", 4);

    public final String label;
    public final int    value;

    RegenLevel(String label, int value) {
        this.label = label;
        this.value = value;
    }

    public RegenLevel next() {
        RegenLevel[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static RegenLevel fromValue(int value) {
        for (RegenLevel r : values()) {
            if (r.value == value) return r;
        }
        return MEDIUM;
    }
}