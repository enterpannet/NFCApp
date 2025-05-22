package com.acs.readertest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

public class FloatingButtonService extends Service {
    private static final String TAG = "FloatingButtonService";
    private static final int NOTIFICATION_ID = 123;
    private static final String CHANNEL_ID = "FloatingButtonChannel";
    
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private float initialX, initialY;
    private int initialTouchX, initialTouchY;
    private boolean isMoving = false;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // สร้าง notification channel (จำเป็นสำหรับ Android 8.0 ขึ้นไป)
        createNotificationChannel();
        
        // สร้าง Intent สำหรับเปิดแอพเมื่อกดที่ notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);
        
        // สร้าง notification
        Notification.Builder notificationBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            notificationBuilder = new Notification.Builder(this);
        }
        
        Notification notification = notificationBuilder
                .setContentTitle("ปุ่มกลับแอพ")
                .setContentText("กำลังแสดงปุ่มลอยสำหรับกลับมาที่แอพ")
                .setSmallIcon(android.R.drawable.ic_menu_revert)
                .setContentIntent(pendingIntent)
                .build();
        
        // เริ่มการทำงานในโหมด Foreground
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatingView == null) {
            showFloatingButton();
        }
        
        // บอกว่าบริการนี้ควรเริ่มต้นใหม่หากถูกทำลาย
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "ปุ่มลอย";
            String description = "ช่องทางการแจ้งเตือนสำหรับปุ่มลอย";
            int importance = NotificationManager.IMPORTANCE_LOW;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showFloatingButton() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            
            // สร้าง floating view จาก layout
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            floatingView = inflater.inflate(R.layout.floating_button, null);
            
            // กำหนดค่า LayoutParams สำหรับ floating view
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);
            } else {
                params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);
            }
            
            // ตำแหน่งเริ่มต้นที่มุมขวาล่าง
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = 20;
            params.y = 100;
            // กำหนดลำดับชั้นให้แสดงบนสุด
            params.windowAnimations = android.R.style.Animation_Toast;
            
            // กำหนด event listener สำหรับการคลิกปุ่ม
            floatingView.findViewById(R.id.floating_button_icon).setOnClickListener(v -> {
                // เปิดแอพ
                Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                
                // แสดงข้อความ
                Toast.makeText(this, "กลับมาที่แอพแล้ว", Toast.LENGTH_SHORT).show();
            });
            
            // กำหนด touch listener เพื่อสามารถลากปุ่มไปยังตำแหน่งต่างๆ ได้
            floatingView.findViewById(R.id.root_container).setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = (int) event.getRawX();
                            initialTouchY = (int) event.getRawY();
                            isMoving = false;
                            return true;
                            
                        case MotionEvent.ACTION_MOVE:
                            // คำนวณระยะห่างที่เคลื่อนที่
                            int dx = (int) event.getRawX() - initialTouchX;
                            int dy = (int) event.getRawY() - initialTouchY;
                            
                            // ถ้าเคลื่อนที่มากพอ ให้ถือว่ากำลังเคลื่อนย้าย
                            if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                                isMoving = true;
                            }
                            
                            // อัปเดตตำแหน่ง
                            params.x = (int) (initialX - dx);
                            params.y = (int) (initialY - dy);
                            
                            // อัปเดต view
                            if (floatingView != null && floatingView.isAttachedToWindow()) {
                                windowManager.updateViewLayout(floatingView, params);
                            }
                            return true;
                            
                        case MotionEvent.ACTION_UP:
                            // ถ้าไม่ได้เคลื่อนย้าย ให้ถือเป็นการคลิก
                            if (!isMoving) {
                                v.performClick();
                            }
                            return true;
                    }
                    return false;
                }
            });
            
            // เพิ่ม view ลงใน window
            windowManager.addView(floatingView, params);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการแสดงปุ่มลอย: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && floatingView.isAttachedToWindow()) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
    }
} 