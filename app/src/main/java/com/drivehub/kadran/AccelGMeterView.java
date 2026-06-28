package com.drivehub.kadran;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.drivehub.dort.R;

/**
 * Boyuna (portrait) ve yanal (lateral) ivmeyi m/s² olarak alır; daire içinde G topu çizer.
 * Ekranda: yanal = sağ/sol, boyuna = ileri ivme yukarı.
 */
public class AccelGMeterView extends View {

    private static final float GRAVITY_MS2 = 9.80665f;
    /** Yanal (sol/sağ) eksende çizim ölçeği: |gLat| bu değeri aşarsa kenarda kalır. */
    private static final float MAX_G_SCALE_X = 0.6f;
    /** Boyuna (ileri/fren, ekranda dikey) eksende çizim ölçeği: |gLong| bu değeri aşarsa kenarda kalır. */
    private static final float MAX_G_SCALE_Y = 0.6f;

    private float accelPortraitMs2;
    private float accelLateralMs2;

    private final Paint paintRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AccelGMeterView(Context context) {
        super(context);
        init();
    }

    public AccelGMeterView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AccelGMeterView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintRing.setStyle(Paint.Style.STROKE);
        paintRing.setStrokeWidth(dp(1.5f));
        paintCross.setStyle(Paint.Style.STROKE);
        paintCross.setStrokeWidth(dp(1f));
        paintDot.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** İvme bileşenleri m/s² (Dort ile uyumlu). */
    public void setAccelerationMs2(float accelPortraitMs2, float accelLateralMs2) {
        this.accelPortraitMs2 = accelPortraitMs2 * -1f;
        this.accelLateralMs2 = accelLateralMs2;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float radius = Math.min(cx, cy) - dp(6f);

        paintRing.setColor(ContextCompat.getColor(getContext(), R.color.track_label));
        paintCross.setColor(ContextCompat.getColor(getContext(), R.color.track_label));
        paintDot.setColor(ContextCompat.getColor(getContext(), R.color.track_speed_big));

        canvas.drawCircle(cx, cy, radius, paintRing);

        canvas.drawLine(cx - radius * 0.92f, cy, cx + radius * 0.92f, cy, paintCross);
        canvas.drawLine(cx, cy - radius * 0.92f, cx, cy + radius * 0.92f, paintCross);

        float gLat = accelLateralMs2 / GRAVITY_MS2;
        float gLong = accelPortraitMs2 / GRAVITY_MS2;
        float gx = clamp(gLat, -MAX_G_SCALE_X, MAX_G_SCALE_X);
        float gy = clamp(-gLong, -MAX_G_SCALE_Y, MAX_G_SCALE_Y);
        float scaleX = radius * 0.88f / MAX_G_SCALE_X;
        float scaleY = radius * 0.88f / MAX_G_SCALE_Y;
        float dotX = cx + gx * scaleX;
        float dotY = cy + gy * scaleY;
        canvas.drawCircle(dotX, dotY, dp(5f), paintDot);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }
}
