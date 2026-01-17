package com.bolusmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "BolusMonitorPrefs";
    private static final String KEY_IS_RUNNING = "is_running";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Device boot completed");
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasRunning = prefs.getBoolean(KEY_IS_RUNNING, false);
            
            if (wasRunning) {
                Log.d(TAG, "Restarting bolus monitoring...");
                
                PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                        BolusCheckWorker.class,
                        5,
                        TimeUnit.MINUTES
                    )
                    .build();
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "bolus_monitor_work",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                );
                
                Log.d(TAG, "Bolus monitoring restarted!");
            }
        }
    }
}
