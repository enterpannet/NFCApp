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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.acs.smartcard.Reader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

/**
 * แอพสำหรับอ่านการ์ด NFC ด้วย ACR122U และเปิดไฟล์ PDF ที่เชื่อมโยงกับการ์ด
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.acs.readertest.USB_PERMISSION";
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int CARD_POLLING_INTERVAL = 1000; // 1 วินาที

    // UI elements
    private TextView tvReaderName;
    private TextView tvReaderStatus;
    private Button btnConnectReader;
    private TextView tvCardId;
    private TextView tvPdfFile;
    private TextView tvStatusMessage;
    private Button btnReadCard;
    private TextView tvLogContent;

    // USB และ Reader
    private UsbManager mManager;
    private Reader mReader;
    private PendingIntent mPermissionIntent;
    private int mSlotNum = 0;
    private boolean mReaderOpened = false;
    private NfcCardReader nfcCardReader;
    private CardPdfMapping cardPdfMapping;
    private Timer cardPollingTimer;
    private String lastCardId = null;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_main);

            // ตั้งค่า UI elements
            initializeUIElements();
            
            // แสดงข้อความเริ่มต้น
            logMessage("กำลังเริ่มต้นแอพ...");
            
            // เริ่มต้นขอสิทธิ์การเข้าถึง Storage
            requestStoragePermission();
            
            // ตรวจสอบและสร้างโฟลเดอร์สำหรับเก็บไฟล์ PDF ถ้ายังไม่มี
            createTestPdfFiles();

            // ตั้งค่า USB Manager และ Reader
            initializeReaderComponents();

            // โหลด CardPdfMapping
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
            showErrorDialog("เกิดข้อผิดพลาดในการเริ่มต้นแอพ", e.getMessage());
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
                logMessage("ได้รับสิทธิ์การเข้าถึงไฟล์แล้ว");
                // สร้างไฟล์ PDF ตัวอย่าง
                createTestPdfFiles();
            } else {
                logMessage("สิทธิ์การเข้าถึงไฟล์ถูกปฏิเสธ อาจไม่สามารถเปิดไฟล์ PDF ได้");
                // แสดงข้อความแนะนำการตั้งค่าสิทธิ์เพิ่มเติม
                new AlertDialog.Builder(this)
                    .setTitle("ต้องการสิทธิ์การเข้าถึงไฟล์")
                    .setMessage("แอพนี้จำเป็นต้องได้รับสิทธิ์การเข้าถึงไฟล์เพื่อเปิดไฟล์ PDF กรุณาให้สิทธิ์ในการตั้งค่า")
                    .setPositiveButton("ไปที่ตั้งค่า", (dialog, which) -> {
                        dialog.dismiss();
                        // เปิดหน้าตั้งค่าแอป
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    })
                    .setNegativeButton("ยกเลิก", (dialog, which) -> dialog.dismiss())
                    .setCancelable(false)
                    .show();
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
            cardPdfMapping = new CardPdfMapping();
            boolean mappingLoaded = cardPdfMapping.loadMapping(this);
            if (mappingLoaded) {
                Log.d(TAG, "โหลด mapping สำเร็จ");
                logMessage("โหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์ PDF สำเร็จ");
            } else {
                Log.w(TAG, "ไม่สามารถโหลด mapping ได้");
                logMessage("ไม่สามารถโหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์ PDF ได้ ใช้ mapping ว่างเปล่าแทน");
                showMessage("ไม่สามารถโหลดข้อมูลการเชื่อมโยงการ์ดกับไฟล์ PDF ได้");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด mapping: ", e);
            logMessage("เกิดข้อผิดพลาดในการโหลด mapping: " + e.getMessage());
            cardPdfMapping = new CardPdfMapping(); // สร้าง mapping ว่างเปล่า
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
            });
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่านการ์ด", e);
            logMessage("เกิดข้อผิดพลาดในการอ่านการ์ด: " + e.getMessage());
            runOnUiThread(() -> tvStatusMessage.setText("เกิดข้อผิดพลาดในการอ่านการ์ด"));
        }
    }

    /**
     * ประมวลผลข้อมูลการ์ดที่อ่านได้และเชื่อมโยงกับไฟล์ PDF
     */
    private void processCardInfo(String cardId) {
        try {
            if (cardId == null || cardId.isEmpty()) {
                Log.e(TAG, "cardId เป็น null หรือว่างเปล่า");
                return;
            }
            
            Log.d(TAG, "ประมวลผลข้อมูลการ์ด: " + cardId);
            lastCardId = cardId;
            
            // แสดง card ID บน UI
            final String finalCardId = cardId;
            runOnUiThread(() -> tvCardId.setText(finalCardId));
            
            // ค้นหา PDF ที่เชื่อมโยงกับการ์ด
            if (cardPdfMapping == null) {
                Log.e(TAG, "cardPdfMapping เป็น null");
                runOnUiThread(() -> {
                    tvPdfFile.setText("ไม่สามารถเชื่อมโยงได้");
                    tvStatusMessage.setText("ไม่สามารถเชื่อมโยงการ์ดกับไฟล์ PDF ได้");
                });
                return;
            }
            
            String pdfPath = cardPdfMapping.findPdfForCard(cardId);
            
            if (pdfPath != null) {
                // พบ PDF ที่เชื่อมโยงกับการ์ด
                String fileName = new File(pdfPath).getName();
                logMessage("พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + fileName);
                
                final String finalPdfPath = pdfPath;
                final String finalFileName = fileName;
                
                runOnUiThread(() -> {
                    tvPdfFile.setText(finalFileName);
                    tvStatusMessage.setText("พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด กำลังเปิด...");
                    
                    // เปิดไฟล์ PDF
                    if (PdfHelper.openPdf(MainActivity.this, finalPdfPath)) {
                        tvStatusMessage.setText("เปิดไฟล์ PDF สำเร็จ");
                    } else {
                        tvStatusMessage.setText("ไม่สามารถเปิดไฟล์ PDF ได้");
                    }
                });
                
            } else {
                // ไม่พบ PDF ที่เชื่อมโยงกับการ์ด
                logMessage("ไม่พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + cardId);
                
                runOnUiThread(() -> {
                    tvPdfFile.setText("ไม่พบ");
                    tvStatusMessage.setText("ไม่พบไฟล์ PDF ที่เชื่อมโยงกับการ์ดนี้");
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
                    createTestPdfFiles(); // สร้างไฟล์ PDF ตัวอย่างหลังจากได้รับสิทธิ์
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
                    createTestPdfFiles(); // สร้างไฟล์ PDF ตัวอย่างหลังจากได้รับสิทธิ์
                }
            } else { // Android 5 หรือต่ำกว่า
                // สิทธิ์จะถูกให้โดยอัตโนมัติตอนติดตั้ง
                logMessage("Android 5.1 หรือต่ำกว่า ได้รับสิทธิ์โดยอัตโนมัติ");
                createTestPdfFiles(); // สร้างไฟล์ PDF ตัวอย่าง
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการขอสิทธิ์", e);
            logMessage("เกิดข้อผิดพลาดในการขอสิทธิ์: " + e.getMessage());
        }
    }

    /**
     * สร้างไฟล์ PDF ตัวอย่างสำหรับทดสอบ
     */
    private void createTestPdfFiles() {
        new Thread(() -> {
            try {
                // สร้างโฟลเดอร์ PDF
                File pdfFolder = new File("/storage/emulated/0/Download/pdf/");
                if (!pdfFolder.exists()) {
                    boolean created = pdfFolder.mkdirs();
                    Log.d(TAG, "สร้างโฟลเดอร์ PDF: " + (created ? "สำเร็จ" : "ไม่สำเร็จ"));
                }
                
                // สร้างไฟล์ PDF ตัวอย่าง
                createSamplePdfFile(pdfFolder, "E_Book_7.pdf", "นี่คือเนื้อหาของ E_Book_7");
                createSamplePdfFile(pdfFolder, "7-Eleven.pdf", "นี่คือเนื้อหาของ 7-Eleven");
                createSamplePdfFile(pdfFolder, "golang_full_exam.pdf", "นี่คือเนื้อหาของ Golang Exam");
                createSamplePdfFile(pdfFolder, "history1.pdf", "นี่คือเนื้อหาของ History 1");
                createSamplePdfFile(pdfFolder, "science2.pdf", "นี่คือเนื้อหาของ Science 2");
                createSamplePdfFile(pdfFolder, "math.pdf", "นี่คือเนื้อหาของ Math");
                
                runOnUiThread(() -> logMessage("สร้างไฟล์ PDF ตัวอย่างเรียบร้อยแล้ว"));
            } catch (Exception e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการสร้างไฟล์ PDF", e);
                runOnUiThread(() -> logMessage("เกิดข้อผิดพลาดในการสร้างไฟล์ PDF: " + e.getMessage()));
            }
        }).start();
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
}
