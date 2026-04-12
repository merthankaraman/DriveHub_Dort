package com.drivehub.dort.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Dış uygulamalardan (implicit intent) sesli asistanı açar: model hazırsa dinlemeye geçer,
 * bir sonuç işlendikten sonra {@link VoiceAssistantActivity} kendini kapatır.
 */
public class StartVoiceAssistantActivity extends Activity {

    public static final String ACTION_START_VOICE_ASSISTANT =
            "com.drivehub.dort.action.START_VOICE_ASSISTANT_ACTIVITY";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = new Intent(this, VoiceAssistantActivity.class);
        i.putExtra(VoiceAssistantActivity.EXTRA_AUTO_START_LISTEN, true);
        i.putExtra(VoiceAssistantActivity.EXTRA_FINISH_AFTER_FIRST_RESULT, true);
        startActivity(i);
        finish();
    }
}
