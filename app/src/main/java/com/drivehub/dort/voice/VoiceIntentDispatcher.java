package com.drivehub.dort.voice;

import android.content.Context;
import android.content.Intent;

import com.drivehub.dort.R;
import com.drivehub.dort.model.DriveMode;
import com.drivehub.dort.model.RegenLevel;
import com.drivehub.dort.service.MG4ControlService;

import java.util.Locale;

/**
 * Vosk çıktısı (düz metin veya JSON içindeki text) için basit Türkçe kural eşlemesi.
 */
public final class VoiceIntentDispatcher {

    private VoiceIntentDispatcher() {}

    public static final class Result {
        public final Intent commandIntent;
        public final String assistantReply;

        public Result(Intent commandIntent, String assistantReply) {
            this.commandIntent = commandIntent;
            this.assistantReply = assistantReply;
        }
    }

    public static Result parse(Context appContext, String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) {
            return null;
        }

        if (passengerColdComfortMatch(n)) {
            VoiceLog.d(VoiceLog.TAG_CMD, "coldPassenger norm=\"" + n + "\" raw=\"" + raw + "\"");
            return coldPassenger(appContext);
        }
        if (crewColdComfortMatch(n)) {
            VoiceLog.d(VoiceLog.TAG_CMD, "coldCrew norm=\"" + n + "\" raw=\"" + raw + "\"");
            return coldCrew(appContext);
        }
        if (driverColdComfortMatch(n)) {
            VoiceLog.d(VoiceLog.TAG_CMD, "coldDriver norm=\"" + n + "\" raw=\"" + raw + "\"");
            return coldDriver(appContext);
        }

        if (tekPedalMention(n)) {
            if (isOffPhrase(n)) {
                return pedalOff(appContext);
            }
            if (isOnPhrase(n)) {
                return pedalOn(appContext);
            }
        }

        if (regenContext(n)) {
            RegenLevel rl = detectRegenLevel(n);
            if (rl != null) {
                return regenSet(appContext, rl);
            }
        }

        if (driveModeContext(n)) {
            DriveMode dm = detectDriveMode(n);
            if (dm != null) {
                return driveSet(appContext, dm);
            }
        }

        if (steeringContext(n)) {
            if (steeringOn(n)) {
                return heatOn(appContext);
            }
            if (steeringOff(n)) {
                return heatOff(appContext);
            }
        }

        return null;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.toLowerCase(new Locale("tr", "TR")).trim();
        t = t.replaceAll("[,.!?;:]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Yolcu tarafı soğuk → sağ ön koltuk ısıtma 3 (LHD yolcu). “Yolcu üşüyoruz” çoğul → {@link #crewColdComfortMatch}. */
    private static boolean passengerColdComfortMatch(String n) {
        if (!n.contains("yolcu")) {
            return false;
        }
        if (pluralColdPronoun(n)) {
            return false;
        }
        return coldWordHints(n);
    }

    /** “Üşüyoruz” vb. → direksiyon + sol + sağ koltuk 3 */
    private static boolean crewColdComfortMatch(String n) {
        return pluralColdPronoun(n);
    }

    private static boolean pluralColdPronoun(String n) {
        return n.contains("üşüyoruz") || n.contains("üşüdük") || n.contains("üşüyoz")
                || n.contains("usuyoruz") || n.contains("usuduk") || n.contains("usuyoz")
                || n.contains("donuyoruz") || n.contains("donuyoz");
    }

    /**
     * Sürücü / birinci şahıs soğuk → direksiyon + sol ön koltuk 3.
     * {@code yolcu} geçen cümleler yolcu dalına ayrılır (önce eşleşir).
     */
    private static boolean driverColdComfortMatch(String n) {
        if (n.contains("yolcu")) {
            return false;
        }
        return coldWordHints(n);
    }

    private static boolean coldWordHints(String n) {
        if (n.contains("üşüd") || n.contains("üşüy")) {
            return true;
        }
        if (n.contains("usud") || n.contains("usuy")) {
            return true;
        }
        if (n.contains("donu")) {
            return true;
        }
        return (n.contains("soğuk") || n.contains("soguk"))
                && (n.contains("çok") || n.contains("cok"));
    }

    private static Result coldPassenger(Context ctx) {
        return new Result(base(ctx, "COLD_COMFORT_PASSENGER"),
                ctx.getString(R.string.voice_cold_passenger_reply));
    }

    private static Result coldDriver(Context ctx) {
        return new Result(base(ctx, "COLD_COMFORT_DRIVER"),
                ctx.getString(R.string.voice_cold_driver_reply));
    }

    private static Result coldCrew(Context ctx) {
        return new Result(base(ctx, "COLD_COMFORT_CREW"),
                ctx.getString(R.string.voice_cold_crew_reply));
    }

    private static boolean tekPedalMention(String n) {
        return (n.contains("tek") && n.contains("pedal"))
                || n.contains("tekpedal")
                || n.contains("te pedal");
    }

    private static boolean isOnPhrase(String n) {
        return n.contains("aç")
                || n.contains("açık") || n.contains("acik")
                || n.contains("aktif") || n.contains("başlat") || n.contains("baslat")
                || n.contains("çalıştır") || n.contains("calistir");
    }

    private static boolean isOffPhrase(String n) {
        return n.contains("kapat") || n.contains("kapa ")
                || n.contains(" kapa") || n.endsWith("kapa")
                || n.contains("durdur") || n.contains("söndür") || n.contains("sondur");
    }

    private static boolean regenContext(String n) {
        if (n.contains("regen") || n.contains("rejeneratif") || n.contains("rejenerasyon")) {
            return true;
        }
        if (n.contains("rejener")) {
            return true;
        }
        return n.contains("seviye") && hasRegenLevelWord(n);
    }

    private static boolean hasRegenLevelWord(String n) {
        return n.contains("düşük") || n.contains("dusuk")
                || n.contains(" orta ") || n.startsWith("orta ") || n.endsWith(" orta") || n.equals("orta")
                || n.contains("yüksek") || n.contains("yuksek")
                || n.contains("adaptif");
    }

    private static RegenLevel detectRegenLevel(String n) {
        if (n.contains("adaptif")) {
            return RegenLevel.ADAPTIVE;
        }
        if (n.contains("yüksek") || n.contains("yuksek")) {
            return RegenLevel.HIGH;
        }
        if (isWholeWordOrta(n)) {
            return RegenLevel.MEDIUM;
        }
        if (n.contains("düşük") || n.contains("dusuk")) {
            return RegenLevel.LOW;
        }
        return null;
    }

    /** "port" içinde "orta" yanlış eşleşmesin diye basit kontrol */
    private static boolean isWholeWordOrta(String n) {
        if (n.equals("orta")) {
            return true;
        }
        return n.contains(" orta ") || n.startsWith("orta ") || n.endsWith(" orta");
    }

    private static boolean driveModeContext(String n) {
        return n.contains("sürüş") || n.contains("surus") || n.contains("surusu")
                || n.contains("mod") || n.contains("araç") || n.contains("arac")
                || n.contains("konfor");
    }

    private static DriveMode detectDriveMode(String n) {
        // MG4 CUSTOM = 7; "konfor özelliği" gibi yanlış tetiklememek için özel/ozel/custom dar eşlenir.
        if (customDriveModeMention(n)) {
            return DriveMode.CUSTOM;
        }
        if (n.contains("spor") || n.contains("sport")) {
            return DriveMode.SPORT;
        }
        if (n.contains("eko") || n.contains("eco")) {
            return DriveMode.ECO;
        }
        if (isWholeWordNormal(n)) {
            return DriveMode.NORMAL;
        }
        if (n.contains("kar") || n.contains("snow") || n.contains("kış") || n.contains("kis")) {
            return DriveMode.SNOW;
        }
        return null;
    }

    private static boolean isWholeWordNormal(String n) {
        if (n.equals("normal")) {
            return true;
        }
        return n.contains(" normal ") || n.startsWith("normal ") || n.endsWith(" normal");
    }

    /** Özel sürüş profili; {@code özel} kelimesi tek başına (ör. özellik) kullanılmaz. */
    private static boolean customDriveModeMention(String n) {
        if (n.contains("özel mod") || n.contains("ozel mod")
                || n.contains("modu özel") || n.contains("modu ozel")
                || n.contains("modunu özel") || n.contains("modunu ozel")) {
            return true;
        }
        if (n.contains("sürüş özel") || n.contains("surus ozel")
                || n.contains("özel sürüş") || n.contains("ozel surus")) {
            return true;
        }
        if (n.contains("custom")) {
            return n.contains("mod") || n.contains("sürüş") || n.contains("surus")
                    || n.contains("araç") || n.contains("arac") || n.contains("konfor");
        }
        return false;
    }

    private static boolean steeringContext(String n) {
        return n.contains("direksiyon") || n.contains("direksynon") || n.contains("direksyon");
    }

    private static boolean steeringOn(String n) {
        return n.contains("ısıt") || n.contains("isit") || n.contains("ısıtma") || n.contains("isitma")
                || n.contains("aç") || n.contains("aktif")
                || n.contains("çalıştır") || n.contains("calistir");
    }

    private static boolean steeringOff(String n) {
        return n.contains("kapat") || n.contains("kapa") || n.contains("durdur")
                || n.contains("söndür") || n.contains("sondur");
    }

    private static Intent base(Context ctx, String action) {
        Intent i = new Intent(ctx, MG4ControlService.class);
        i.setAction(action);
        return i;
    }

    private static Result heatOn(Context ctx) {
        return new Result(base(ctx, "HEAT_ON"), "Direksiyon ısıtmasını açıyorum.");
    }

    private static Result heatOff(Context ctx) {
        return new Result(base(ctx, "HEAT_OFF"), "Direksiyon ısıtmasını kapatıyorum.");
    }

    private static Result driveSet(Context ctx, DriveMode dm) {
        Intent i = base(ctx, "DRIVE_SET");
        i.putExtra("driveValue", dm.value);
        return new Result(i, ctx.getString(R.string.voice_drive_set_reply, dm.label));
    }

    private static Result regenSet(Context ctx, RegenLevel rl) {
        Intent i = base(ctx, "REGEN_SET");
        i.putExtra("regenValue", rl.value);
        return new Result(i, "Rejeneratif seviyesini " + rl.label + " yapıyorum.");
    }

    private static Result pedalOn(Context ctx) {
        return new Result(base(ctx, "PEDAL_ON"), "Tek pedal modunu açıyorum.");
    }

    private static Result pedalOff(Context ctx) {
        return new Result(base(ctx, "PEDAL_OFF"), "Tek pedal modunu kapatıyorum.");
    }
}
