package com.acs.readertest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ตัวรับการกระจายเมื่อเครื่องเปิด เพื่อเริ่มบริการอ่านการ์ดโดยอัตโนมัติ
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "ได้รับการกระจาย ACTION_BOOT_COMPLETED");
            
            // เริ่ม CardReaderService
            Intent serviceIntent = new Intent(context, CardReaderService.class);
            
            // Android 8.0 ขึ้นไปต้องใช้ startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "เริ่มบริการด้วย startForegroundService");
            } else {
                context.startService(serviceIntent);
                Log.d(TAG, "เริ่มบริการด้วย startService");
            }
        }
    }
} 