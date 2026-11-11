package com.example.sevensegmentlogger;

import android.graphics.Bitmap;
import android.graphics.RectF;
import java.util.Arrays;

/** Four-digit dark LCD / seven-segment decoder. The ROI should tightly contain the digits,
 * excluding surrounding case, LCD borders, units and glare when possible. */
public final class SevenSegmentDecoder {
    private SevenSegmentDecoder() {}
    public static final class Settings {
        public int digits = 4;
        public int threshold = 125;
        public boolean litIsBright = false;
        public boolean decimalEnabled = true;
        public boolean fixedDecimalAfterFirst = true;
    }
    private static final int[] PATTERNS = {
        0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110,
        0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111
    };
    // Regions centered well inside the seven segment strokes, excluding corners.
    // x1,y1,x2,y2 in digit-cell coordinates (digit itself occupies approx. 80% of cell).
    private static final float[][] SEG = {
        {.27f,.055f,.62f,.12f}, // top
        {.70f,.19f,.79f,.38f},  // upper right
        {.70f,.63f,.79f,.81f},  // lower right
        {.27f,.87f,.62f,.935f},// bottom
        {.065f,.63f,.155f,.81f},// lower left
        {.065f,.19f,.155f,.38f},// upper left
        {.27f,.47f,.62f,.54f}  // middle
    };
    public static String decode(Bitmap b, RectF roi, Settings s) {
        if (b == null || roi == null || s.digits < 1 || s.digits > 8) return null;
        int left = Math.max(0, Math.round(roi.left*b.getWidth()));
        int right = Math.min(b.getWidth(), Math.round(roi.right*b.getWidth()));
        int top = Math.max(0, Math.round(roi.top*b.getHeight()));
        int bottom = Math.min(b.getHeight(), Math.round(roi.bottom*b.getHeight()));
        int w = right-left, h=bottom-top;
        if (w < s.digits*15 || h < 30) return null;
        // Determine threshold from this frame's crop rather than hard-coding a brightness value.
        // Trim dark and bright outliers (reflection and screen grain).
        int[] histogram = new int[256]; int samples = 0;
        for(int y=top;y<bottom;y+=2) for(int x=left;x<right;x+=2) {
            histogram[gray(b.getPixel(x,y))]++; samples++;
        }
        int p20=percentile(histogram,samples,.20f);
        int p75=percentile(histogram,samples,.75f);
        if(p75-p20 < 18) return null; // insufficient contrast
        int autoThreshold = Math.round(p20 + (p75-p20)*.42f);
        // The manual threshold is retained as a fine-tuning offset (125 = automatic).
        int threshold = clamp(autoThreshold + s.threshold - 125, 12, 245);
        StringBuilder result = new StringBuilder();
        float cellW = w/(float)s.digits;
        for(int d=0;d<s.digits;d++) {
            float x0=left+d*cellW;
            float[] strength = new float[7];
            for(int j=0;j<7;j++) strength[j]=darkFraction(b,x0,top,cellW,h,SEG[j],threshold,s.litIsBright);
            int bestDigit=-1; float bestScore=Float.MAX_VALUE, second=Float.MAX_VALUE;
            for(int k=0;k<10;k++) {
                float score=0;
                for(int j=0;j<7;j++) {
                    boolean expected=(PATTERNS[k]&(1<<j)) != 0;
                    float delta=strength[j]-(expected?.72f:.18f);
                    score+=delta*delta;
                }
                if(score<bestScore){second=bestScore;bestScore=score;bestDigit=k;}
                else if(score<second){second=score;}
            }
            // Reject uncertain/noisy digits rather than silently recording the wrong value.
            if(bestScore>1.15f || second-bestScore<.055f) return null;
            result.append(bestDigit);
            if(s.decimalEnabled && d<s.digits-1) {
                if(s.fixedDecimalAfterFirst) {
                    if(d==0) result.append('.');
                } else {
                    float dp=darkFraction(b,x0,top,cellW,h,
                            new float[]{.81f,.79f,.98f,.95f},threshold,s.litIsBright);
                    if(dp>.53f) result.append('.');
                }
            }
        }
        return result.toString();
    }
    private static float darkFraction(Bitmap b,float x,float y,float w,float h,float[] box,int th,boolean bright) {
        int total=0,active=0;
        // Sample a filled box, not just one-pixel lines; robust to small alignment errors.
        for(int iy=0;iy<6;iy++) for(int ix=0;ix<7;ix++) {
            int px=clamp(Math.round(x+w*(box[0]+(box[2]-box[0])*(ix+.5f)/7f)),0,b.getWidth()-1);
            int py=clamp(Math.round(y+h*(box[1]+(box[3]-box[1])*(iy+.5f)/6f)),0,b.getHeight()-1);
            int l=gray(b.getPixel(px,py));
            if(bright ? l>th : l<th) active++;
            total++;
        }
        return active/(float)total;
    }
    private static int percentile(int[] h,int count,float p){int n=0;int target=Math.round(count*p);for(int i=0;i<256;i++){n+=h[i];if(n>=target)return i;}return 255;}
    private static int gray(int c){return (77*((c>>16)&255)+150*((c>>8)&255)+29*(c&255))>>8;}
    private static int clamp(int x,int a,int b){return Math.max(a,Math.min(x,b));}
}
