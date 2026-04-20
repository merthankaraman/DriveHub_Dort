package com.drivehub.dort.voice;

import android.content.Context;
import android.content.Intent;

import com.drivehub.dort.R;

/**
 * Demo ekranı ve one-shot servis için ortak hipotez çözümleyici.
 * Vosk çıktısını tek bir karar ağacından geçirir.
 */
public final class VoiceHypothesisResolver {

    public enum Kind {
        EMPTY,
        UNKNOWN,
        NO_MATCH,
        MATCH
    }

    public static final class Resolution {
        public final Kind kind;
        public final String commandText;
        public final String heardForOverlay;
        public final String actionForOverlay;
        public final Intent commandIntent;

        private Resolution(Kind kind,
                           String commandText,
                           String heardForOverlay,
                           String actionForOverlay,
                           Intent commandIntent) {
            this.kind = kind;
            this.commandText = commandText != null ? commandText : "";
            this.heardForOverlay = heardForOverlay != null ? heardForOverlay : "";
            this.actionForOverlay = actionForOverlay != null ? actionForOverlay : "";
            this.commandIntent = commandIntent;
        }
    }

    private VoiceHypothesisResolver() {}

    public static Resolution resolve(Context context, String hypothesis) {
        String commandText = VoiceRecognitionUtils.extractForCommand(hypothesis);
        if (commandText.isEmpty()) {
            return new Resolution(Kind.EMPTY, "", "", "", null);
        }

        String trimmed = commandText.trim();
        if ("[unk]".equalsIgnoreCase(trimmed) || trimmed.startsWith("[unk]")) {
            String heard = VoiceRecognitionUtils.extractForDisplay(hypothesis);
            if (heard.isEmpty()) {
                heard = trimmed;
            }
            return new Resolution(
                    Kind.UNKNOWN,
                    commandText,
                    heard,
                    context.getString(R.string.voice_overlay_action_unk),
                    null
            );
        }

        VoiceIntentDispatcher.Result parsed = VoiceIntentDispatcher.parse(context, commandText);
        if (parsed == null) {
            return new Resolution(
                    Kind.NO_MATCH,
                    commandText,
                    commandText,
                    context.getString(R.string.voice_overlay_action_none),
                    null
            );
        }

        return new Resolution(
                Kind.MATCH,
                commandText,
                commandText,
                parsed.assistantReply,
                parsed.commandIntent
        );
    }
}
