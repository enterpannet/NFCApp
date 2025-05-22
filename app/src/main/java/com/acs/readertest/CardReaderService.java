package com.acs.readertest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.acs.smartcard.Reader;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/**
 * บริการอ่านการ์ดที่ทำงานในพื้นหลัง
 */
public class CardReaderService extends Service {
    private static final String TAG = "CardReaderService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "card_reader_channel";
    private static final int CARD_POLLING_INTERVAL = 1000; // 1 วินาที
    
    // Reader และอุปกรณ์
    private UsbManager mManager;
    private Reader mReader;
    private int mSlotNum = 0;
    private boolean mReaderOpened = false;
    private NfcCardReader nfcCardReader;
    private CardPdfMapping cardPdfMapping;
    private Timer cardPollingTimer;
    private String lastCardId = null;
    private String lastOpenedPdfForCardId = null;

    // Handler สำหรับทำงานใน UI Thread
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // Binder สำหรับให้ Activity เข้าถึง Service
    private final IBinder binder = new LocalBinder();
    
    /**
     * Binder class สำหรับการเชื่อมต่อกับ Activity
     */
    public class LocalBinder extends Binder {
        CardReaderService getService() {
            return CardReaderService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "เริ่ม CardReaderService");
        
        // เริ่มต้นตัวแปรต่างๆ
        initializeComponents();
        
        // โหลด CardPdfMapping
        initializeCardMapping();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        
        // สร้าง Notification สำหรับ Foreground Service
        createNotificationChannel();
        Notification notification = buildNotification("บริการอ่านการ์ด NFC กำลังทำงาน");
        startForeground(NOTIFICATION_ID, notification);
        
        // เริ่มต้นการเชื่อมต่อกับเครื่องอ่าน
        connectToAvailableReader();
        
        // กรณีที่ Service ถูกหยุดโดยระบบ ให้เริ่มใหม่
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        // หยุดการทำงานทั้งหมด
        stopCardPolling();
        closeReader();
        super.onDestroy();
        Log.d(TAG, "หยุด CardReaderService");
    }
    
    /**
     * เริ่มต้นตัวแปรต่างๆ
     */
    private void initializeComponents() {
        // ตั้งค่า USB Manager
        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        // ตั้งค่า Reader
        UsbReader usbReader = UsbReader.getInstance(this);
        mReader = usbReader.getReader();
        nfcCardReader = new NfcCardReader(mReader);
    }
    
    /**
     * โหลด CardPdfMapping
     */
    private void initializeCardMapping() {
        try {
            cardPdfMapping = new CardPdfMapping();
            boolean mappingLoaded = cardPdfMapping.loadMapping(this);
            if (mappingLoaded) {
                Log.d(TAG, "โหลด mapping สำเร็จ");
            } else {
                Log.w(TAG, "ไม่สามารถโหลด mapping ได้ ใช้ mapping ว่างเปล่าแทน");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด mapping: ", e);
            cardPdfMapping = new CardPdfMapping(); // สร้าง mapping ว่างเปล่า
        }
    }
    
    /**
     * สร้าง notification channel (จำเป็นสำหรับ Android 8.0 ขึ้นไป)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Card Reader Service",
                    NotificationManager.IMPORTANCE_LOW); // ใช้ LOW เพื่อไม่ให้มีเสียงและการสั่น
            channel.setDescription("แจ้งเตือนการทำงานของเครื่องอ่านการ์ด");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * สร้าง notification สำหรับ foreground service
     */
    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("แอพอ่านการ์ด NFC")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    /**
     * อัปเดต notification ด้วยข้อความใหม่
     */
    private void updateNotification(String text) {
        Notification notification = buildNotification(text);
        NotificationManager notificationManager = 
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    /**
     * เชื่อมต่อกับเครื่องอ่านที่มีอยู่
     */
    public void connectToAvailableReader() {
        try {
            // ตรวจสอบว่ามีเครื่องอ่านที่รองรับเชื่อมต่ออยู่หรือไม่
            for (UsbDevice device : mManager.getDeviceList().values()) {
                if (mReader != null && mReader.isSupported(device) && mManager.hasPermission(device)) {
                    openReader(device);
                    return;
                }
            }
            
            // ไม่พบเครื่องอ่านที่มีสิทธิ์การเข้าถึง
            updateNotification("ไม่พบเครื่องอ่านการ์ด หรือไม่มีสิทธิ์เข้าถึง");
            Log.d(TAG, "ไม่พบเครื่องอ่านการ์ด หรือไม่มีสิทธิ์เข้าถึง");
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเชื่อมต่อเครื่องอ่าน: ", e);
        }
    }
    
    /**
     * เปิดการเชื่อมต่อกับเครื่องอ่าน
     */
    private void openReader(UsbDevice device) {
        try {
            // เปิดการเชื่อมต่อกับ reader
            mReader.open(device);
            
            // อัปเดตสถานะ
            mReaderOpened = true;
            String deviceName = device.getProductName() != null ? device.getProductName() : device.getDeviceName();
            updateNotification("เชื่อมต่อกับเครื่องอ่าน: " + deviceName);
            Log.d(TAG, "เชื่อมต่อกับเครื่องอ่านสำเร็จ: " + deviceName);
            
            // เริ่ม polling เพื่อรอการ์ด
            startCardPolling();
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดเครื่องอ่าน", e);
            mReaderOpened = false;
            updateNotification("เกิดข้อผิดพลาดในการเชื่อมต่อเครื่องอ่าน");
        }
    }
    
    /**
     * ปิดการเชื่อมต่อกับเครื่องอ่าน
     */
    private void closeReader() {
        try {
            if (mReaderOpened) {
                // หยุด polling
                stopCardPolling();
                
                // ปิดการเชื่อมต่อกับ reader
                mReader.close();
                
                // อัปเดตสถานะ
                mReaderOpened = false;
                updateNotification("ยกเลิกการเชื่อมต่อเครื่องอ่านแล้ว");
                Log.d(TAG, "ปิดการเชื่อมต่อกับเครื่องอ่านแล้ว");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการปิดเครื่องอ่าน", e);
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
        
        Log.d(TAG, "เริ่มการตรวจสอบการ์ดอัตโนมัติ");
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
            if (!mReaderOpened || nfcCardReader == null) {
                return;
            }
            
            // อ่าน UID ก่อน
            String uid = nfcCardReader.readCardUid(mSlotNum);
            if (uid != null) {
                Log.d(TAG, "อ่าน UID สำเร็จ: " + uid);
                processCardInfo(uid);
                return;
            }
            
            // ถ้าอ่าน UID ไม่สำเร็จ ให้ลองอ่าน NDEF
            String ndefText = nfcCardReader.readNdefText(mSlotNum);
            if (ndefText != null) {
                Log.d(TAG, "อ่าน NDEF Text สำเร็จ: " + ndefText);
                processCardInfo(ndefText);
                return;
            }
            
            // ถ้าทั้ง UID และ NDEF ไม่สำเร็จ
            if (lastCardId != null) {
                // รีเซ็ตสถานะเมื่อไม่พบการ์ด
                lastCardId = null;
                lastOpenedPdfForCardId = null;
                updateNotification("รอการอ่านการ์ด...");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่านการ์ด", e);
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
            
            // ตรวจสอบว่าเป็นการ์ดเดิมหรือไม่
            if (cardId.equals(lastCardId)) {
                // เป็นการ์ดเดิม ไม่ต้องทำอะไร
                return;
            }
            
            Log.d(TAG, "ประมวลผลข้อมูลการ์ด: " + cardId);
            updateNotification("พบการ์ด: " + cardId);
            
            // บันทึกการ์ดปัจจุบัน
            lastCardId = cardId;
            
            // ค้นหา PDF ที่เชื่อมโยงกับการ์ด
            if (cardPdfMapping == null) {
                Log.e(TAG, "cardPdfMapping เป็น null");
                updateNotification("ไม่สามารถเชื่อมโยงการ์ดกับไฟล์ PDF ได้");
                return;
            }
            
            String pdfPath = cardPdfMapping.findPdfForCard(cardId);
            
            if (pdfPath != null) {
                // พบ PDF ที่เชื่อมโยงกับการ์ด
                String fileName = new File(pdfPath).getName();
                
                // ตรวจสอบว่าเป็นไฟล์เดิมที่เปิดไปแล้วหรือไม่
                if ((cardId + ":" + pdfPath).equals(lastOpenedPdfForCardId)) {
                    Log.d(TAG, "ข้าม: ไฟล์ PDF นี้ถูกเปิดไปแล้วสำหรับการ์ดนี้");
                    return;
                }
                
                Log.d(TAG, "พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + fileName);
                updateNotification("พบ PDF: " + fileName + " - กำลังเปิด...");
                
                // บันทึกว่าได้เปิดไฟล์นี้สำหรับการ์ดนี้
                lastOpenedPdfForCardId = cardId + ":" + pdfPath;
                
                // เปิดไฟล์ PDF ด้วย Handler เพื่อให้ทำงานใน UI Thread
                final String finalPdfPath = pdfPath;
                handler.post(() -> {
                    try {
                        openPdfFile(finalPdfPath);
                    } catch (Exception e) {
                        Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage());
                        updateNotification("เกิดข้อผิดพลาดในการเปิดไฟล์ PDF");
                    }
                });
            } else {
                // ไม่พบ PDF ที่เชื่อมโยงกับการ์ด
                Log.d(TAG, "ไม่พบไฟล์ PDF ที่เชื่อมโยงกับการ์ด: " + cardId);
                updateNotification("ไม่พบไฟล์ PDF สำหรับการ์ด: " + cardId);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการประมวลผลข้อมูลการ์ด", e);
        }
    }
    
    /**
     * เปิดไฟล์ PDF
     */
    private void openPdfFile(String pdfPath) {
        try {
            // สร้าง Intent สำหรับเปิดไฟล์ PDF
            File file = new File(pdfPath);
            if (!file.exists() || !file.canRead()) {
                Toast.makeText(this, "ไม่พบไฟล์หรือไม่สามารถอ่านไฟล์ได้", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // สร้าง URI ด้วย FileProvider
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    file);
            
            // สร้าง Intent สำหรับเปิดไฟล์ PDF
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // ตรวจสอบว่ามีแอพที่สามารถเปิดไฟล์ PDF หรือไม่
            if (intent.resolveActivity(getPackageManager()) != null) {
                // เปิด PDF viewer
                startActivity(intent);
                updateNotification("กำลังแสดงไฟล์ PDF ของการ์ด: " + lastCardId);
            } else {
                Toast.makeText(this, "ไม่พบแอพที่สามารถเปิดไฟล์ PDF ได้", Toast.LENGTH_SHORT).show();
                updateNotification("ไม่พบแอพที่สามารถเปิดไฟล์ PDF ได้");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening PDF: " + e.getMessage());
            Toast.makeText(this, "เกิดข้อผิดพลาด: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * ส่งออกเหตุการณ์ไปยัง Activity
     */
    public interface CardReaderListener {
        void onReaderStatusChanged(boolean connected, String readerName);
        void onCardDetected(String cardId);
        void onPdfOpened(String cardId, String pdfPath);
    }
    
    private CardReaderListener listener;
    
    public void setListener(CardReaderListener listener) {
        this.listener = listener;
    }
    
    public boolean isReaderConnected() {
        return mReaderOpened;
    }
    
    public String getLastCardId() {
        return lastCardId;
    }
} 