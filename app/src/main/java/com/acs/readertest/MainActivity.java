/*
 * Copyright (C) 2011-2024 Advanced Card Systems Ltd. All rights reserved.
 *
 * This software is the confidential and proprietary information of Advanced
 * Card Systems Ltd. ("Confidential Information").  You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with ACS.
 */

package com.acs.readertest;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * หน้าหลักสำหรับแสดงรูปภาพเต็มจอ
 */
public class MainActivity extends AppCompatActivity implements CardReaderService.CardReaderListener {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "ImagePrefs";
    private static final String KEY_SELECTED_IMAGE_PATH = "selected_image_path";
    private static final String SAVED_IMAGE_NAME = "selected_image.jpg";
    private static final int REQUEST_MEDIA_PERMISSION = 102;

    // UI elements
    private ImageView ivMainDisplay;
    private LinearLayout layoutNoImage;
    private Button btnSettings;
    private TextView tvImageInfo;

    // ActivityResultLauncher สำหรับ Settings
    private ActivityResultLauncher<Intent> settingsLauncher;
    
    // ActivityResultLauncher สำหรับเปิดไฟล์ PDF
    private ActivityResultLauncher<Intent> pdfLauncher;
    
    // Current selected image URI
    private Uri currentImageUri;
    
    // CardReaderService connection
    private CardReaderService mCardReaderService;
    private boolean mBound = false;
    
    // Card PDF Mapping
    private CardPdfMapping cardPdfMapping;
    private String lastCardId = null;
    private String lastOpenedPdfForCardId = null;
    
    // ServiceConnection สำหรับเชื่อมต่อกับ CardReaderService
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "ServiceConnection.onServiceConnected() called");
            CardReaderService.LocalBinder binder = (CardReaderService.LocalBinder) service;
            mCardReaderService = binder.getService();
            mBound = true;
            
            // ตั้งค่า listener เพื่อรับการแจ้งเตือนจาก service
            mCardReaderService.setListener(MainActivity.this);
            
            Log.d(TAG, "เชื่อมต่อกับบริการอ่านการ์ดสำเร็จ");
            
            // ตรวจสอบสถานะปัจจุบันของ service
            boolean isReaderConnected = mCardReaderService.isReaderConnected();
            String lastCardId = mCardReaderService.getLastCardId();
            
            Log.d(TAG, "สถานะปัจจุบัน - Reader connected: " + isReaderConnected + ", Last card: " + lastCardId);
            
            // แจ้งเตือนผู้ใช้ว่าพร้อมใช้งาน
            runOnUiThread(() -> {
                if (isReaderConnected) {
                    showMessage("เครื่องอ่านการ์ดพร้อมใช้งาน");
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "ServiceConnection.onServiceDisconnected() called");
            mBound = false;
            mCardReaderService = null;
            Log.d(TAG, "ยกเลิกการเชื่อมต่อกับบริการอ่านการ์ด");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ตั้งค่า UI elements
        initializeUIElements();
        
        // ลงทะเบียน ActivityResultLauncher
        registerSettingsLauncher();
        
        // ลงทะเบียน PDF Launcher
        registerPdfLauncher();
        
        // เริ่มต้น Card Mapping
        initializeCardMapping();
        
        // กำหนด Event Listeners
        setupEventListeners();
        
        // ขอสิทธิ์และโหลดรูปภาพ
        requestPermissionsAndLoadImage();
        
        // เริ่ม CardReaderService เป็น foreground service
        startCardReaderService();
        
        Log.d(TAG, "MainActivity เริ่มต้นเรียบร้อย - หน้าแสดงภาพเต็มจอ");
    }

    /**
     * ตั้งค่า UI elements
     */
    private void initializeUIElements() {
        ivMainDisplay = findViewById(R.id.iv_main_display);
        layoutNoImage = findViewById(R.id.layout_no_image);
        btnSettings = findViewById(R.id.btn_settings);
        tvImageInfo = findViewById(R.id.tv_image_info);
        
        // ตั้งค่าการแสดงผลเริ่มต้น
        showNoImageLayout();
    }

    /**
     * กำหนด Event Listeners
     */
    private void setupEventListeners() {
        // ปุ่ม Settings
        btnSettings.setOnClickListener(v -> {
            openSettingsActivity();
        });
    }
    
    /**
     * ลงทะเบียน ActivityResultLauncher สำหรับ Settings
     */
    private void registerSettingsLauncher() {
        settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    // รับข้อมูลกลับจาก SettingsActivity
                    Intent data = result.getData();
                    String imageUriString = data.getStringExtra("selected_image_uri");
                    String readerStatus = data.getStringExtra("reader_status");
                    String cardId = data.getStringExtra("card_id");
                    String pdfFile = data.getStringExtra("pdf_file");
                    
                    // โหลดรูปภาพใหม่ถ้ามี
                    if (imageUriString != null) {
                        if (imageUriString.isEmpty()) {
                            // ล้างรูปภาพ
                            clearImage();
                            Log.d(TAG, "ล้างรูปภาพตามคำสั่งจาก Settings");
                        } else {
                            // โหลดรูปภาพใหม่
                            Uri imageUri = Uri.parse(imageUriString);
                            loadImageFromUri(imageUri);
                            saveImagePath(imageUri.toString()); // บันทึก URI ของรูปภาพ
                            Log.d(TAG, "โหลดรูปภาพใหม่จาก Settings: " + imageUriString);
                        }
                    }
                    
                    // อัปเดตข้อมูลอื่น ๆ (หากต้องการ)
                    if (readerStatus != null) {
                        Log.d(TAG, "สถานะเครื่องอ่านจาก Settings: " + readerStatus);
                    }
                    if (cardId != null) {
                        Log.d(TAG, "การ์ด ID จาก Settings: " + cardId);
                    }
                    if (pdfFile != null) {
                        Log.d(TAG, "ไฟล์ PDF จาก Settings: " + pdfFile);
                    }
                }
                
                Log.d(TAG, "กลับมาจากหน้า Settings แล้ว");
            }
        );
    }

    /**
     * ลงทะเบียน ActivityResultLauncher สำหรับเปิดไฟล์ PDF
     */
    private void registerPdfLauncher() {
        pdfLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // กลับมาที่แอพหลังจากเปิดไฟล์ PDF
                Log.d(TAG, "กลับมาที่แอพหลังจากดูไฟล์ PDF แล้ว");
                showMessage("กลับมาจากการดู PDF แล้ว");
            }
        );
    }

    /**
     * เริ่มต้น Card Mapping
     */
    private void initializeCardMapping() {
        try {
            cardPdfMapping = new CardPdfMapping();
            boolean mappingLoaded = cardPdfMapping.loadMapping(this);
            if (mappingLoaded) {
                Log.d(TAG, "โหลด mapping สำเร็จ");
            } else {
                Log.w(TAG, "ไม่สามารถโหลด mapping ได้");
                cardPdfMapping = new CardPdfMapping(); // สร้าง mapping ว่างเปล่า
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด mapping: ", e);
            cardPdfMapping = new CardPdfMapping(); // สร้าง mapping ว่างเปล่า
        }
    }

    /**
     * ขอสิทธิ์และโหลดรูปภาพที่บันทึกไว้
     */
    private void requestPermissionsAndLoadImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                // ขอสิทธิ์ READ_MEDIA_IMAGES
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        REQUEST_MEDIA_PERMISSION);
            } else {
                loadSavedImage();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6-12
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                // ขอสิทธิ์ READ_EXTERNAL_STORAGE
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_MEDIA_PERMISSION);
            } else {
                loadSavedImage();
            }
        } else {
            // Android 5.1 หรือต่ำกว่า ได้รับสิทธิ์โดยอัตโนมัติ
            loadSavedImage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_MEDIA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "ได้รับสิทธิ์ในการเข้าถึงไฟล์สื่อแล้ว");
                loadSavedImage();
            } else {
                Log.w(TAG, "ไม่ได้รับสิทธิ์ในการเข้าถึงไฟล์สื่อ");
                showMessage("ต้องการสิทธิ์ในการเข้าถึงไฟล์สื่อเพื่อแสดงรูปภาพ");
            }
        }
    }

    /**
     * โหลดรูปภาพจาก URI และบันทึกเป็นไฟล์ local
     */
    private void loadImageFromUri(Uri imageUri) {
        // ตรวจสอบสิทธิ์ก่อนโหลด
        if (!hasMediaPermission()) {
            Log.w(TAG, "ไม่มีสิทธิ์ในการเข้าถึงไฟล์สื่อ");
            showMessage("ไม่มีสิทธิ์ในการเข้าถึงไฟล์สื่อ กรุณาอนุญาตสิทธิ์ในการตั้งค่า");
            return;
        }

        // คัดลอกและบันทึกรูปภาพเป็นไฟล์ local ใน background thread
        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                if (inputStream != null) {
                    // สร้าง path สำหรับบันทึกไฟล์
                    File internalDir = new File(getFilesDir(), "images");
                    if (!internalDir.exists()) {
                        internalDir.mkdirs();
                    }
                    
                    File savedImageFile = new File(internalDir, SAVED_IMAGE_NAME);
                    
                    // คัดลอกไฟล์
                    FileOutputStream outputStream = new FileOutputStream(savedImageFile);
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                    outputStream.close();
                    inputStream.close();
                    
                    Log.d(TAG, "บันทึกรูปภาพเป็นไฟล์ local สำเร็จ: " + savedImageFile.getPath());
                    
                    // โหลดและแสดงรูปภาพ
                    runOnUiThread(() -> {
                        loadImageFromFile(savedImageFile.getPath());
                        saveImagePath(savedImageFile.getPath());
                        showMessage("โหลดรูปภาพสำเร็จ");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการบันทึกรูปภาพ", e);
                runOnUiThread(() -> {
                    showMessage("เกิดข้อผิดพลาดในการโหลดรูปภาพ: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * โหลดรูปภาพจากไฟล์ local
     */
    private void loadImageFromFile(String filePath) {
        try {
            File imageFile = new File(filePath);
            if (!imageFile.exists()) {
                Log.e(TAG, "ไฟล์รูปภาพไม่มีอยู่: " + filePath);
                showMessage("ไฟล์รูปภาพไม่มีอยู่");
                return;
            }

            // โหลดรูปภาพแบบ resize เพื่อประหยัดหน่วยความจำ
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);

            // คำนวณ inSampleSize เพื่อ resize
            int reqWidth = ivMainDisplay.getWidth();
            int reqHeight = ivMainDisplay.getHeight();
            if (reqWidth == 0) reqWidth = 1080; // default width
            if (reqHeight == 0) reqHeight = 1920; // default height

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            // โหลดรูปภาพที่ resize แล้ว
            Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);

            if (bitmap != null) {
                // แสดงรูปภาพ
                ivMainDisplay.setImageBitmap(bitmap);
                showImageLayout();

                // แสดงข้อมูลรูปภาพ
                String info = String.format("ขนาด: %d x %d px", bitmap.getWidth(), bitmap.getHeight());
                tvImageInfo.setText(info);
                tvImageInfo.setVisibility(View.VISIBLE);

                Log.d(TAG, "โหลดรูปภาพจากไฟล์สำเร็จ: " + filePath);
            } else {
                Log.e(TAG, "ไม่สามารถโหลด Bitmap จากไฟล์: " + filePath);
                showMessage("ไม่สามารถโหลดรูปภาพได้");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลดรูปภาพจากไฟล์", e);
            showMessage("เกิดข้อผิดพลาดในการโหลดรูปภาพ: " + e.getMessage());
        }
    }

    /**
     * คำนวณ inSampleSize สำหรับ resize รูปภาพ
     */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * แสดง layout เมื่อมีรูปภาพ
     */
    private void showImageLayout() {
        ivMainDisplay.setVisibility(View.VISIBLE);
        layoutNoImage.setVisibility(View.GONE);
    }

    /**
     * แสดง layout เมื่อไม่มีรูปภาพ
     */
    private void showNoImageLayout() {
        ivMainDisplay.setVisibility(View.GONE);
        layoutNoImage.setVisibility(View.VISIBLE);
        tvImageInfo.setVisibility(View.GONE);
    }

    /**
     * เปิดหน้า Settings
     */
    private void openSettingsActivity() {
        try {
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            Log.d(TAG, "เปิดหน้า Settings");
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดหน้า Settings", e);
            showMessage("เกิดข้อผิดพลาดในการเปิดหน้า Settings");
        }
    }

    /**
     * แสดงข้อความ toast
     */
    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * ตรวจสอบว่ามีสิทธิ์ในการเข้าถึงไฟล์สื่อหรือไม่
     */
    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6-12
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            // Android 5.1 หรือต่ำกว่า ได้รับสิทธิ์โดยอัตโนมัติ
            return true;
        }
    }

    /**
     * โหลดรูปภาพที่บันทึกไว้ (ถ้ามี)
     */
    private void loadSavedImage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedImagePath = prefs.getString(KEY_SELECTED_IMAGE_PATH, null);
        
        if (savedImagePath != null) {
            File imageFile = new File(savedImagePath);
            if (imageFile.exists()) {
                loadImageFromFile(savedImagePath);
                Log.d(TAG, "โหลดรูปภาพที่บันทึกไว้สำเร็จ: " + savedImagePath);
            } else {
                // ไฟล์ไม่มีอยู่ ลบการบันทึก
                prefs.edit().remove(KEY_SELECTED_IMAGE_PATH).apply();
                Log.w(TAG, "ไฟล์รูปภาพที่บันทึกไว้ไม่มีอยู่: " + savedImagePath);
                showMessage("ไฟล์รูปภาพที่บันทึกไว้ไม่มีอยู่ กรุณาเลือกรูปภาพใหม่");
            }
        }
    }
    
    /**
     * บันทึก path ของรูปภาพที่เลือก
     */
    private void saveImagePath(String imagePath) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (imagePath != null) {
            prefs.edit().putString(KEY_SELECTED_IMAGE_PATH, imagePath).apply();
            Log.d(TAG, "บันทึก path รูปภาพ: " + imagePath);
        } else {
            prefs.edit().remove(KEY_SELECTED_IMAGE_PATH).apply();
            Log.d(TAG, "ลบ path รูปภาพที่บันทึกไว้");
        }
    }

    /**
     * ล้างรูปภาพ
     */
    private void clearImage() {
        ivMainDisplay.setImageBitmap(null);
        showNoImageLayout();
        tvImageInfo.setText("");
        tvImageInfo.setVisibility(View.GONE);
        
        // ลบไฟล์รูปภาพ
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedImagePath = prefs.getString(KEY_SELECTED_IMAGE_PATH, null);
        if (savedImagePath != null) {
            File imageFile = new File(savedImagePath);
            if (imageFile.exists()) {
                imageFile.delete();
                Log.d(TAG, "ลบไฟล์รูปภาพ: " + savedImagePath);
            }
        }
        
        saveImagePath(null); // ลบการบันทึก path
        showMessage("ล้างรูปภาพแล้ว");
        Log.d(TAG, "ล้างรูปภาพและข้อมูลที่บันทึกไว้แล้ว");
    }

    /**
     * เริ่มการทำงานของ CardReaderService
     */
    private void startCardReaderService() {
        Intent serviceIntent = new Intent(this, CardReaderService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
            Log.d(TAG, "เริ่ม CardReaderService เป็น foreground service");
        } else {
            startService(serviceIntent);
            Log.d(TAG, "เริ่ม CardReaderService เป็น background service");
        }
        
        // เชื่อมต่อกับ service
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart() called - mBound=" + mBound);
        
        // ถ้ายังไม่ได้เชื่อมต่อ ให้เชื่อมต่อใหม่
        if (!mBound) {
            Intent intent = new Intent(this, CardReaderService.class);
            boolean bindResult = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "พยายามเชื่อมต่อกับ CardReaderService: " + bindResult);
        } else {
            Log.d(TAG, "Service ยังเชื่อมต่ออยู่แล้ว");
            // ตั้งค่า listener ใหม่เผื่อหลุด
            if (mCardReaderService != null) {
                mCardReaderService.setListener(MainActivity.this);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop() called - mBound=" + mBound);
        
        // ไม่ unbind service ใน onStop เพราะเราต้องการให้ทำงานต่อไป
        // จะ unbind ใน onDestroy เท่านั้น
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume() called - mBound=" + mBound);
        
        // ตรวจสอบและตั้งค่า listener ใหม่
        if (mBound && mCardReaderService != null) {
            mCardReaderService.setListener(MainActivity.this);
            Log.d(TAG, "ตั้งค่า listener สำหรับ CardReaderService ใหม่");
            
            // แสดงสถานะปัจจุบัน
            boolean isReaderConnected = mCardReaderService.isReaderConnected();
            String lastCardId = mCardReaderService.getLastCardId();
            Log.d(TAG, "สถานะปัจจุบัน - Reader: " + isReaderConnected + ", LastCard: " + lastCardId);
            
            // แจ้งผู้ใช้ว่าพร้อมใช้งาน
            if (isReaderConnected) {
                showMessage("เครื่องอ่านการ์ดพร้อมใช้งาน");
            }
        } else {
            Log.w(TAG, "Service ไม่ได้เชื่อมต่อใน onResume - พยายามเชื่อมต่อใหม่");
            startCardReaderService();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause() called");
        
        // ไม่ต้อง unset listener ใน onPause เพราะเราต้องการให้ทำงานต่อไป
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy() called");
        
        // ยกเลิกการเชื่อมต่อกับ CardReaderService
        if (mBound) {
            try {
                if (mCardReaderService != null) {
                    mCardReaderService.setListener(null);
                }
                unbindService(mConnection);
                Log.d(TAG, "ยกเลิกการเชื่อมต่อกับ CardReaderService สำเร็จ");
            } catch (Exception e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการยกเลิกการเชื่อมต่อ: " + e.getMessage());
            }
            mBound = false;
        }
    }

    // Implement CardReaderListener methods
    @Override
    public void onReaderStatusChanged(boolean connected, String readerName) {
        Log.d(TAG, "onReaderStatusChanged: connected=" + connected + ", readerName=" + readerName);
        runOnUiThread(() -> {
            // อัปเดต UI ถ้าต้องการ (ไม่มี UI element สำหรับแสดงสถานะใน MainActivity)
            Log.d(TAG, "สถานะเครื่องอ่านเปลี่ยนเป็น: " + (connected ? "เชื่อมต่อแล้ว" : "ไม่ได้เชื่อมต่อ"));
        });
    }

    @Override
    public void onCardDetected(String cardId) {
        Log.d(TAG, "onCardDetected: cardId=" + cardId);
        runOnUiThread(() -> {
            Log.d(TAG, "ตรวจพบการ์ด: " + cardId);
            
            // ประมวลผลข้อมูลการ์ดและเปิด PDF
            processCardInfo(cardId);
        });
    }

    @Override
    public void onPdfOpened(String cardId, String pdfPath) {
        Log.d(TAG, "onPdfOpened: cardId=" + cardId + ", pdfPath=" + pdfPath);
        runOnUiThread(() -> {
            Log.d(TAG, "เปิดไฟล์ PDF สำเร็จ: " + pdfPath);
            showMessage("เปิดไฟล์ PDF สำเร็จสำหรับการ์ด: " + cardId);
        });
    }

    /**
     * ประมวลผลข้อมูลการ์ดที่ตรวจพบและเปิดไฟล์ PDF ที่เชื่อมโยง
     */
    private void processCardInfo(String cardId) {
        try {
            Log.d(TAG, "=== processCardInfo START ===");
            Log.d(TAG, "Input cardId: " + cardId);
            
            if (cardId == null || cardId.isEmpty()) {
                Log.e(TAG, "cardId เป็น null หรือว่างเปล่า");
                return;
            }
            
            Log.d(TAG, "ประมวลผลข้อมูลการ์ด: " + cardId);
            
            // ตรวจสอบ CardPdfMapping
            if (cardPdfMapping == null) {
                Log.e(TAG, "cardPdfMapping เป็น null");
                showMessage("ไม่สามารถเชื่อมโยงการ์ดกับไฟล์ PDF ได้");
                return;
            }
            
            Log.d(TAG, "cardPdfMapping พร้อมใช้งาน");
            
            // ค้นหา PDF ที่เชื่อมโยงกับการ์ด
            String pdfPath = cardPdfMapping.findPdfForCard(cardId);
            Log.d(TAG, "ผลการค้นหา PDF: " + pdfPath);
            
            if (pdfPath != null) {
                // พบ PDF ที่เชื่อมโยงกับการ์ด
                String fileName = new File(pdfPath).getName();
                Log.d(TAG, "พบไฟล์ PDF: " + fileName);
                
                // ตรวจสอบว่าเป็นการ์ดเดิมและเปิดไฟล์ PDF ไปแล้วหรือไม่
                String currentCardPdfKey = cardId + ":" + pdfPath;
                Log.d(TAG, "lastCardId: " + lastCardId);
                Log.d(TAG, "lastOpenedPdfForCardId: " + lastOpenedPdfForCardId);
                Log.d(TAG, "currentCardPdfKey: " + currentCardPdfKey);
                
                if (cardId.equals(lastCardId) && currentCardPdfKey.equals(lastOpenedPdfForCardId)) {
                    Log.d(TAG, "ข้าม: ไฟล์ PDF นี้ถูกเปิดไปแล้วสำหรับการ์ดนี้");
                    return;
                }
                
                Log.d(TAG, "พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + fileName);
                showMessage("กำลังเปิด: " + fileName);
                
                // ตรวจสอบว่าไฟล์มีอยู่จริงหรือไม่
                File pdfFile = new File(pdfPath);
                if (!pdfFile.exists()) {
                    Log.e(TAG, "ไฟล์ PDF ไม่มีอยู่: " + pdfPath);
                    showMessage("ไฟล์ PDF ไม่มีอยู่: " + fileName);
                    return;
                }
                
                Log.d(TAG, "ไฟล์ PDF มีอยู่, กำลังเปิด...");
                
                // เปิดไฟล์ PDF โดยใช้ ActivityResultLauncher พร้อมปุ่มกลับและตัวจับเวลา
                boolean openResult = PdfHelper.openPdf(this, pdfPath, pdfLauncher, true, 120);
                Log.d(TAG, "ผลการเปิด PDF: " + openResult);
                
                if (openResult) {
                    Log.d(TAG, "เปิดไฟล์ PDF สำเร็จ");
                    showMessage("เปิดไฟล์ PDF สำเร็จ");
                    
                    // บันทึกว่าได้เปิดไฟล์นี้ไปแล้วสำหรับการ์ดนี้
                    lastCardId = cardId;
                    lastOpenedPdfForCardId = currentCardPdfKey;
                    Log.d(TAG, "บันทึกสถานะการเปิด PDF แล้ว");
                } else {
                    Log.e(TAG, "ไม่สามารถเปิดไฟล์ PDF ได้");
                    showMessage("ไม่สามารถเปิดไฟล์ PDF ได้");
                }
                
            } else {
                // ไม่พบ PDF ที่เชื่อมโยงกับการ์ด
                Log.d(TAG, "ไม่พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + cardId);
                showMessage("ไม่พบไฟล์ PDF สำหรับการ์ดนี้");
            }
            
            Log.d(TAG, "=== processCardInfo END ===");
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด", e);
            showMessage("เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด: " + e.getMessage());
        }
    }
} 