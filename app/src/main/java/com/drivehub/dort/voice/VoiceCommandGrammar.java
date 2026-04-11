package com.drivehub.dort.voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.vosk.Recognizer;

/**
 * Vosk küçük model geniş kelime haznesinde zayıf kalır; {@link Recognizer#setGrammar}
 * ile tanımayı yalnızca araç komut cümlelerine yaklaştırır (HCLr+Gr modellerinde).
 */
public final class VoiceCommandGrammar {

    /**
     * Küçük harf / yaygın telaffuz varyantları. Son eleman {@code [unk]} olmalı.
     */
    private static final String[] PHRASES = {
            // Direksiyon ısıtma
            "direksiyonu ısıt",
            "direksiyon ısıt",
            "direksiyonu aç",
            "direksiyon aç",
            "direksiyon ısıtma aç",
            "direksiyon ısıtmayı aç",
            "direksiyonu kapat",
            "direksiyon kapat",
            "direksiyonu kapa",
            "direksiyon kapa",
            "direksiyonu durdur",
            "direksiyon durdur",
            // ASR sık yazım
            "direksyonu ısıt",
            "direksyon ısıt",
            "direksyonu aç",
            "direksyonu kapat",

            // Tek pedal
            "tek pedal aç",
            "tek pedalı aç",
            "tek pedal açık",
            "tek pedal aktif",
            "tek pedal kapat",
            "tek pedalı kapat",
            "tek pedal durdur",
            "tekpedal aç",
            "tekpedal kapat",

            // Regen
            "regen düşük",
            "regen dusuk",
            "regen orta",
            "regen yüksek",
            "regen yuksek",
            "regen adaptif",
            "rejeneratif düşük",
            "rejeneratif orta",
            "rejeneratif yüksek",
            "rejeneratif adaptif",
            "rejenerasyon düşük",
            "regen seviyesi düşük",
            "regen seviyesi orta",
            "regen seviyesi yüksek",
            "regen seviyesi adaptif",
            "seviye düşük",
            "seviye orta",
            "seviye yüksek",
            "seviye adaptif",

            // Sürüş modu (bağlamlı kısa ifadeler)
            "sürüş modunu spor yap",
            "sürüş modu spor",
            "sürüş spor",
            "spor modu",
            "sport modu",
            "araç spor mod",
            "arac spor mod",
            "konfor modu spor",
            "sürüş modunu eko yap",
            "sürüş modu eko",
            "sürüş eko",
            "eko modu",
            "eco modu",
            "sürüş modunu normal yap",
            "sürüş modu normal",
            "sürüş normal",
            "normal mod",
            "sürüş modunu kar yap",
            "sürüş modu kar",
            "sürüş kar",
            "kar modu",
            "kış modu",
            "kis modu",

            // Sürüş modu — Özel (CUSTOM)
            "sürüş modu özel",
            "sürüş modunu özel yap",
            "sürüş modunu ozel yap",
            "surus modu ozel",
            "surus modunu ozel yap",
            "özel mod",
            "ozel mod",
            "özel moda geç",
            "ozel moda gec",
            "özel sürüş",
            "ozel surus",
            "araç özel mod",
            "arac ozel mod",
            "konfor modu özel",
            "custom mod",
            "sürüş custom",
            "surus custom",

            // Üşüme — sürücü (birinci şahıs / sürücü)
            "üşüdüm",
            "üşüyorum",
            "usudum",
            "usuyorum",
            "çok üşüdüm",
            "cok usudum",
            "donuyorum",
            "çok soğuk",
            "cok soguk",
            "sürücü üşüdü",
            "surucu usudu",
            "sürücü üşüyor",
            "sürücü donuyor",

            // Üşüme — çoğul (içeridekiler)
            "üşüyoruz",
            "üşüdük",
            "usuyoruz",
            "usuduk",
            "çok üşüyoruz",
            "donuyoruz",

            // Üşüme — yolcu
            "yolcu üşüdü",
            "yolcu üşüyor",
            "yolcu usudu",
            "yolcu donuyor",
            "yolcu çok üşüdü",
            "yolcu çok soğuk",

            "[unk]"
    };

    private VoiceCommandGrammar() {}

    public static int phraseCount() {
        return PHRASES.length;
    }

    public static String asJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (String p : PHRASES) {
            a.put(p);
        }
        return a.toString();
    }

    /**
     * @return gramer uygulandıysa true
     */
    public static boolean applyIfSupported(Recognizer recognizer) {
        if (recognizer == null) {
            return false;
        }
        try {
            recognizer.setGrammar(asJson());
            recognizer.reset();
            VoiceLog.i(VoiceLog.TAG_GRAMMAR, "Komut grameri aktif, ifade sayısı=" + PHRASES.length);
            return true;
        } catch (Throwable t) {
            VoiceLog.w(VoiceLog.TAG_GRAMMAR, "setGrammar desteklenmiyor veya hata: " + t.getMessage());
            return false;
        }
    }
}
