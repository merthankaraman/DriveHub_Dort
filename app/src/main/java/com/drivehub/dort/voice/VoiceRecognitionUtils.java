package com.drivehub.dort.voice;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Recognizer;

/**
 * Demo ekranı ve one-shot servis akışının aynı tanıma yardımcılarını kullanması için ortak katman.
 */
public final class VoiceRecognitionUtils {

    private VoiceRecognitionUtils() {}

    /**
     * Komut odaklı tanıma için gramer uygular ve recognizer'i temiz başlangıca alır.
     */
    public static void prepareRecognizerForCommands(Recognizer recognizer) {
        if (recognizer == null) {
            return;
        }
        VoiceCommandGrammar.applyIfSupported(recognizer);
        recognizer.reset();
    }

    /**
     * Komut eşleştirme için sadece final "text" alanını döndürür.
     */
    public static String extractForCommand(String jsonOrPlain) {
        if (jsonOrPlain == null) {
            return "";
        }
        String s = jsonOrPlain.trim();
        if (s.startsWith("{")) {
            try {
                return new JSONObject(s).optString("text", "").trim();
            } catch (JSONException e) {
                return "";
            }
        }
        return s;
    }

    /**
     * UI/overlay için "text", yoksa "partial" döndürür.
     */
    public static String extractForDisplay(String jsonOrPlain) {
        if (jsonOrPlain == null) {
            return "";
        }
        String s = jsonOrPlain.trim();
        if (!s.startsWith("{")) {
            return s;
        }
        try {
            JSONObject o = new JSONObject(s);
            String text = o.optString("text", "").trim();
            if (!text.isEmpty()) {
                return text;
            }
            return o.optString("partial", "").trim();
        } catch (JSONException e) {
            return "";
        }
    }
}
