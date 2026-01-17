package com.bolusmonitor;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    
    private static final String CHANNEL_ID = "bolus_monitor_channel";
    private static final String PREFS_NAME = "BolusMonitorPrefs";
    private static final String KEY_IS_RUNNING = "is_running";
    private static final String KEY_LAST_5_BOLUSES = "last_5_boluses";
    private static final String KEY_LAST_CHECK = "last_check";
    
    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvLastTimestamp;
    private TextView tvLastCheck;
    private SharedPreferences prefs;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        btnStartStop = findViewById(R.id.btnStartStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvLastTimestamp = findViewById(R.id.tvLastTimestamp);
        tvLastCheck = findViewById(R.id.tvLastCheck);
        
        createNotificationChannel();
        updateUI();
        
        btnStartStop.setOnClickListener(v -> {
            boolean isRunning = prefs.getBoolean(KEY_IS_RUNNING, false);
            if (isRunning) {
                stopMonitoring();
            } else {
                startMonitoring();
            }
            updateUI();
        });
        
        // Auto-start if was running before
        if (prefs.getBoolean(KEY_IS_RUNNING, false)) {
            startMonitoring();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }
    
    private void startMonitoring() {
        prefs.edit().putBoolean(KEY_IS_RUNNING, true).apply();
        
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                BolusCheckWorker.class, 
                5, 
                TimeUnit.MINUTES
            )
            .build();
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "bolus_monitor_work",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        );
    }
    
    private void stopMonitoring() {
        prefs.edit().putBoolean(KEY_IS_RUNNING, false).apply();
        WorkManager.getInstance(this).cancelUniqueWork("bolus_monitor_work");
    }
    
    private void updateUI() {
        boolean isRunning = prefs.getBoolean(KEY_IS_RUNNING, false);
        String last5Boluses = prefs.getString(KEY_LAST_5_BOLUSES, "");
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        
        if (isRunning) {
            btnStartStop.setText("STOP");
            btnStartStop.setBackgroundColor(0xFFE53935); // Red
            tvStatus.setText("Status: Running ✅");
            tvStatus.setTextColor(0xFF4CAF50); // Green
        } else {
            btnStartStop.setText("START");
            btnStartStop.setBackgroundColor(0xFF4CAF50); // Green
            tvStatus.setText("Status: Stopped ❌");
            tvStatus.setTextColor(0xFFE53935); // Red
        }
        
        if (!last5Boluses.isEmpty()) {
            // Parse first bolus: timestamp,insulin
            String[] boluses = last5Boluses.split("\\|");
            if (boluses.length > 0 && !boluses[0].isEmpty()) {
                String[] parts = boluses[0].split(",");
                if (parts.length >= 2) {
                    long timestamp = Long.parseLong(parts[0]);
                    String insulin = parts[1];
                    tvLastTimestamp.setText("Last Bolus: " + formatTimestamp(timestamp) + 
                        " (" + insulin + "U)\nTotal saved: " + boluses.length + " boluses");
                } else {
                    tvLastTimestamp.setText("Last Bolus: Parse error");
                }
            } else {
                tvLastTimestamp.setText("Last Bolus: None");
            }
        } else {
            tvLastTimestamp.setText("Last Bolus: None");
        }
        
        if (lastCheck > 0) {
            tvLastCheck.setText("Last Check: " + formatDate(lastCheck));
        } else {
            tvLastCheck.setText("Last Check: Never");
        }
    }
    
    private String formatTimestamp(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date(timestamp));
    }
    
    private String formatDate(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(new Date(timestamp));
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Bolus Notifications";
            String description = "Notifications for new insulin bolus";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableVibration(true);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
