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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.acs.smartcard.Reader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import android.content.SharedPreferences;

/**
 * หน้าการตั้งค่าสำหรับจัดการเครื่องอ่านการ์ด NFC และระบบต่างๆ
 */
public class SettingsActivity extends AppCompatActivity implements CardReaderService.CardReaderListener {

    private static final String TAG = "SettingsActivity";
    private static final String ACTION_USB_PERMISSION = "com.acs.readertest.USB_PERMISSION";
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int REQUEST_MEDIA_PERMISSION = 102;
    private static final int CARD_POLLING_INTERVAL = 1000; // 1 วินาที
    private static final String PREFS_NAME = "ImagePrefs";
    private static final String KEY_SELECTED_IMAGE_URI = "selected_image_uri";

    // UI elements
    private TextView tvReaderName;
    private TextView tvReaderStatus;
    private Button btnConnectReader;
    private TextView tvCardId;
    private TextView tvPdfFile;
    private TextView tvStatusMessage;
    private Button btnReadCard;
    private TextView tvLogContent;
    private Button btnBack;
    
    // Image selection UI elements
    private Button btnSelectImage;
    private Button btnClearImage;
    private TextView tvSelectedImageInfo;
    private ImageView ivImagePreview;
    private Button btnApplyImage;

    // USB และ Reader
    private UsbManager mManager;
    private Reader mReader;
    private PendingIntent mPermissionIntent;
    private int mSlotNum = 0;
    private boolean mReaderOpened = false;
    private NfcCardReader nfcCardReader;
    private CardMediaMapping cardMediaMapping;
    private Timer cardPollingTimer;
    private String lastCardId = null;
    private String lastOpenedPdfForCardId = null;
    private String lastOpenedMediaForCardId = null;

    // ActivityResultLauncher สำหรับเปิดไฟล์ PDF
    private ActivityResultLauncher<Intent> pdfLauncher;
    
    // ActivityResultLauncher สำหรับเลือกรูปภาพ
    private ActivityResultLauncher<Intent> imageLauncher;
    
    // Selected image URI
    private Uri selectedImageUri;

    // BroadcastReceiver สำหรับจัดการ USB events
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            // เปิดการเชื่อมต่อกับ reader
                            logMessage("กำลังเชื่อมต่อกับเครื่องอ่าน: " + device.getDeviceName());
                            new Thread(() -> openReader(device)).start();
                        }
                    } else {
                        logMessage("การขอสิทธิ์ USB ถูกปฏิเสธสำหรับอุปกรณ์: " + device.getDeviceName());
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isAcsDevice(device)) {
                    logMessage("ตรวจพบเครื่องอ่าน: " + device.getDeviceName());
                    requestUsbPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && mReader != null && mReader.isSupported(device)) {
                    if (mReaderOpened) {
                        new Thread(() -> closeReader()).start();
                    }
                    updateReaderStatus("ไม่ได้เชื่อมต่อ");
                    logMessage("เครื่องอ่านถูกถอด: " + device.getDeviceName());
                }
            }
        }
    };

    // Service connection
    private CardReaderService mCardReaderService;
    private boolean mBound = false;
    private androidx.appcompat.widget.SwitchCompat switchBackgroundMode;

    // ServiceConnection สำหรับเชื่อมต่อกับ CardReaderService
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CardReaderService.LocalBinder binder = (CardReaderService.LocalBinder) service;
            mCardReaderService = binder.getService();
            mBound = true;
            
            // ตั้งค่า listener เพื่อรับการแจ้งเตือนจาก service
            mCardReaderService.setListener(SettingsActivity.this);
            
            // อัปเดต UI ตามสถานะปัจจุบันของ service
            updateUIFromService();
            
            logMessage("เชื่อมต่อกับบริการอ่านการ์ดสำเร็จ");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            logMessage("ยกเลิกการเชื่อมต่อกับบริการอ่านการ์ด");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_settings);

            // ลงทะเบียน ActivityResultLauncher สำหรับเปิดไฟล์ PDF
            registerPdfLauncher();
            
            // ลงทะเบียน ActivityResultLauncher สำหรับเลือกรูปภาพ
            registerImageLauncher();
            
            // ตั้งค่า UI elements
            initializeUIElements();
            
            // โหลดรูปภาพที่บันทึกไว้ (ถ้ามี)
            loadSavedImage();
            
            // แสดงข้อความเริ่มต้น
            logMessage("กำลังเริ่มต้นหน้า Settings...");
            
            // เริ่มต้นขอสิทธิ์การเข้าถึง Storage
            requestStoragePermission();
            
            // ขอสิทธิ์การแสดงหน้าต่างลอย
            checkOverlayPermission();
            
            // ตรวจสอบและสร้างโฟลเดอร์สำหรับเก็บไฟล์ PDF ถ้ายังไม่มี
            createTestMediaFiles();

            // ตั้งค่า USB Manager และ Reader
            initializeReaderComponents();

            // โหลด CardMediaMapping
            initializeCardMapping();

            // กำหนด Event Listeners
            setupEventListeners();

            // ลงทะเบียน BroadcastReceiver สำหรับ USB events
            registerUsbReceiver();

            // ตรวจสอบ Intent จาก USB device ที่เสียบอยู่แล้ว
            checkAttachedDevices(getIntent());
            
        } catch (Exception e) {
            // จัดการกับข้อผิดพลาดทุกประเภทที่อาจเกิดขึ้นใน onCreate
            Log.e(TAG, "เกิดข้อผิดพลาดใน onCreate: ", e);
            showErrorDialog("เกิดข้อผิดพลาดในการเริ่มต้นหน้า Settings", e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAttachedDevices(intent);
    }

    @Override
    protected void onDestroy() {
        try {
            // หยุดการทำงานของ timer
            stopCardPolling();
            
            // ปิดการเชื่อมต่อกับ reader
            if (mReaderOpened) {
                try {
                    new Thread(() -> closeReader()).start();
                } catch (Exception e) {
                    Log.e(TAG, "เกิดข้อผิดพลาดในการปิด reader: ", e);
                }
            }
            
            // ยกเลิกการลงทะเบียน BroadcastReceiver
            try {
                unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException e) {
                // เกิดขึ้นเมื่อ receiver ไม่ได้ลงทะเบียน
                Log.e(TAG, "เกิดข้อผิดพลาดในการยกเลิกการลงทะเบียน receiver: ", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดใน onDestroy: ", e);
        } finally {
            super.onDestroy();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logMessage("ได้รับสิทธิ์เข้าถึงพื้นที่จัดเก็บข้อมูลแล้ว");
                createTestMediaFiles(); // สร้างไฟล์สื่อตัวอย่างหลังจากได้รับสิทธิ์
            } else {
                logMessage("ไม่ได้รับสิทธิ์เข้าถึงพื้นที่จัดเก็บข้อมูล");
                showErrorDialog("ต้องการสิทธิ์", 
                    "แอปต้องการสิทธิ์ในการเข้าถึงพื้นที่จัดเก็บข้อมูลเพื่อสร้างและอ่านไฟล์สื่อ");
            }
        } else if (requestCode == REQUEST_MEDIA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logMessage("ได้รับสิทธิ์ในการเข้าถึงไฟล์สื่อแล้ว");
                // เปิด image picker ทันที
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                imageLauncher.launch(intent);
            } else {
                logMessage("ไม่ได้รับสิทธิ์ในการเข้าถึงไฟล์สื่อ");
                showMessage("ต้องการสิทธิ์ในการเข้าถึงไฟล์สื่อเพื่อเลือกรูปภาพ");
            }
        }
    }

    /**
     * ตั้งค่า UI elements
     */
    private void initializeUIElements() {
        try {
            tvReaderName = findViewById(R.id.tv_reader_name);
            tvReaderStatus = findViewById(R.id.tv_reader_status);
            btnConnectReader = findViewById(R.id.btn_connect_reader);
            tvCardId = findViewById(R.id.tv_card_id);
            tvPdfFile = findViewById(R.id.tv_pdf_file);
            tvStatusMessage = findViewById(R.id.tv_status_message);
            btnReadCard = findViewById(R.id.btn_read_card);
            tvLogContent = findViewById(R.id.tv_log_content);
            btnBack = findViewById(R.id.btn_back);
            
            // Image selection UI elements
            btnSelectImage = findViewById(R.id.btn_select_image);
            btnClearImage = findViewById(R.id.btn_clear_image);
            tvSelectedImageInfo = findViewById(R.id.tv_selected_image_info);
            ivImagePreview = findViewById(R.id.iv_image_preview);
            btnApplyImage = findViewById(R.id.btn_apply_image);
            
            // ตั้งค่าสวิตช์ควบคุมปุ่มลอย
            androidx.appcompat.widget.SwitchCompat switchFloatingButton = findViewById(R.id.switch_floating_button);
            switchFloatingButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    startFloatingButtonService();
                } else {
                    stopFloatingButtonService();
                }
            });
            // ซ่อนสวิตช์ควบคุมปุ่มลอย
            switchFloatingButton.setVisibility(View.GONE);
            
            // ตั้งค่าสวิตช์โหมดพื้นหลัง
            switchBackgroundMode = findViewById(R.id.switch_background_mode);
            switchBackgroundMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    startBackgroundService();
                } else {
                    stopBackgroundService();
                }
            });
            // ซ่อนสวิตช์โหมดพื้นหลัง
            switchBackgroundMode.setVisibility(View.GONE);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการตั้งค่า UI: ", e);
            throw e; // โยนข้อผิดพลาดให้จัดการที่ onCreate
        }
    }
    
    /**
     * ตั้งค่า Card Mapping
     */
    private void initializeCardMapping() {
        try {
            cardMediaMapping = new CardMediaMapping();
            boolean mappingLoaded = cardMediaMapping.loadMapping(this);
            if (mappingLoaded) {
                Log.d(TAG, "โหลด mapping สำเร็จ");
                logMessage("โหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์สื่อสำเร็จ");
            } else {
                Log.w(TAG, "ไม่สามารถโหลด mapping ได้");
                logMessage("ไม่สามารถโหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์สื่อได้ ใช้ mapping ว่างเปล่าแทน");
                showMessage("ไม่สามารถโหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์สื่อได้");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด mapping: ", e);
            logMessage("เกิดข้อผิดพลาดในการโหลด mapping: " + e.getMessage());
            cardMediaMapping = new CardMediaMapping(); // สร้าง mapping ว่างเปล่า
        }
    }

    /**
     * ตั้งค่า components สำหรับ Reader
     */
    private void initializeReaderComponents() {
        // ตั้งค่า USB Manager
        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        // ตั้งค่า Reader
        UsbReader usbReader = UsbReader.getInstance(this);
        mReader = usbReader.getReader();
        nfcCardReader = new NfcCardReader(mReader);
        
        // สร้าง PendingIntent สำหรับขอสิทธิ์ USB
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
    }

    /**
     * กำหนด Event Listeners
     */
    private void setupEventListeners() {
        // ปุ่มกลับ
        btnBack.setOnClickListener(v -> {
            // ส่งข้อมูลกลับไป MainActivity
            Intent resultIntent = new Intent();
            resultIntent.putExtra("reader_status", tvReaderStatus.getText().toString());
            resultIntent.putExtra("card_id", tvCardId.getText().toString());
            resultIntent.putExtra("pdf_file", tvPdfFile.getText().toString());
            
            // ส่งข้อมูลรูปภาพที่เลือกกลับไป MainActivity (รวมทั้งกรณีที่ null/ล้างแล้ว)
            if (selectedImageUri != null) {
                resultIntent.putExtra("selected_image_uri", selectedImageUri.toString());
            } else {
                resultIntent.putExtra("selected_image_uri", ""); // ส่งค่าว่างเมื่อล้างรูป
            }
            
            setResult(RESULT_OK, resultIntent);
            finish();
        });
        
        // ปุ่มเชื่อมต่อเครื่องอ่าน
        btnConnectReader.setOnClickListener(v -> {
            if (!mReaderOpened) {
                connectToReader();
            } else {
                new Thread(() -> closeReader()).start();
            }
        });

        // ปุ่มอ่านการ์ด
        btnReadCard.setOnClickListener(v -> {
            if (mReaderOpened) {
                new Thread(() -> readCard()).start();
            } else {
                showMessage("กรุณาเชื่อมต่อเครื่องอ่านก่อน");
            }
        });

        // ปุ่มเลือกรูปภาพ
        btnSelectImage.setOnClickListener(v -> {
            // ตรวจสอบสิทธิ์ก่อนเลือกรูปภาพ
            if (hasMediaPermission()) {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                imageLauncher.launch(intent);
            } else {
                requestMediaPermission();
            }
        });
        
        // ปุ่มล้างรูปภาพ
        btnClearImage.setOnClickListener(v -> {
            clearSelectedImage();
        });
        
        // ปุ่มใช้รูปภาพและกลับ
        btnApplyImage.setOnClickListener(v -> {
            if (selectedImageUri != null) {
                // ส่งข้อมูลกลับไป MainActivity ทันที
                Intent resultIntent = new Intent();
                resultIntent.putExtra("reader_status", tvReaderStatus.getText().toString());
                resultIntent.putExtra("card_id", tvCardId.getText().toString());
                resultIntent.putExtra("pdf_file", tvPdfFile.getText().toString());
                resultIntent.putExtra("selected_image_uri", selectedImageUri.toString());
                
                setResult(RESULT_OK, resultIntent);
                finish();
            } else {
                showMessage("กรุณาเลือกรูปภาพก่อน");
            }
        });
        
        // ปุ่มจัดการการ์ด
        Button btnManageCards = findViewById(R.id.btn_manage_cards);
        btnManageCards.setOnClickListener(v -> {
            Intent intent = new Intent(this, CardManagementActivity.class);
            startActivity(intent);
            logMessage("เปิดหน้าจัดการการ์ด");
        });
    }

    /**
     * ลงทะเบียน BroadcastReceiver สำหรับ USB events
     */
    private void registerUsbReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            
            // ต้องระบุ RECEIVER_EXPORTED หรือ RECEIVER_NOT_EXPORTED สำหรับ Android 12 (API 31) ขึ้นไป
            if (Build.VERSION.SDK_INT >= 31) { // Android 12 (API 31) หรือสูงกว่า
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                Log.d(TAG, "ลงทะเบียน BroadcastReceiver ด้วย RECEIVER_NOT_EXPORTED สำเร็จ");
            } else {
                registerReceiver(mReceiver, filter);
                Log.d(TAG, "ลงทะเบียน BroadcastReceiver สำเร็จ");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการลงทะเบียน BroadcastReceiver", e);
        }
    }

    /**
     * ตรวจสอบ USB devices ที่เสียบอยู่แล้ว
     */
    private void checkAttachedDevices(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && isAcsDevice(device)) {
            requestUsbPermission(device);
        } else {
            // ตรวจสอบอุปกรณ์ที่เชื่อมต่ออยู่แล้ว
            for (UsbDevice d : mManager.getDeviceList().values()) {
                if (isAcsDevice(d)) {
                    requestUsbPermission(d);
                    break;
                }
            }
        }
    }

    /**
     * ตรวจสอบว่าเป็นอุปกรณ์ของ ACS หรือไม่
     */
    private boolean isAcsDevice(UsbDevice device) {
        return mReader != null && mReader.isSupported(device);
    }

    /**
     * ขอสิทธิ์การเข้าถึง USB device
     */
    private void requestUsbPermission(UsbDevice device) {
        if (!mManager.hasPermission(device)) {
            mManager.requestPermission(device, mPermissionIntent);
        } else {
            // มีสิทธิ์อยู่แล้ว เปิดการเชื่อมต่อได้เลย
            logMessage("มีสิทธิ์เข้าถึง USB แล้ว กำลังเชื่อมต่อ...");
            new Thread(() -> openReader(device)).start();
        }
    }

    /**
     * เชื่อมต่อกับเครื่องอ่าน
     */
    private void connectToReader() {
        // ตรวจสอบว่ามีเครื่องอ่านที่รองรับเชื่อมต่ออยู่หรือไม่
        HashMap<String, UsbDevice> deviceList = mManager.getDeviceList();
        if (deviceList.isEmpty()) {
            showMessage("ไม่พบเครื่องอ่านการ์ด");
            return;
        }

        // หาเครื่องอ่านที่รองรับ
        for (UsbDevice device : deviceList.values()) {
            if (isAcsDevice(device)) {
                requestUsbPermission(device);
                return;
            }
        }

        showMessage("ไม่พบเครื่องอ่าน ACR122U ที่รองรับ");
    }

    /**
     * เปิดการเชื่อมต่อกับเครื่องอ่าน
     */
    private void openReader(UsbDevice device) {
        try {
            // เปิดการเชื่อมต่อกับ reader
            mReader.open(device);

            // อัปเดตสถานะ UI
            runOnUiThread(() -> {
                mReaderOpened = true;
                String deviceName = device.getProductName() != null ? device.getProductName() : device.getDeviceName();
                tvReaderName.setText(deviceName);
                updateReaderStatus("เชื่อมต่อแล้ว");
                btnConnectReader.setText("ยกเลิกการเชื่อมต่อ");
                logMessage("เชื่อมต่อกับเครื่องอ่านสำเร็จ: " + deviceName);
                
                // เริ่ม polling เพื่อรอการ์ด
                startCardPolling();
            });
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดเครื่องอ่าน", e);
            runOnUiThread(() -> {
                mReaderOpened = false;
                updateReaderStatus("เกิดข้อผิดพลาด");
                logMessage("เกิดข้อผิดพลาดในการเปิดเครื่องอ่าน: " + e.getMessage());
            });
        }
    }

    /**
     * ปิดการเชื่อมต่อกับเครื่องอ่าน
     */
    private void closeReader() {
        try {
            // หยุด polling
            stopCardPolling();
            
            // ปิดการเชื่อมต่อกับ reader
            mReader.close();

            // อัปเดตสถานะ UI
            runOnUiThread(() -> {
                mReaderOpened = false;
                tvReaderName.setText("ไม่ได้เชื่อมต่อ");
                updateReaderStatus("ไม่ได้เชื่อมต่อ");
                btnConnectReader.setText("เชื่อมต่อเครื่องอ่านการ์ด");
                logMessage("ปิดการเชื่อมต่อกับเครื่องอ่านแล้ว");
            });
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการปิดเครื่องอ่าน", e);
            runOnUiThread(() -> logMessage("เกิดข้อผิดพลาดในการปิดเครื่องอ่าน: " + e.getMessage()));
        }
    }

    /**
     * เริ่ม polling เพื่อตรวจสอบการ์ด
     */
    private void startCardPolling() {
        if (cardPollingTimer != null) {
            cardPollingTimer.cancel();
        }
        
        cardPollingTimer = new Timer();
        cardPollingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (mReaderOpened) {
                    readCard();
                }
            }
        }, 500, CARD_POLLING_INTERVAL);
        
        logMessage("เริ่มการตรวจสอบการ์ดอัตโนมัติ");
    }

    /**
     * หยุด polling
     */
    private void stopCardPolling() {
        if (cardPollingTimer != null) {
            cardPollingTimer.cancel();
            cardPollingTimer = null;
        }
    }

    /**
     * อ่านข้อมูลจากการ์ด
     */
    private void readCard() {
        try {
            if (!mReaderOpened) {
                showMessage("กรุณาเชื่อมต่อเครื่องอ่านก่อน");
                return;
            }
            
            logMessage("กำลังอ่านการ์ด...");
            tvStatusMessage.post(() -> tvStatusMessage.setText("กำลังอ่านการ์ด..."));
            
            // ตรวจสอบว่ามีการ์ดบนเครื่องอ่านหรือไม่
            if (nfcCardReader == null) {
                logMessage("ไม่สามารถอ่านการ์ดได้: ไม่มี NfcCardReader");
                tvStatusMessage.post(() -> tvStatusMessage.setText("ไม่สามารถอ่านการ์ดได้"));
                return;
            }
            
            // อ่าน UID ก่อน
            String uid = nfcCardReader.readCardUid(mSlotNum);
            if (uid != null) {
                logMessage("อ่าน UID สำเร็จ: " + uid);
                processCardInfo(uid);
                return;
            }
            
            // ถ้าอ่าน UID ไม่สำเร็จ ให้ลองอ่าน NDEF
            String ndefText = nfcCardReader.readNdefText(mSlotNum);
            if (ndefText != null) {
                logMessage("อ่าน NDEF Text สำเร็จ: " + ndefText);
                processCardInfo(ndefText);
                return;
            }
            
            // ถ้าทั้ง UID และ NDEF ไม่สำเร็จ
            logMessage("ไม่พบการ์ดบนเครื่องอ่าน หรือรูปแบบการ์ดไม่รองรับ");
            runOnUiThread(() -> {
                tvCardId.setText("ไม่พบการ์ด");
                tvPdfFile.setText("-");
                tvStatusMessage.setText("ไม่พบการ์ดบนเครื่องอ่าน");
                
                // รีเซ็ตสถานะเมื่อไม่พบการ์ด
                lastCardId = null;
                lastOpenedPdfForCardId = null;
            });
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่านการ์ด", e);
            logMessage("เกิดข้อผิดพลาดในการอ่านการ์ด: " + e.getMessage());
            runOnUiThread(() -> tvStatusMessage.setText("เกิดข้อผิดพลาดในการอ่านการ์ด"));
        }
    }

    /**
     * ลงทะเบียน ActivityResultLauncher สำหรับเปิดไฟล์ PDF
     */
    private void registerPdfLauncher() {
        pdfLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // กลับมาที่แอพหลังจากเปิดไฟล์ PDF
                logMessage("กลับมาที่แอพหลังจากดูไฟล์ PDF แล้ว");
                // ทำการรีเฟรชหน้าจอหรือแสดงข้อความให้กับผู้ใช้
                runOnUiThread(() -> {
                    tvStatusMessage.setText("กลับมาจากการดู PDF แล้ว");
                });
            }
        );
    }

    /**
     * ลงทะเบียน ActivityResultLauncher สำหรับเลือกรูปภาพ
     */
    private void registerImageLauncher() {
        imageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        // ขอสิทธิ์ถาวรในการเข้าถึง URI
                        try {
                            getContentResolver().takePersistableUriPermission(imageUri, 
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            Log.d(TAG, "ขอสิทธิ์ถาวรสำหรับ URI สำเร็จ: " + imageUri);
                        } catch (Exception e) {
                            Log.w(TAG, "ไม่สามารถขอสิทธิ์ถาวรสำหรับ URI ได้: " + e.getMessage());
                        }
                        
                        selectedImageUri = imageUri;
                        updateImageInfo(imageUri);
                        logMessage("เลือกรูปภาพสำเร็จ: " + imageUri.toString());
                    }
                } else {
                    logMessage("ยกเลิกการเลือกรูปภาพ");
                }
            }
        );
    }

    /**
     * โหลดรูปภาพที่บันทึกไว้ (ถ้ามี)
     */
    private void loadSavedImage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedImageUri = prefs.getString(KEY_SELECTED_IMAGE_URI, null);
        
        if (savedImageUri != null) {
            try {
                selectedImageUri = Uri.parse(savedImageUri);
                updateImageInfo(selectedImageUri);
                logMessage("โหลดรูปภาพที่บันทึกไว้: " + getFileNameFromUri(selectedImageUri));
            } catch (Exception e) {
                Log.e(TAG, "ไม่สามารถโหลดรูปภาพที่บันทึกไว้ได้", e);
                // ลบข้อมูลรูปที่เสียหาย
                prefs.edit().remove(KEY_SELECTED_IMAGE_URI).apply();
                logMessage("ไม่สามารถโหลดรูปภาพที่บันทึกไว้ได้ กรุณาเลือกรูปภาพใหม่");
            }
        }
    }

    /**
     * บันทึกรูปภาพที่เลือก
     */
    private void saveSelectedImage(Uri imageUri) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (imageUri != null) {
            prefs.edit().putString(KEY_SELECTED_IMAGE_URI, imageUri.toString()).apply();
            Log.d(TAG, "บันทึกรูปภาพที่เลือก: " + imageUri.toString());
        } else {
            prefs.edit().remove(KEY_SELECTED_IMAGE_URI).apply();
            Log.d(TAG, "ลบการบันทึกรูปภาพ");
        }
    }

    /**
     * อัปเดตข้อมูลรูปภาพที่เลือก
     */
    private void updateImageInfo(Uri imageUri) {
        try {
            String fileName = getFileNameFromUri(imageUri);
            tvSelectedImageInfo.setText("เลือกแล้ว: " + fileName);
            
            // โหลดรูปภาพสำหรับ preview
            loadImagePreview(imageUri);
            
            // แสดงปุ่มใช้รูปภาพ
            btnApplyImage.setVisibility(View.VISIBLE);
            
            // บันทึกรูปภาพที่เลือก
            saveSelectedImage(imageUri);
            
            logMessage("อัปเดตข้อมูลรูปภาพ: " + fileName);
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอัปเดตข้อมูลรูปภาพ", e);
            tvSelectedImageInfo.setText("เลือกรูปภาพแล้ว (ไม่สามารถอ่านชื่อไฟล์ได้)");
        }
    }
    
    /**
     * โหลดรูปภาพสำหรับ preview
     */
    private void loadImagePreview(Uri imageUri) {
        new Thread(() -> {
            try {
                // โหลดรูปภาพแบบ resize เพื่อประหยัดหน่วยความจำ
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                if (inputStream != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(inputStream, null, options);
                    inputStream.close();
                    
                    // คำนวณ inSampleSize สำหรับ preview (ขนาดเล็กกว่า)
                    options.inSampleSize = calculateInSampleSize(options, 400, 300);
                    options.inJustDecodeBounds = false;
                    
                    inputStream = getContentResolver().openInputStream(imageUri);
                    Bitmap previewBitmap = BitmapFactory.decodeStream(inputStream, null, options);
                    inputStream.close();
                    
                    if (previewBitmap != null) {
                        runOnUiThread(() -> {
                            ivImagePreview.setImageBitmap(previewBitmap);
                            ivImagePreview.setVisibility(View.VISIBLE);
                            logMessage("แสดง preview รูปภาพสำเร็จ");
                        });
                    } else {
                        runOnUiThread(() -> {
                            logMessage("ไม่สามารถแสดง preview รูปภาพได้");
                        });
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "ไม่มีสิทธิ์ในการเข้าถึงไฟล์รูปภาพ", e);
                runOnUiThread(() -> {
                    logMessage("ไม่มีสิทธิ์ในการเข้าถึงไฟล์รูปภาพ กรุณาอนุญาตสิทธิ์");
                    clearSelectedImage();
                });
            } catch (Exception e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด preview", e);
                runOnUiThread(() -> {
                    logMessage("เกิดข้อผิดพลาดในการแสดง preview: " + e.getMessage());
                });
            }
        }).start();
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
     * ล้างรูปภาพที่เลือก
     */
    private void clearSelectedImage() {
        selectedImageUri = null;
        tvSelectedImageInfo.setText("ยังไม่ได้เลือกรูปภาพ");
        
        // ซ่อน preview และปุ่มใช้รูปภาพ
        ivImagePreview.setVisibility(View.GONE);
        ivImagePreview.setImageBitmap(null);
        btnApplyImage.setVisibility(View.GONE);
        
        // ลบการบันทึกรูปภาพ
        saveSelectedImage(null);
        
        logMessage("ล้างรูปภาพที่เลือกแล้ว");
        showMessage("ล้างรูปภาพแล้ว");
    }
    
    /**
     * ดึงชื่อไฟล์จาก URI
     */
    private String getFileNameFromUri(Uri uri) {
        String fileName = "ไฟล์ที่เลือก";
        try {
            String path = uri.getPath();
            if (path != null) {
                fileName = path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการดึงชื่อไฟล์", e);
        }
        return fileName;
    }

    // Implement CardReaderListener methods
    
    @Override
    public void onReaderStatusChanged(boolean connected, String readerName) {
        runOnUiThread(() -> {
            tvReaderName.setText(readerName);
            tvReaderStatus.setText(connected ? "เชื่อมต่อแล้ว (ทำงานในพื้นหลัง)" : "ไม่ได้เชื่อมต่อ");
            logMessage("สถานะเครื่องอ่านเปลี่ยนเป็น: " + (connected ? "เชื่อมต่อแล้ว" : "ไม่ได้เชื่อมต่อ"));
        });
    }

    @Override
    public void onCardDetected(String cardId) {
        runOnUiThread(() -> {
            tvCardId.setText(cardId);
            logMessage("ตรวจพบการ์ด (จากบริการพื้นหลัง): " + cardId);
        });
    }

    @Override
    public void onPdfOpened(String cardId, String pdfPath) {
        runOnUiThread(() -> {
            File file = new File(pdfPath);
            tvPdfFile.setText(file.getName());
            logMessage("เปิดไฟล์ PDF (จากบริการพื้นหลัง): " + file.getName());
        });
    }

    /**
     * ประมวลผลข้อมูลการ์ดที่อ่านได้และเชื่อมโยงกับไฟล์สื่อ
     */
    private void processCardInfo(String cardId) {
        try {
            if (cardId == null || cardId.isEmpty()) {
                Log.e(TAG, "cardId เป็น null หรือว่างเปล่า");
                return;
            }
            
            Log.d(TAG, "ประมวลผลข้อมูลการ์ด: " + cardId);
            
            // แสดง card ID บน UI
            final String finalCardId = cardId;
            runOnUiThread(() -> tvCardId.setText(finalCardId));
            
            // ค้นหาสื่อที่เชื่อมโยงกับการ์ด
            if (cardMediaMapping == null) {
                Log.e(TAG, "cardMediaMapping เป็น null");
                runOnUiThread(() -> {
                    tvPdfFile.setText("ไม่สามารถเชื่อมโยงได้");
                    tvStatusMessage.setText("ไม่สามารถเชื่อมโยงการ์ดกับไฟล์สื่อได้");
                });
                return;
            }
            
            String mediaPath = cardMediaMapping.findMediaForCard(cardId);
            
            if (mediaPath != null) {
                // พบสื่อที่เชื่อมโยงกับการ์ด
                MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(mediaPath);
                
                // ตรวจสอบว่าเป็นการ์ดเดิมและเปิดสื่อไปแล้วหรือไม่
                if (cardId.equals(lastCardId) && (cardId + ":" + mediaPath).equals(lastOpenedMediaForCardId)) {
                    logMessage("ข้าม: สื่อนี้ถูกเปิดไปแล้วสำหรับการ์ดนี้");
                    return;
                }
                
                logMessage("พบสื่อที่เชื่อมโยงกับการ์ด: " + mediaInfo.displayName + " (" + mediaInfo.type + ")");
                
                final String finalMediaPath = mediaPath;
                final MediaHelper.MediaInfo finalMediaInfo = mediaInfo;
                
                runOnUiThread(() -> {
                    // แสดงชื่อไฟล์ที่เหมาะสม
                    String displayText = finalMediaInfo.displayName;
                    switch (finalMediaInfo.type) {
                        case PDF:
                            displayText = "📄 " + finalMediaInfo.displayName;
                            break;
                        case VIDEO:
                            displayText = "🎬 " + finalMediaInfo.displayName;
                            break;
                        case WEB:
                            displayText = "🌐 " + finalMediaInfo.displayName;
                            break;
                        default:
                            displayText = "📁 " + finalMediaInfo.displayName;
                            break;
                    }
                    tvPdfFile.setText(displayText);
                    
                    // แสดงข้อความสถานะ
                    String statusMessage = "";
                    switch (finalMediaInfo.type) {
                        case PDF:
                            statusMessage = "พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด กำลังเปิด...";
                            break;
                        case VIDEO:
                            statusMessage = "พบไฟล์วิดีโอที่เชื่อมโยงกับการ์ด กำลังเปิด...";
                            break;
                        case WEB:
                            statusMessage = "พบเว็บไซต์ที่เชื่อมโยงกับการ์ด กำลังเปิด...";
                            break;
                        default:
                            statusMessage = "พบสื่อที่เชื่อมโยงกับการ์ด กำลังเปิด...";
                            break;
                    }
                    tvStatusMessage.setText(statusMessage);
                    
                    // เปิดสื่อโดยใช้ MediaHelper พร้อมปุ่มกลับและตัวจับเวลา
                    if (MediaHelper.openMedia(SettingsActivity.this, finalMediaPath, pdfLauncher, true, 120)) {
                        String successMessage = "";
                        switch (finalMediaInfo.type) {
                            case PDF:
                                successMessage = "เปิดไฟล์ PDF สำเร็จ";
                                break;
                            case VIDEO:
                                successMessage = "เปิดไฟล์วิดีโอสำเร็จ";
                                break;
                            case WEB:
                                successMessage = "เปิดเว็บไซต์สำเร็จ";
                                break;
                            default:
                                successMessage = "เปิดสื่อสำเร็จ";
                                break;
                        }
                        tvStatusMessage.setText(successMessage);
                        
                        // บันทึกว่าได้เปิดไฟล์นี้ไปแล้วสำหรับการ์ดนี้
                        lastCardId = cardId;
                        lastOpenedMediaForCardId = cardId + ":" + finalMediaPath;
                    } else {
                        tvStatusMessage.setText("ไม่สามารถเปิดสื่อได้");
                    }
                });
                
            } else {
                // ไม่พบสื่อที่เชื่อมโยงกับการ์ด
                logMessage("ไม่พบสื่อที่เชื่อมโยงกับการ์ด: " + cardId);
                
                runOnUiThread(() -> {
                    tvPdfFile.setText("ไม่พบ");
                    tvStatusMessage.setText("ไม่พบสื่อที่เชื่อมโยงกับการ์ดนี้");
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด", e);
            logMessage("เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด: " + e.getMessage());
            runOnUiThread(() -> tvStatusMessage.setText("เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด"));
        }
    }

    /**
     * อัปเดตสถานะของเครื่องอ่าน
     */
    private void updateReaderStatus(String status) {
        tvReaderStatus.setText(status);
    }

    /**
     * เพิ่มข้อความลงใน log
     */
    private void logMessage(String message) {
        runOnUiThread(() -> {
            try {
                String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
                String logEntry = "[" + timestamp + "] " + message;
                String currentLog = tvLogContent.getText().toString();
                if (currentLog.isEmpty()) {
                    tvLogContent.setText(logEntry);
                } else {
                    tvLogContent.setText(currentLog + "\n" + logEntry);
                }
                
                // หาตัว ScrollView ที่เป็น parent ของ TextView
                View parent = (View) tvLogContent.getParent();
                while (parent != null && !(parent instanceof ScrollView)) {
                    parent = (View) parent.getParent();
                }
                
                // เลื่อนลงล่างสุด (ถ้ามี ScrollView เป็น parent)
                if (parent != null && parent instanceof ScrollView) {
                    final ScrollView scrollView = (ScrollView) parent;
                    // ทำการ scroll หลังจากที่ TextView ได้ update แล้ว
                    scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
                
                Log.d(TAG, message);
            } catch (Exception e) {
                // ป้องกันไม่ให้เกิด crash ในฟังก์ชัน log
                Log.e(TAG, "Error in logMessage", e);
            }
        });
    }

    /**
     * แสดงข้อความ toast
     */
    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        logMessage(message);
    }

    /**
     * ขอสิทธิ์การเข้าถึง Storage
     */
    private void requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
                // สำหรับ Android 11 ขึ้นไป ต้องขอสิทธิ์ MANAGE_EXTERNAL_STORAGE
                if (!Environment.isExternalStorageManager()) {
                    logMessage("ต้องการสิทธิ์เพิ่มเติมในการเข้าถึงไฟล์");
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        // หากไม่สามารถเปิดหน้าตั้งค่าเฉพาะแอปได้ ให้เปิดหน้าตั้งค่าทั่วไปแทน
                        intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                } else {
                    logMessage("มีสิทธิ์ MANAGE_EXTERNAL_STORAGE แล้ว");
                    createTestMediaFiles(); // สร้างไฟล์สื่อตัวอย่างหลังจากได้รับสิทธิ์
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6-10
                // ตรวจสอบและขอสิทธิ์
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED) {
                    
                    ActivityCompat.requestPermissions(this,
                            new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            },
                            REQUEST_STORAGE_PERMISSION);
                } else {
                    logMessage("มีสิทธิ์เข้าถึงพื้นที่จัดเก็บข้อมูลแล้ว");
                    createTestMediaFiles(); // สร้างไฟล์สื่อตัวอย่างหลังจากได้รับสิทธิ์
                }
            } else { // Android 5 หรือต่ำกว่า
                // สิทธิ์จะถูกให้โดยอัตโนมัติตอนติดตั้ง
                logMessage("Android 5.1 หรือต่ำกว่า ได้รับสิทธิ์โดยอัตโนมัติ");
                createTestMediaFiles(); // สร้างไฟล์สื่อตัวอย่าง
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการขอสิทธิ์", e);
            logMessage("เกิดข้อผิดพลาดในการขอสิทธิ์: " + e.getMessage());
        }
    }

    /**
     * สร้างไฟล์สื่อตัวอย่างสำหรับทดสอบ (PDF, Video, Web)
     */
    private void createTestMediaFiles() {
        new Thread(() -> {
            try {
                // สร้างโฟลเดอร์ PDF
                File pdfFolder = new File("/storage/emulated/0/Download/pdf/");
                if (!pdfFolder.exists()) {
                    boolean created = pdfFolder.mkdirs();
                    Log.d(TAG, "สร้างโฟลเดอร์ PDF: " + (created ? "สำเร็จ" : "ไม่สำเร็จ"));
                }
                
                // สร้างโฟลเดอร์ Videos
                File videoFolder = new File("/storage/emulated/0/Download/videos/");
                if (!videoFolder.exists()) {
                    boolean created = videoFolder.mkdirs();
                    Log.d(TAG, "สร้างโฟลเดอร์ Videos: " + (created ? "สำเร็จ" : "ไม่สำเร็จ"));
                }
                
                // สร้างไฟล์ PDF ตัวอย่าง
                createSamplePdfFile(pdfFolder, "E_Book_7.pdf", "นี่คือเนื้อหาของ E_Book_7");
                createSamplePdfFile(pdfFolder, "7-Eleven.pdf", "นี่คือเนื้อหาของ 7-Eleven");
                createSamplePdfFile(pdfFolder, "golang_full_exam.pdf", "นี่คือเนื้อหาของ Golang Exam");
                createSamplePdfFile(pdfFolder, "history1.pdf", "นี่คือเนื้อหาของ History 1");
                createSamplePdfFile(pdfFolder, "science2.pdf", "นี่คือเนื้อหาของ Science 2");
                createSamplePdfFile(pdfFolder, "math.pdf", "นี่คือเนื้อหาของ Math");
                
                // สร้างไฟล์วิดีโอตัวอย่าง (แค่ไฟล์ว่างเพื่อทดสอบ)
                createSampleVideoFile(videoFolder, "sample_video.mp4");
                createSampleVideoFile(videoFolder, "presentation.mp4");
                createSampleVideoFile(videoFolder, "tutorial.mp4");
                createSampleVideoFile(videoFolder, "demo.avi");
                
                runOnUiThread(() -> logMessage("สร้างไฟล์สื่อตัวอย่างเรียบร้อยแล้ว (PDF และ Video)"));
            } catch (Exception e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการสร้างไฟล์สื่อ", e);
                runOnUiThread(() -> logMessage("เกิดข้อผิดพลาดในการสร้างไฟล์สื่อ: " + e.getMessage()));
            }
        }).start();
    }
    
    /**
     * สร้างไฟล์วิดีโอตัวอย่าง (ไฟล์ว่างเพื่อทดสอบ)
     */
    private void createSampleVideoFile(File folder, String fileName) {
        File file = new File(folder, fileName);
        try {
            if (!file.exists()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    // สร้างไฟล์ว่างเพื่อจำลองไฟล์วิดีโอ
                    fos.write("Sample video file for testing".getBytes());
                }
                Log.d(TAG, "สร้างไฟล์วิดีโอ " + fileName + " สำเร็จ");
            } else {
                Log.d(TAG, "ไฟล์วิดีโอ " + fileName + " มีอยู่แล้ว");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการสร้างไฟล์วิดีโอ " + fileName, e);
        }
    }

    /**
     * สร้างไฟล์ PDF ตัวอย่าง
     */
    private void createSamplePdfFile(File folder, String fileName, String content) {
        File file = new File(folder, fileName);
        try {
            if (!file.exists()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes());
                }
                Log.d(TAG, "สร้างไฟล์ " + fileName + " สำเร็จ");
            } else {
                Log.d(TAG, "ไฟล์ " + fileName + " มีอยู่แล้ว");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการสร้างไฟล์ " + fileName, e);
        }
    }

    /**
     * แสดงข้อความผิดพลาดแบบ Dialog
     */
    private void showErrorDialog(String title, String message) {
        try {
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("ตกลง", (dialog, which) -> dialog.dismiss())
                .setNegativeButton("ปิดแอพ", (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .setCancelable(false)
                .show();
        } catch (Exception e) {
            // หากไม่สามารถแสดง Dialog ได้ ให้ใช้ Toast แทน
            Toast.makeText(this, title + ": " + message, Toast.LENGTH_LONG).show();
            Log.e(TAG, "ไม่สามารถแสดง Error Dialog ได้: " + e.getMessage());
        }
    }

    /**
     * ตรวจสอบและขอสิทธิ์การแสดงหน้าต่างลอย (SYSTEM_ALERT_WINDOW)
     */
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                logMessage("ต้องการสิทธิ์ในการแสดงหน้าต่างลอยเพื่อแสดงปุ่มกลับ");
                
                new AlertDialog.Builder(this)
                    .setTitle("ต้องการสิทธิ์เพิ่มเติม")
                    .setMessage("แอพต้องการสิทธิ์ในการแสดงหน้าต่างลอยเพื่อแสดงปุ่มกลับขณะดู PDF")
                    .setPositiveButton("ตั้งค่า", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("ข้าม", (dialog, which) -> {
                        dialog.dismiss();
                        logMessage("ผู้ใช้ข้ามการตั้งค่าสิทธิ์การแสดงหน้าต่างลอย");
                    })
                    .show();
            } else {
                logMessage("มีสิทธิ์การแสดงหน้าต่างลอยแล้ว");
            }
        }
    }

    /**
     * เริ่มการทำงานของ FloatingButtonService
     */
    private void startFloatingButtonService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            checkOverlayPermission();
            return;
        }
        
        Intent serviceIntent = new Intent(this, FloatingButtonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        logMessage("เริ่มการทำงานของปุ่มลอยแล้ว");
    }
    
    /**
     * หยุดการทำงานของ FloatingButtonService
     */
    private void stopFloatingButtonService() {
        Intent serviceIntent = new Intent(this, FloatingButtonService.class);
        stopService(serviceIntent);
        logMessage("หยุดการทำงานของปุ่มลอยแล้ว");
    }

    @Override
    protected void onStart() {
        super.onStart();
        
        // เชื่อมต่อกับ CardReaderService ถ้าไม่ได้เชื่อมต่ออยู่แล้ว
        if (!mBound) {
            Intent intent = new Intent(this, CardReaderService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        // ยกเลิกการเชื่อมต่อกับ CardReaderService
        if (mBound) {
            mCardReaderService.setListener(null);
            unbindService(mConnection);
            mBound = false;
        }
    }

    /**
     * อัปเดต UI จากสถานะของ service
     */
    private void updateUIFromService() {
        if (mBound && mCardReaderService != null) {
            boolean isReaderConnected = mCardReaderService.isReaderConnected();
            String lastCardId = mCardReaderService.getLastCardId();
            
            runOnUiThread(() -> {
                // อัปเดตสถานะเครื่องอ่าน
                if (isReaderConnected) {
                    tvReaderStatus.setText("เชื่อมต่อแล้ว (ทำงานในพื้นหลัง)");
                    btnConnectReader.setEnabled(false);
                }
                
                // อัปเดต ID การ์ด
                if (lastCardId != null) {
                    tvCardId.setText(lastCardId);
                }
            });
        }
    }
    
    /**
     * เริ่มการทำงานของ CardReaderService
     */
    private void startBackgroundService() {
        logMessage("เริ่มบริการอ่านการ์ดในพื้นหลัง");
        
        Intent serviceIntent = new Intent(this, CardReaderService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // ปิดการทำงานของการอ่านการ์ดในหน้า UI
        if (mReaderOpened) {
            new Thread(() -> closeReader()).start();
        }
        
        // ปิดการใช้งานปุ่มเชื่อมต่อเครื่องอ่านในหน้า UI
        btnConnectReader.setEnabled(false);
        btnReadCard.setEnabled(false);
    }
    
    /**
     * หยุดการทำงานของ CardReaderService
     */
    private void stopBackgroundService() {
        logMessage("หยุดบริการอ่านการ์ดในพื้นหลัง");
        
        Intent serviceIntent = new Intent(this, CardReaderService.class);
        stopService(serviceIntent);
        
        // เปิดการใช้งานปุ่มเชื่อมต่อเครื่องอ่านในหน้า UI
        btnConnectReader.setEnabled(true);
        btnReadCard.setEnabled(true);
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
     * ขอสิทธิ์ในการเข้าถึงไฟล์สื่อ
     */
    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_MEDIA_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6-12
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_MEDIA_PERMISSION);
        }
        logMessage("กำลังขอสิทธิ์ในการเข้าถึงไฟล์สื่อ...");
    }
} 