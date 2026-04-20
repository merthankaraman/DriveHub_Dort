package com.drivehub.dort.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;
import com.drivehub.dort.service.MG4ControlService;
import com.drivehub.dort.voice.VoiceHypothesisResolver;
import com.drivehub.dort.voice.VoiceLog;
import com.drivehub.dort.voice.VoiceModelDownloader;
import com.drivehub.dort.voice.VoiceRecognitionUtils;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Yerel Vosk (Türkçe) ile komut dinleme; sonuçları metin + TTS ile gösterir.
 */
public class VoiceAssistantActivity extends AppCompatActivity {

    /** Dış tetikleyici ile otomatik dinleme */
    public static final String EXTRA_AUTO_START_LISTEN = "com.drivehub.dort.EXTRA_AUTO_START_LISTEN";
    /** Bir hipotez işlendikten sonra (TTS için kısa gecikme ile) aktiviteyi kapat */
    public static final String EXTRA_FINISH_AFTER_FIRST_RESULT = "com.drivehub.dort.EXTRA_FINISH_AFTER_FIRST_RESULT";

    private static final int REQ_RECORD_AUDIO = 4401;
    private static final int REQ_RECORD_AUDIO_AUTO = 4402;
    private static final int MAX_LOG_CHARS = 14_000;

    private boolean mWantAutoListen;
    private boolean mAutoMicPermissionRequested;
    private boolean mFinishAfterFirstResult;
    private final AtomicBoolean mOneShotFinishScheduled = new AtomicBoolean(false);
    private final Runnable mFinishAfterResultRunnable = () -> {
        stopListeningSafe();
        if (!isFinishing()) {
            finish();
        }
    };

    private TextView mLogView;
    private ScrollView mScrollLog;
    private TextView mModelStatus;
    private TextView mVoiceLive;
    private Button mBtnListen;

    /** Kısmi log için (aynı metni spam etme) */
    private long mLastPartialLogMs;
    private String mLastPartialLogText = "";

    private final StringBuilder mLog = new StringBuilder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private Model mModel;
    private Recognizer mRecognizer;
    private SpeechService mSpeechService;
    private boolean mListening;

    private TextToSpeech mTts;
    private boolean mTtsReady;

    private String mLastDispatchedText = "";
    private long mLastDispatchElapsed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_assistant);
        FullscreenHelper.applyFromPrefs(this);
        configureVoskLogLevel();
        readVoiceIntentExtras();

        mLogView = findViewById(R.id.tvVoiceLog);
        mScrollLog = findViewById(R.id.scrollVoiceLog);
        mModelStatus = findViewById(R.id.tvVoiceModelStatus);
        mVoiceLive = findViewById(R.id.tvVoiceLive);
        mBtnListen = findViewById(R.id.btnVoiceListen);

        findViewById(R.id.btnVoiceBack).setOnClickListener(v -> finish());

        android.view.View btnCommandsHelp = findViewById(R.id.btnVoiceCommandsHelp);
        if (btnCommandsHelp != null) {
            btnCommandsHelp.setOnClickListener(v -> showVoiceCommandsHelp());
        }

        mBtnListen.setOnClickListener(v -> {
            if (!mBtnListen.isEnabled()) {
                return;
            }
            if (mRecognizer == null) {
                Toast.makeText(this, R.string.voice_model_not_ready, Toast.LENGTH_SHORT).show();
                return;
            }
            if (mListening) {
                stopListeningSafe();
            } else {
                if (!hasRecordPermission()) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                    return;
                }
                startListeningInternal();
            }
        });

        initTts();
        loadModelAsync();
    }

    private void readVoiceIntentExtras() {
        mWantAutoListen = getIntent().getBooleanExtra(EXTRA_AUTO_START_LISTEN, false);
        mFinishAfterFirstResult = getIntent().getBooleanExtra(EXTRA_FINISH_AFTER_FIRST_RESULT, false);
        mAutoMicPermissionRequested = false;
        mOneShotFinishScheduled.set(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readVoiceIntentExtras();
        attemptAutoStartListen();
    }

    private void showVoiceCommandsHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_help_commands_title)
                .setMessage(R.string.voice_help_commands_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureVoskLogLevel();
        attemptAutoStartListen();
    }

    /** Ayarlardaki “log” anahtarı ile aynı: kapalıyken Vosk API logları da sakinleşir. */
    private void configureVoskLogLevel() {
        try {
            LibVosk.setLogLevel(MG4Hardware.isLogEnabled() ? LogLevel.INFO : LogLevel.WARNINGS);
        } catch (Throwable ignored) {
        }
    }

    private void initTts() {
        mTts = new TextToSpeech(this, status -> {
            mTtsReady = (status == TextToSpeech.SUCCESS);
            if (!mTtsReady) {
                appendLogLine(getString(R.string.voice_tts_init_failed));
                return;
            }
            int r = mTts.setLanguage(new Locale("tr", "TR"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                mTts.setLanguage(Locale.getDefault());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mTts.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
            }
        });
    }

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLogLine(getString(R.string.voice_mic_granted));
                startListeningInternal();
            } else {
                appendLogLine(getString(R.string.voice_mic_denied));
                Toast.makeText(this, R.string.voice_mic_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_RECORD_AUDIO_AUTO) {
            mAutoMicPermissionRequested = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLogLine(getString(R.string.voice_mic_granted));
                if (mWantAutoListen && mRecognizer != null && !mListening) {
                    mWantAutoListen = false;
                    startListeningInternal();
                }
            } else {
                mWantAutoListen = false;
                appendLogLine(getString(R.string.voice_mic_denied));
                Toast.makeText(this, R.string.voice_mic_denied, Toast.LENGTH_LONG).show();
                if (mFinishAfterFirstResult) {
                    mBtnListen.postDelayed(() -> {
                        if (!isFinishing()) {
                            finish();
                        }
                    }, 600);
                }
            }
        }
    }

    private void attemptAutoStartListen() {
        if (!mWantAutoListen) {
            return;
        }
        if (mRecognizer == null || !mBtnListen.isEnabled()) {
            return;
        }
        if (!hasRecordPermission()) {
            if (!mAutoMicPermissionRequested) {
                mAutoMicPermissionRequested = true;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO_AUTO);
            }
            return;
        }
        mWantAutoListen = false;
        if (!mListening) {
            startListeningInternal();
        }
    }

    /** Dış tetikleyici: TTS için kısa süre sonra kapanır */
    private void scheduleFinishAfterOneShotResult() {
        if (!mFinishAfterFirstResult) {
            return;
        }
        if (!mOneShotFinishScheduled.compareAndSet(false, true)) {
            return;
        }
        mBtnListen.removeCallbacks(mFinishAfterResultRunnable);
        mBtnListen.postDelayed(mFinishAfterResultRunnable, 2500);
    }

    private void loadModelAsync() {
        mModelStatus.setText(R.string.voice_model_loading);
        mBtnListen.setEnabled(false);
        mExecutor.execute(() -> {
            try {
                final String path = VoiceModelDownloader.ensureModel(this,
                        (msg, read, total) -> runOnUiThread(() -> {
                            if (total > 0) {
                                int pct = (int) (100L * read / total);
                                mModelStatus.setText(getString(R.string.voice_model_download_pct, pct));
                            } else {
                                mModelStatus.setText(R.string.voice_model_downloading);
                            }
                        }));
                mModel = new Model(path);
                mRecognizer = new Recognizer(mModel, 16000.0f);
                VoiceRecognitionUtils.prepareRecognizerForCommands(mRecognizer);
                runOnUiThread(() -> {
                    mModelStatus.setText(R.string.voice_model_ready);
                    mBtnListen.setEnabled(true);
                    appendLogLine(getString(R.string.voice_model_ready_log));
                    appendLogLine(getString(R.string.voice_grammar_on));
                    attemptAutoStartListen();
                });
            } catch (IOException e) {
                VoiceLog.e(VoiceLog.TAG_ASST, "Model yüklenemedi", e);
                runOnUiThread(() -> {
                    mWantAutoListen = false;
                    mModelStatus.setText(R.string.voice_model_error);
                    mBtnListen.setEnabled(false);
                    appendLogLine(getString(R.string.voice_model_error_log, e.getMessage()));
                    if (mFinishAfterFirstResult) {
                        mBtnListen.postDelayed(() -> {
                            if (!isFinishing()) {
                                finish();
                            }
                        }, 800);
                    }
                });
            }
        });
    }

    private void startListeningInternal() {
        if (mRecognizer == null) {
            return;
        }
        stopListeningSafe(true);
        try {
            mSpeechService = new SpeechService(mRecognizer, 16000.0f);
        } catch (IOException e) {
            appendLogLine(getString(R.string.voice_mic_busy, e.getMessage()));
            Toast.makeText(this, R.string.voice_mic_busy_short, Toast.LENGTH_LONG).show();
            publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_LISTEN_END, "", "");
            return;
        }
        mListening = true;
        mBtnListen.setText(R.string.voice_listen_stop);
        appendLogLine(getString(R.string.voice_listening_started));
        appendLogLine(getString(R.string.voice_logcat_hint));
        if (mVoiceLive != null) {
            mVoiceLive.setText(R.string.voice_listening_prompt);
        }
        mLastPartialLogMs = 0L;
        mLastPartialLogText = "";
        VoiceLog.i(VoiceLog.TAG_ASST,
                "Dinleme başladı (16 kHz mono). Emülatörde mikrofon kapalıysa kısmi metin gelmez.");

        RecognitionListener listener = new RecognitionListener() {
            @Override
            public void onPartialResult(String hypothesis) {
                String t = VoiceRecognitionUtils.extractForDisplay(hypothesis);
                if (mVoiceLive != null) {
                    if (t.isEmpty()) {
                        mVoiceLive.setText(R.string.voice_listening_prompt);
                    } else {
                        mVoiceLive.setText(getString(R.string.voice_live_format, t));
                    }
                }
                if (!t.isEmpty()) {
                    mModelStatus.setText(getString(R.string.voice_partial, t));
                }
                maybeLogPartial(hypothesis, t);
            }

            @Override
            public void onResult(String hypothesis) {
                VoiceLog.i(VoiceLog.TAG_ASST, "onResult raw=" + hypothesis);
                handleHypothesis(hypothesis);
            }

            @Override
            public void onFinalResult(String hypothesis) {
                VoiceLog.i(VoiceLog.TAG_ASST, "onFinalResult raw=" + hypothesis);
                handleHypothesis(hypothesis);
            }

            @Override
            public void onError(Exception exception) {
                VoiceLog.e(VoiceLog.TAG_ASST, "onError", exception);
                appendLogLine(getString(R.string.voice_error, exception.getMessage()));
            }

            @Override
            public void onTimeout() {
                VoiceLog.w(VoiceLog.TAG_ASST, "onTimeout");
                appendLogLine(getString(R.string.voice_timeout));
            }
        };

        if (!mSpeechService.startListening(listener)) {
            VoiceLog.e(VoiceLog.TAG_ASST, "startListening false (zaten aktif?)");
            appendLogLine(getString(R.string.voice_listen_failed));
            mListening = false;
            mBtnListen.setText(R.string.voice_listen_start);
            if (mVoiceLive != null) {
                mVoiceLive.setText(R.string.voice_live_idle);
            }
            publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_LISTEN_END, "", "");
        } else {
            VoiceLog.i(VoiceLog.TAG_ASST, "startListening true");
            publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_LISTEN_START, "", "");
        }
    }

    private void maybeLogPartial(String raw, String display) {
        long now = SystemClock.elapsedRealtime();
        if (display.isEmpty()) {
            return;
        }
        if (display.equals(mLastPartialLogText) && (now - mLastPartialLogMs) < 350) {
            return;
        }
        mLastPartialLogText = display;
        mLastPartialLogMs = now;
        VoiceLog.d(VoiceLog.TAG_ASST, "onPartial display=\"" + display + "\" raw=" + raw);
        publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_PARTIAL, display, "");
    }

    private void stopListeningSafe() {
        stopListeningSafe(false);
    }

    /**
     * @param suppressVoiceOverlayEnd yeniden dinleme başlatırken true: önceki oturum için overlay kapanışı tetiklenmez
     */
    private void stopListeningSafe(boolean suppressVoiceOverlayEnd) {
        boolean hadSession = mSpeechService != null;
        if (mSpeechService != null) {
            mSpeechService.stop();
            mSpeechService.shutdown();
            mSpeechService = null;
        }
        mListening = false;
        mBtnListen.setText(R.string.voice_listen_start);
        if (VoiceModelDownloader.isModelReady(this)) {
            mModelStatus.setText(R.string.voice_model_ready);
        }
        if (hadSession) {
            appendLogLine(getString(R.string.voice_listening_stopped));
        }
        if (mVoiceLive != null) {
            mVoiceLive.setText(R.string.voice_live_idle);
        }
        if (hadSession && !suppressVoiceOverlayEnd) {
            publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_LISTEN_END, "", "");
        }
    }

    private void handleHypothesis(String hypothesis) {
        VoiceHypothesisResolver.Resolution r = VoiceHypothesisResolver.resolve(this, hypothesis);
        if (r.kind == VoiceHypothesisResolver.Kind.EMPTY) {
            VoiceLog.d(VoiceLog.TAG_ASST, "handleHypothesis boş metin, yoksayıldı raw=" + hypothesis);
            return;
        }
        if (r.kind == VoiceHypothesisResolver.Kind.UNKNOWN) {
            VoiceLog.d(VoiceLog.TAG_ASST, "Grafik [unk] (cümle komut listesinde yok)");
            appendLogLine(getString(R.string.voice_grammar_unk));
            publishVoiceResultOverlay(r.heardForOverlay, r.actionForOverlay);
            speak(getString(R.string.voice_not_understood_tts));
            scheduleFinishAfterOneShotResult();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (r.commandText.equals(mLastDispatchedText) && (now - mLastDispatchElapsed) < 900) {
            return;
        }
        mLastDispatchedText = r.commandText;
        mLastDispatchElapsed = now;

        appendLogLine(getString(R.string.voice_heard, r.commandText));
        VoiceLog.i(VoiceLog.TAG_ASST, "Komut metni: \"" + r.commandText + "\"");
        if (r.kind == VoiceHypothesisResolver.Kind.NO_MATCH) {
            appendLogLine(getString(R.string.voice_not_understood));
            publishVoiceResultOverlay(r.heardForOverlay, r.actionForOverlay);
            speak(getString(R.string.voice_not_understood_tts));
            scheduleFinishAfterOneShotResult();
            return;
        }

        appendLogLine(getString(R.string.voice_reply, r.actionForOverlay));
        publishVoiceResultOverlay(r.heardForOverlay, r.actionForOverlay);
        speak(r.actionForOverlay);

        try {
            ContextCompat.startForegroundService(this, new Intent(this, MG4ControlService.class));
            startService(r.commandIntent);
        } catch (Exception e) {
            String err = getString(R.string.voice_command_error, e.getMessage());
            appendLogLine(err);
            publishVoiceResultOverlay(r.commandText, err);
            VoiceLog.e(VoiceLog.TAG_ASST, "Komut gönderilemedi", e);
        }
        scheduleFinishAfterOneShotResult();
    }

    /** Final sonuç satırları (dinleme açıkken kapanış zamanlanmaz). */
    private void publishVoiceResultOverlay(String heard, String actionLine) {
        publishVoiceOverlay(MG4ControlService.VOICE_OVERLAY_MODE_RESULT, heard, actionLine);
    }

    private void publishVoiceOverlay(String mode, String heard, String actionLine) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction(MG4ControlService.ACTION_VOICE_FEEDBACK_OVERLAY);
        i.putExtra(MG4ControlService.EXTRA_VOICE_OVERLAY_MODE, mode);
        i.putExtra(MG4ControlService.EXTRA_VOICE_HEARD, heard != null ? heard : "");
        i.putExtra(MG4ControlService.EXTRA_VOICE_ACTION, actionLine != null ? actionLine : "");
        try {
            ContextCompat.startForegroundService(this, i);
        } catch (Throwable t) {
            try {
                startService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private void speak(String utterance) {
        if (!mTtsReady || mTts == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            mTts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, "drivehub_voice");
        } else {
            mTts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    private void appendLogLine(String line) {
        if (mLog.length() > 0) {
            mLog.append('\n');
        }
        mLog.append(line);
        if (mLog.length() > MAX_LOG_CHARS) {
            mLog.delete(0, mLog.length() - MAX_LOG_CHARS);
        }
        mLogView.setText(mLog.toString());
        mScrollLog.post(() -> mScrollLog.fullScroll(android.view.View.FOCUS_DOWN));
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListeningSafe();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBtnListen.removeCallbacks(mFinishAfterResultRunnable);
        stopListeningSafe();
        if (mRecognizer != null) {
            mRecognizer.close();
            mRecognizer = null;
        }
        if (mModel != null) {
            mModel.close();
            mModel = null;
        }
        mExecutor.shutdown();
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
    }
}
