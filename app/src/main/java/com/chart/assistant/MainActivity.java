package com.chart.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private EditText apiKeyInput;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 80, 50, 50);

        TextView title = new TextView(this);
        title.setText("Chart AI Floating Assistant");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 30);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("1. Apni Gemini API Key paste karein:\n2. 'Start AI Overlay' dabayein aur Screen Recording permission allow karein.");
        desc.setTextSize(14);
        desc.setPadding(0, 0, 0, 30);
        layout.addView(desc);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("Paste Gemini API Key here (AIza...)");
        apiKeyInput.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        SharedPreferences prefs = getSharedPreferences("ChartAI", MODE_PRIVATE);
        apiKeyInput.setText(prefs.getString("api_key", ""));
        layout.addView(apiKeyInput);

        Button startBtn = new Button(this);
        startBtn.setText("Start AI Overlay");
        layout.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop AI Overlay");
        layout.addView(stopBtn);

        setContentView(layout);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Pehle Gemini API Key dalein!", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("api_key", key).apply();

            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Overlay permission ON karke wapas aayein", Toast.LENGTH_LONG).show();
            } else {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        });

        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingAiService.class));
            Toast.makeText(this, "AI Overlay Band Ho Gaya", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, FloatingAiService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "AI Overlay Shuru! Ab apna Trading Chart kholein.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
    }
}

