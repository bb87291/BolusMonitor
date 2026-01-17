package com.bolusmonitor;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BolusCheckWorker extends Worker {
    
    private static final String TAG = "BolusCheckWorker";
    private static final String API_URL_TREATMENTS = "http://127.0.0.1:17580/treatments.json?count=5";
    private static final String API_URL_IOB = "http://127.0.0.1:17580/pebble";
    private static final String CHANNEL_ID = "bolus_monitor_channel";
    private static final String PREFS_NAME = "BolusMonitorPrefs";
    private static final String KEY_LAST_5_BOLUSES = "last_5_boluses";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final double MIN_BOLUS_UNITS = 2.0;
    
    public BolusCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    
    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting bolus check...");
        
        try {
            // Get treatments data (last 5)
            String jsonTreatments = fetchAPI(API_URL_TREATMENTS);
            
            if (jsonTreatments == null || jsonTreatments.isEmpty()) {
                Log.e(TAG, "Empty response from treatments API");
                return Result.retry();
            }
            
            Log.d(TAG, "Treatments Response: " + jsonTreatments.substring(0, Math.min(200, jsonTreatments.length())));
            
            // Extract all boluses from response
            String newBoluses = extractLast5Boluses(jsonTreatments);
            
            if (newBoluses.isEmpty()) {
                Log.e(TAG, "No boluses found in response");
                return Result.retry();
            }
            
            Log.d(TAG, "Extracted boluses: " + newBoluses);
            
            // Get saved boluses
            SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedBoluses = prefs.getString(KEY_LAST_5_BOLUSES, "");
            
            Log.d(TAG, "Saved boluses: " + savedBoluses);
            
            // Update last check time
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            
            // Check if there's a new bolus (first entry changed)
            if (!newBoluses.equals(savedBoluses)) {
                // Find which bolus is new
                String[] newBolusArray = newBoluses.split("\\|");
                String[] savedBolusArray = savedBoluses.split("\\|");
                
                String firstNewBolus = newBolusArray.length > 0 ? newBolusArray[0] : "";
                String firstSavedBolus = savedBolusArray.length > 0 ? savedBolusArray[0] : "";
                
                if (!firstNewBolus.equals(firstSavedBolus) && !firstNewBolus.isEmpty()) {
                    // Parse first bolus: timestamp,insulin
                    String[] parts = firstNewBolus.split(",");
                    if (parts.length >= 2) {
                        long timestamp = Long.parseLong(parts[0]);
                        double insulin = Double.parseDouble(parts[1]);
                        
                        Log.d(TAG, "NEW BOLUS DETECTED! Timestamp: " + timestamp + ", Insulin: " + insulin);
                        
                        // Check if insulin >= 2 units
                        if (insulin >= MIN_BOLUS_UNITS) {
                            // Get current IOB
                            double iob = getCurrentIOB();
                            
                            // Show notification
                            showNotification(timestamp, insulin, iob);
                            
                            Log.d(TAG, "Notification sent for bolus >= 2U");
                        } else {
                            Log.d(TAG, "Bolus < 2U, no notification");
                        }
                        
                        // Save new boluses list
                        prefs.edit().putString(KEY_LAST_5_BOLUSES, newBoluses).apply();
                    }
                }
                
                return Result.success();
            } else {
                Log.d(TAG, "No new bolus (same as saved)");
                return Result.success();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking bolus", e);
            return Result.retry();
        }
    }
    
    private String fetchAPI(String apiUrl) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "HTTP Response Code: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                
                return response.toString();
            } else {
                Log.e(TAG, "HTTP error code: " + responseCode);
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error fetching API", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    private String extractLast5Boluses(String json) {
        try {
            // Find all boluses with timestamp and insulin amount
            // Format: timestamp1,insulin1|timestamp2,insulin2|...
            StringBuilder result = new StringBuilder();
            
            // Regex to find: "created_at":timestamp and "insulin":amount
            Pattern patternTimestamp = Pattern.compile("\"created_at\"[:\\s]*(\\d+)");
            Pattern patternInsulin = Pattern.compile("\"insulin\"[:\\s]*([0-9.]+)");
            
            // Split by objects
            String[] objects = json.split("\\},\\{");
            int count = 0;
            
            for (String obj : objects) {
                if (count >= 5) break;
                
                Matcher matcherTimestamp = patternTimestamp.matcher(obj);
                Matcher matcherInsulin = patternInsulin.matcher(obj);
                
                if (matcherTimestamp.find() && matcherInsulin.find()) {
                    String timestamp = matcherTimestamp.group(1);
                    String insulin = matcherInsulin.group(1);
                    
                    if (count > 0) result.append("|");
                    result.append(timestamp).append(",").append(insulin);
                    count++;
                }
            }
            
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error extracting boluses", e);
            return "";
        }
    }
    
    private double getCurrentIOB() {
        try {
            String jsonIOB = fetchAPI(API_URL_IOB);
            
            if (jsonIOB == null || jsonIOB.isEmpty()) {
                Log.e(TAG, "Empty response from IOB API");
                return 0.0;
            }
            
            // Extract IOB: "iob":{"iob":2.5,...}
            Pattern pattern = Pattern.compile("\"iob\"[:\\s]*\\{[^}]*\"iob\"[:\\s]*([0-9.]+)");
            Matcher matcher = pattern.matcher(jsonIOB);
            
            if (matcher.find()) {
                String iobStr = matcher.group(1);
                double iob = Double.parseDouble(iobStr);
                Log.d(TAG, "Current IOB: " + iob);
                return iob;
            }
            
            Log.e(TAG, "Could not extract IOB");
            return 0.0;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting IOB", e);
            return 0.0;
        }
    }
    
    private void showNotification(long timestamp, double insulin, double iob) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timeStr = sdf.format(new Date(timestamp));
        
        String title = String.format(Locale.getDefault(), "🎯 NEW BOLUS: %.1fU", insulin);
        String content = String.format(Locale.getDefault(), "Time: %s\nIOB: %.1fU", timeStr, iob);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                getApplicationContext(), 
                CHANNEL_ID
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(String.format(Locale.getDefault(), 
                    "💉 Bolus: %.1f units\n⏰ Time: %s\n📊 IOB: %.1f units",
                    insulin, timeStr, iob)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(new long[]{0, 500, 200, 500})
            .setAutoCancel(true);
        
        NotificationManager notificationManager = (NotificationManager) 
            getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        
        notificationManager.notify(1, builder.build());
        
        Log.d(TAG, "Notification sent!");
    }
}
