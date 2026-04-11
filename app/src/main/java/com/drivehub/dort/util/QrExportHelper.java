package com.drivehub.dort.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * UTF-8 metin için QR bitmap üretir. Kapasite aşılırsa {@link WriterException} fırlatır.
 */
public final class QrExportHelper {

    private QrExportHelper() {}

    public static Bitmap encodeQr(String utf8Text, int sizePixels) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(utf8Text, BarcodeFormat.QR_CODE, sizePixels, sizePixels, hints);
        int w = bitMatrix.getWidth();
        int h = bitMatrix.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }
}
