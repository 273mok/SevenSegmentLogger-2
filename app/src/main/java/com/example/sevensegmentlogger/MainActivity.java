package com.example.sevensegmentlogger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final SevenSegmentDecoder.Settings config = new SevenSegmentDecoder.Settings();
    private final RectF roi = new RectF(.18f, .35f, .82f, .65f);
    private CameraOverlayView cameraView;
    private TextView currentText, statusText, countText, fileText;
    private Button recordButton, exportButton;
    private boolean recording = false;
    private BufferedWriter writer;
    private File currentFile;
    private long sessionStart = 0;
    private int recordCount = 0;
    private String candidate = null, lastRecorded = null;
    private int candidateFrames = 0, stableFrames = 1;
    private static final long RECOGNITION_INTERVAL_MS = 500L;
    private long lastFrameMs = 0;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else statusText.setText("카메라 권한이 필요합니다. 설정에서 권한을 허용해주세요.");
            });
    private final ActivityResultLauncher<Intent> csvDestination = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                if (result.getData().getData() == null || currentFile == null) return;
                try (FileInputStream in = new FileInputStream(currentFile);
                     OutputStream out = getContentResolver().openOutputStream(result.getData().getData())) {
                    if (out == null) throw new IOException("출력 스트림을 열 수 없음");
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) >= 0) out.write(buffer, 0, len);
                    toast("CSV 파일이 저장되었습니다.");
                } catch (IOException e) { toast("CSV 내보내기 오류: " + e.getMessage()); }
            });

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        setContentView(root);

        TextView header = label("7세그먼트 실시간 기록기", 22, true);
        root.addView(header);
        cameraView = new CameraOverlayView(this);
        root.addView(cameraView, new LinearLayout.LayoutParams(-1, 0, 1f));
        currentText = label("인식 값: —", 25, true);
        root.addView(currentText);
        statusText = label("카메라 준비 중 · 0.5초마다 인식", 13, false);
        root.addView(statusText);
        countText = label("기록: 0개", 13, false);
        root.addView(countText);
        fileText = label("측정 시작 시 CSV 파일이 자동 생성됩니다.", 11, false);
        root.addView(fileText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions);
        recordButton = new Button(this);
        recordButton.setText("측정 시작");
        actions.addView(recordButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        recordButton.setOnClickListener(v -> { if (recording) stopRecording(); else startRecording(); });
        exportButton = new Button(this);
        exportButton.setText("CSV 저장");
        actions.addView(exportButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        exportButton.setOnClickListener(v -> exportCsv());

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(240)));
        LinearLayout settings = new LinearLayout(this);
        settings.setPadding(dp(2), 0, dp(2), dp(12));
        settings.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(settings);
        settings.addView(label("인식 설정  ·  화면의 청록색 상자 안에 숫자를 맞추세요", 13, true));
        addSeek(settings, "자릿수", 1, 8, 4, n -> { config.digits = n; resetCandidate(); });
        addSeek(settings, "밝기 임계값", 0, 255, 125, n -> config.threshold = n);
        addSeek(settings, "변경 확정 프레임", 1, 10, 1, n -> stableFrames = n);
        settings.addView(label("0.5초마다 한 번 인식합니다. 인식 실패는 —로 표시됩니다.", 12, false));
        CheckBox bright = new CheckBox(this);
        bright.setText("밝은 세그먼트 (검은 LCD 숫자는 해제)");
        bright.setChecked(true);
        bright.setOnCheckedChangeListener((button, checked) -> config.litIsBright = checked);
        settings.addView(bright);
        CheckBox decimal = new CheckBox(this);
        decimal.setText("소수점 판독");
        decimal.setChecked(true);
        decimal.setOnCheckedChangeListener((button, checked) -> config.decimalEnabled = checked);
        settings.addView(decimal);
        settings.addView(label("인식 영역(화면 비율 %) — x/y는 왼쪽·상단 시작점", 12, true));
        // Starting position and size are represented in screen-normalized coordinates.
        final int[] r = {18, 35, 64, 30};
        addSeek(settings, "X", 0, 90, 18, n -> { r[0] = n; updateRoi(r); });
        addSeek(settings, "Y", 0, 90, 35, n -> { r[1] = n; updateRoi(r); });
        addSeek(settings, "너비", 10, 100, 64, n -> { r[2] = n; updateRoi(r); });
        addSeek(settings, "높이", 10, 100, 30, n -> { r[3] = n; updateRoi(r); });
    }

    private interface IntConsumer { void accept(int v); }
    private void addSeek(LinearLayout container, String title, int min, int max,
                         int initial, IntConsumer setter) {
        TextView description = label(title + ": " + initial, 12, false);
        container.addView(description);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        container.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                int value = p + min;
                description.setText(title + ": " + value);
                setter.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
    }

    private void updateRoi(int[] r) {
        float x = r[0] / 100f, y = r[1] / 100f;
        roi.set(x, y, Math.min(1f, x + r[2] / 100f), Math.min(1f, y + r[3] / 100f));
        cameraView.setRegion(roi);
        resetCandidate();
    }

    private TextView label(String t, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(t);
        text.setTextColor(Color.rgb(24, 35, 55));
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(dp(2), dp(5), dp(2), dp(4));
        return text;
    }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(imageExecutor, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
                statusText.setText("카메라 연결됨 · 0.5초마다 인식 · 숫자 영역을 맞추세요.");
            } catch (Exception ex) { statusText.setText("카메라 연결 실패: " + ex.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameMs < RECOGNITION_INTERVAL_MS) return; // at most one recognition attempt per 500 ms
            lastFrameMs = now;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer yBuffer = plane.getBuffer();
            int width = image.getWidth(), height = image.getHeight();
            int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
            int[] colors = new int[width * height];
            for (int y = 0; y < height; y++) {
                int row = y * rowStride;
                for (int x = 0; x < width; x++) {
                    int lum = yBuffer.get(row + x * pixelStride) & 0xff;
                    colors[y * width + x] = Color.rgb(lum, lum, lum);
                }
            }
            Bitmap base = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
            int rotation = image.getImageInfo().getRotationDegrees();
            Bitmap displayed = base;
            if (rotation != 0) {
                Matrix transform = new Matrix();
                transform.postRotate(rotation);
                displayed = Bitmap.createBitmap(base, 0, 0, width, height, transform, true);
                base.recycle();
            }
            // Snapshot on camera executor. Settings changed on UI thread; volatile-free UI changes
            // only affect the next frame, not persistent application data.
            RectF region = new RectF(roi);
            SevenSegmentDecoder.Settings opts = new SevenSegmentDecoder.Settings();
            opts.digits = config.digits;
            opts.threshold = config.threshold;
            opts.litIsBright = config.litIsBright;
            opts.decimalEnabled = config.decimalEnabled;
            String detected = SevenSegmentDecoder.decode(displayed, region, opts);
            Bitmap imageForUi = displayed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { imageForUi.recycle(); return; }
                cameraView.setFrame(imageForUi);
                cameraView.setResult(detected);
                currentText.setText("인식 값 (0.5초): " + (detected == null ? "— (인식 실패)" : detected));
                onRecognition(detected);
            });
        } catch (Exception ex) {
            runOnUiThread(() -> statusText.setText("인식 오류: " + ex.getMessage()));
        } finally { image.close(); }
    }

    private void onRecognition(String value) {
        if (value == null) { resetCandidate(); return; }
        if (!value.equals(candidate)) { candidate = value; candidateFrames = 1; }
        else candidateFrames++;
        if (recording && candidateFrames >= stableFrames && !value.equals(lastRecorded)) {
            long elapsed = SystemClock.elapsedRealtime() - sessionStart;
            long wallMs = System.currentTimeMillis();
            try {
                writer.write(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(wallMs))
                        + "," + String.format(Locale.US, "%.3f", elapsed / 1000d)
                        + "," + value + "\n");
                writer.flush(); // each accepted change persists to device storage
                lastRecorded = value;
                recordCount++;
                countText.setText("기록: " + recordCount + "개 · 마지막 값: " + value);
                statusText.setText("0.5초 간격 인식 · 값이 변경되면 자동 기록됩니다.");
            } catch (IOException ex) {
                statusText.setText("기록 실패: " + ex.getMessage());
                stopRecording();
            }
        }
    }
    private void resetCandidate() { candidate = null; candidateFrames = 0; }

    private void startRecording() {
        try {
            File dir = new File(getFilesDir(), "measurements");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("저장 폴더 생성 실패");
            String name = "sevenseg_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            currentFile = new File(dir, name);
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(currentFile), StandardCharsets.UTF_8));
            writer.write("timestamp_utc,elapsed_seconds,value\n");
            writer.flush();
            sessionStart = SystemClock.elapsedRealtime();
            recordCount = 0;
            lastRecorded = null;
            resetCandidate();
            recording = true;
            recordButton.setText("측정 중지");
            countText.setText("기록: 0개");
            fileText.setText("자동 저장 중: " + name);
            statusText.setText("측정 중 · 자동 기록 활성화");
        } catch (IOException ex) {
            toast("기록 시작 실패: " + ex.getMessage());
            closeWriter();
        }
    }

    private void stopRecording() {
        recording = false;
        closeWriter();
        recordButton.setText("측정 시작");
        statusText.setText("측정 종료 · CSV 저장 버튼으로 내보내세요.");
    }

    private void closeWriter() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) { }
            writer = null;
        }
    }

    private void exportCsv() {
        if (currentFile == null || !currentFile.isFile()) {
            toast("먼저 측정을 시작해주세요."); return;
        }
        try { if (writer != null) writer.flush(); }
        catch (IOException e) { toast("파일 동기화 실패: " + e.getMessage()); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, currentFile.getName());
        csvDestination.launch(intent);
    }

    @Override protected void onDestroy() {
        stopRecording();
        imageExecutor.shutdown();
        super.onDestroy();
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
}
