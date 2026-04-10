package com.drivehub.dort.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Şarj sırasında SOC ekseninde 0.1% adımlarında kW ortalaması toplar.
 * Her bin [socEnd−0.1, socEnd) aralığında örneklenen değerlerin ortalaması; X = bin sonu (% SOC).
 */
public final class ChargingSocPowerAccumulator {

    private static final float SOC_STEP = 0.1f;

    /** Bir sonraki bin üst sınırı (ör. 45.0 → [44.9, 45.0) kapanınca nokta eklenir). */
    private float mBucketUpper = Float.NaN;
    private double mSumMaxDc;
    private double mSumAc;
    private double mSumBatt;
    private int mCountMaxDc;
    private int mCountAc;
    private int mCountBatt;

    private final ArrayList<SocBinPoint> mPointsMaxDc = new ArrayList<>();
    private final ArrayList<SocBinPoint> mPointsAc = new ArrayList<>();
    private final ArrayList<SocBinPoint> mPointsBatt = new ArrayList<>();

    public static final class SocBinPoint {
        public final float socEnd;
        public final float avgKw;

        public SocBinPoint(float socEnd, float avgKw) {
            this.socEnd = socEnd;
            this.avgKw = avgKw;
        }
    }

    public void reset() {
        mBucketUpper = Float.NaN;
        mSumMaxDc = mSumAc = mSumBatt = 0;
        mCountMaxDc = mCountAc = mCountBatt = 0;
        mPointsMaxDc.clear();
        mPointsAc.clear();
        mPointsBatt.clear();
    }

    /**
     * @param soc       anlık SOC (%)
     * @param maxDcKw   istasyon DC teklifi (kW); NaN olabilir
     * @param acKw      AC giriş gücü (kW); şarj yoksa NaN veya ≤0
     * @param battKw    batarya DC gücü (kW); şarjda genelde &lt; 0
     */
    public void onSample(float soc, float maxDcKw, float acKw, float battKw) {
        if (Float.isNaN(soc)) {
            return;
        }
        if (Float.isNaN(mBucketUpper)) {
            mBucketUpper = (float) (Math.floor(soc * 10.0) / 10.0) + SOC_STEP;
        }
        while (soc >= mBucketUpper - 1e-4f && mBucketUpper <= 100.05f) {
            finalizeCurrentBucket();
            mBucketUpper += SOC_STEP;
        }
        if (soc < mBucketUpper) {
            if (!Float.isNaN(maxDcKw)) {
                mSumMaxDc += Math.abs(maxDcKw);
                mCountMaxDc++;
            }
            if (!Float.isNaN(acKw) && acKw > 0f) {
                mSumAc += acKw;
                mCountAc++;
            }
            if (!Float.isNaN(battKw)) {
                float battChg = battKw < 0f ? -battKw : 0f;
                if (battChg > 0f) {
                    mSumBatt += battChg;
                    mCountBatt++;
                }
            }
        }
    }

    private void finalizeCurrentBucket() {
        float xEnd = mBucketUpper;
        if (mCountMaxDc > 0) {
            mPointsMaxDc.add(new SocBinPoint(xEnd, (float) (mSumMaxDc / mCountMaxDc)));
        }
        if (mCountAc > 0) {
            mPointsAc.add(new SocBinPoint(xEnd, (float) (mSumAc / mCountAc)));
        }
        if (mCountBatt > 0) {
            mPointsBatt.add(new SocBinPoint(xEnd, (float) (mSumBatt / mCountBatt)));
        }
        mSumMaxDc = mSumAc = mSumBatt = 0;
        mCountMaxDc = mCountAc = mCountBatt = 0;
    }

    public List<SocBinPoint> getPointsMaxDc() {
        return Collections.unmodifiableList(new ArrayList<>(mPointsMaxDc));
    }

    public List<SocBinPoint> getPointsAc() {
        return Collections.unmodifiableList(new ArrayList<>(mPointsAc));
    }

    public List<SocBinPoint> getPointsBatt() {
        return Collections.unmodifiableList(new ArrayList<>(mPointsBatt));
    }
}
