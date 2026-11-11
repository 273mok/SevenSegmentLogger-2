package com.example.sevensegmentlogger;

import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * Fast threshold-based seven-segment decoder for a fixed, tightly cropped display.
 * Digit order: a (top), b (right upper), c (right lower), d (bottom),
 * e (left lower), f (left upper), g (middle).
 * Per digit rectangles are normalized to the left 84% of its cell;
 * the remaining width is reserved for the decimal point.
 */
public final class SevenSegmentDecoder {
    private SevenSegmentDecoder() {}

    public static final class Settings {
        public int digits = 4;
        public int threshold = 125;
        public boolean litIsBright = true;
        public boolean decimalEnabled = true;
    }

    // a,b,c,d,e,f,g bit order
    private static final int[] PATTERNS = {
        0b0111111, // 0
        0b0000110, // 1
        0b1011011, // 2
        0b1001111, // 3
        0b1100110, // 4
        0b1101101, // 5
        0b1111101, // 6
        0b0000111, // 7
        0b1111111, // 8
        0b1101111  // 9
    };

    public static String decode(Bitmap bitmap, RectF roi, Settings s) {
        if (bitmap == null || roi == null || s.digits < 1 || s.digits > 8) return null;
        int left = Math.max(0, Math.round(roi.left * bitmap.getWidth()));
        int right = Math.min(bitmap.getWidth(), Math.round(roi.right * bitmap.getWidth()));
        int top = Math.max(0, Math.round(roi.top * bitmap.getHeight()));
        int bottom = Math.min(bitmap.getHeight(), Math.round(roi.bottom * bitmap.getHeight()));
        if (right - left < s.digits * 12 || bottom - top < 20) return null;
        int rw = right - left, rh = bottom - top;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.digits; i++) {
            float cellX = left + rw * (i / (float) s.digits);
            float cellW = rw / (float) s.digits;
            int pattern = 0;
            // Samples along the segment's center line, away from junctions.
            float[][] segments = {
                {0.27f, 0.12f, 0.69f, 0.12f}, // a
                {0.77f, 0.22f, 0.77f, 0.41f}, // b
                {0.77f, 0.59f, 0.77f, 0.78f}, // c
                {0.27f, 0.88f, 0.69f, 0.88f}, // d
                {0.17f, 0.59f, 0.17f, 0.78f}, // e
                {0.17f, 0.22f, 0.17f, 0.41f}, // f
                {0.27f, 0.50f, 0.69f, 0.50f}  // g
            };
            for (int seg = 0; seg < 7; seg++) {
                float[] pos = segments[seg];
                float active = sampleLine(bitmap, cellX, top, cellW, rh,
                        pos[0], pos[1], pos[2], pos[3], s);
                if (active >= 0.53f) pattern |= 1 << seg;
            }
            int digit = -1;
            for (int k = 0; k < 10; k++) {
                if (PATTERNS[k] == pattern) { digit = k; break; }
            }
            // Blank leading cells are permitted; noise and invalid non-leading digits are rejected.
            if (digit < 0 && pattern == 0 && out.length() == 0) continue;
            if (digit < 0) return null;
            out.append(digit);
            if (s.decimalEnabled && i < s.digits - 1) {
                float dp = sampleArea(bitmap, cellX, top, cellW, rh,
                        0.86f, 0.77f, 0.98f, 0.92f, s);
                if (dp >= 0.55f) out.append('.');
            }
        }
        if (out.length() == 0) return null;
        String value = out.toString();
        int first = value.indexOf('.');
        if (first != value.lastIndexOf('.')) return null;
        return value;
    }

    private static float sampleLine(Bitmap b, float x, float y, float w, float h,
                                    float sx, float sy, float ex, float ey, Settings s) {
        float sum = 0;
        for (int j = 0; j < 9; j++) {
            float t = j / 8f;
            int px = clamp(Math.round(x + w * (sx + (ex - sx) * t)), 0, b.getWidth() - 1);
            int py = clamp(Math.round(y + h * (sy + (ey - sy) * t)), 0, b.getHeight() - 1);
            sum += sampleNeighborhood(b, px, py, s);
        }
        return sum / 9f;
    }

    private static float sampleArea(Bitmap b, float x, float y, float w, float h,
                                    float x1, float y1, float x2, float y2, Settings s) {
        float count = 0, lit = 0;
        for (int iy = 0; iy < 5; iy++) {
            for (int ix = 0; ix < 5; ix++) {
                int px = clamp(Math.round(x + w * (x1 + (x2 - x1) * ix / 4f)), 0, b.getWidth() - 1);
                int py = clamp(Math.round(y + h * (y1 + (y2 - y1) * iy / 4f)), 0, b.getHeight() - 1);
                lit += isLit(b.getPixel(px, py), s) ? 1 : 0;
                count++;
            }
        }
        return lit / count;
    }

    private static float sampleNeighborhood(Bitmap b, int x, int y, Settings s) {
        int lit = 0, count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = clamp(x + dx, 0, b.getWidth() - 1);
                int py = clamp(y + dy, 0, b.getHeight() - 1);
                if (isLit(b.getPixel(px, py), s)) lit++;
                count++;
            }
        }
        return lit / (float) count;
    }

    private static boolean isLit(int color, Settings s) {
        int r = (color >> 16) & 255;
        int g = (color >> 8) & 255;
        int b = color & 255;
        int luma = (77 * r + 150 * g + 29 * b) >> 8;
        return s.litIsBright ? luma >= s.threshold : luma <= s.threshold;
    }

    private static int clamp(int x, int a, int b) { return Math.max(a, Math.min(x, b)); }
}
