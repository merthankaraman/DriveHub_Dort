package com.example.mg4_2.model;

public enum DriveMode {
    ECO   ("Eco",    2),
    NORMAL("Normal", 3),
    SPORT ("Sport",  4),
    SNOW  ("Kar",    6),
    CUSTOM("Özel",   7);

    public final String label;
    public final int    value;

    DriveMode(String label, int value) {
        this.label = label;
        this.value = value;
    }

    public DriveMode next() {
        switch (this) {
            case ECO:    return NORMAL;
            case NORMAL: return SPORT;
            case SPORT:  return ECO;
            default:     return NORMAL;
        }
    }

    public static DriveMode fromValue(int value) {
        for (DriveMode m : values()) {
            if (m.value == value) return m;
        }
        return NORMAL;
    }
}