package com.drivehub.dort.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.drivehub.dort.service.MG4ControlService;

/**
 * Dış tetikleyici (broadcast): uygulamayı öne getirmeden
 * serviste tek atımlık sesli komut dinlemeyi başlatır.
 */
public class StartVoiceAssistantReceiver extends BroadcastReceiver {

    public static final String ACTION_START_VOICE_ASSISTANT =
            "com.drivehub.dort.action.START_VOICE_ASSISTANT_ACTIVITY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_START_VOICE_ASSISTANT.equals(intent.getAction())) {
            return;
        }
        Intent i = new Intent(context, MG4ControlService.class);
        i.setAction(MG4ControlService.ACTION_VOICE_ONESHOT_START);
        ContextCompat.startForegroundService(context, i);
    }
}
