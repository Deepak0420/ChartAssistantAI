package com.chart.assistant;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingAiService extends Service {
    private WindowManager windowManager;
    private LinearLayout floatingView;
    private TextView statusText;
    private TextView detailText;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isRunning = false;
    private String apiKey = "";

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            captureAndAnalyze();
            handler.postDelayed(this, 8000); // Har 8 second mein auto-scan
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("ChartAI", MODE_PRIVATE);
        apiKey = prefs.getString("api_key", "");
        createNotificationChannel();
        startForeground(1, buildNotification());
        setupFloatingWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && !isRunning) {
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            setupVirtualDisplay();
            isRunning = true;
            handler.postDelayed(scanRunnable, 2000);
        }
        return START_STICKY;
    }

    private void setupFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = new LinearLayout(this);
        floatingView.setOrientation(LinearLayout.VERTICAL);
        floatingView.setBackgroundColor(Color.parseColor("#CC111111"));
        floatingView.setPadding(28, 20, 28, 20);

        statusText = new TextView(this);
        statusText.setText("AI READY - Scanning...");
        statusText.setTextColor(Color.YELLOW);
        statusText.setTextSize(15);
        floatingView.addView(statusText);

        detailText = new TextView(this);
        detailText.setText("Chart kholein (75%+ rule active)");
        detailText.setTextColor(Color.WHITE);
        detailText.setTextSize(12);
        floatingView.addView(detailText);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 180;

        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingView, params);
    }

    private void setupVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels / 2;
        screenHeight = metrics.heightPixels / 2;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChartCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
    }

    private void captureAndAnalyze() {
        if (imageReader == null) return;
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            executor.execute(() -> sendToGemini(base64Image));
        } catch (Exception e) {
            if (image != null) image.close();
        }
    }

    private void sendToGemini(String base64Image) {
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String prompt = "You are a strict binary options chart assistant. Analyze this chart screenshot (candles, RSI, Bollinger Bands, expiry timer). " +
                    "Rule: Only signal UP or DOWN if winning probability is 75% or higher with clear breakout/rejection and safe expiry time. Otherwise say WAIT. " +
                    "Reply in EXACTLY 2 short lines in Hinglish:\nLine 1: SIGNAL: UP (XX%) or DOWN (XX%) or WAIT (XX%)\nLine 2: 1 short reason (max 8 words).";

            JSONObject inlineData = new JSONObject();
            inlineData.put("mime_type", "image/jpeg");
            inlineData.put("data", base64Image);

            JSONObject imagePart = new JSONObject();
            imagePart.put("inline_data", inlineData);

            JSONObject textPart = new JSONObject();
            textPart.put("text", prompt);

            JSONArray parts = new JSONArray();
            parts.put(textPart);
            parts.put(imagePart);

            JSONObject content = new JSONObject();
            content.put("parts", parts);

            JSONArray contents = new JSONArray();
            contents.put(content);

            JSONObject body = new JSONObject();
            body.put("contents", contents);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            br.close();

            JSONObject jsonResponse = new JSONObject(response.toString());
            String reply = jsonResponse.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text").trim();

            String[] lines = reply.split("\n");
            String line1 = lines.length > 0 ? lines[0] : "WAIT";
            String line2 = lines.length > 1 ? lines[1] : "";

            handler.post(() -> {
                statusText.setText(line1);
                detailText.setText(line2);
                if (line1.toUpperCase().contains("UP")) {
                    statusText.setTextColor(Color.GREEN);
                } else if (line1.toUpperCase().contains("DOWN")) {
                    statusText.setTextColor(Color.parseColor("#FF5252"));
                } else {
                    statusText.setTextColor(Color.YELLOW);
                }
            });
        } catch (Exception e) {
            handler.post(() -> detailText.setText("Scan error - retrying..."));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("chart_ai", "Chart AI Overlay", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "chart_ai")
                : new Notification.Builder(this);
        return builder.setContentTitle("Chart AI Running")
                .setContentText("Monitoring screen for 75%+ setups")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacks(scanRunnable);
        if (floatingView != null) windowManager.removeView(floatingView);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
