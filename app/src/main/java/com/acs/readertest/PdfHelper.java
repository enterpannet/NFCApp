package com.acs.readertest;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/**
 * ช่วยเหลือในการเปิดไฟล์ PDF
 */
public class PdfHelper {
    private static final String TAG = "PdfHelper";
    private static Timer autoReturnTimer;
    private static PopupWindow returnButtonPopup;
    
    /**
     * เปิดไฟล์ PDF
     * 
     * @param context Context ของแอพ
     * @param pdfPath Path หรือ URL ของไฟล์ PDF
     * @return true ถ้าเปิดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public static boolean openPdf(Context context, String pdfPath) {
        try {
            if (pdfPath == null || pdfPath.isEmpty()) {
                Log.e(TAG, "path ของไฟล์ PDF เป็น null หรือว่างเปล่า");
                Toast.makeText(context, "ไม่พบ path ของไฟล์ PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
            
            Log.d(TAG, "กำลังเปิดไฟล์ PDF: " + pdfPath);
            
            // ลองใช้การเข้าถึงไฟล์โดยตรงก่อน หากเป็น Android 10 หรือต่ำกว่า หรือแอปมีสิทธิ์ MANAGE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || Environment.isExternalStorageManager()) {
                File directFile = getValidPdfFile(pdfPath);
                if (directFile != null && directFile.exists() && directFile.canRead()) {
                    return openPdfWithDirectFile(context, directFile);
                }
            }
            
            // ถ้าไม่สามารถเข้าถึงไฟล์โดยตรงได้ ให้ใช้ FileProvider
            return openPdfWithFileProvider(context, pdfPath);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    
    /**
     * เปิดไฟล์ PDF โดยใช้ ActivityResultLauncher
     * 
     * @param context Context ของแอพ
     * @param pdfPath Path หรือ URL ของไฟล์ PDF
     * @param launcher ActivityResultLauncher สำหรับเริ่ม activity และรับผลลัพธ์
     * @param showReturnButton true เพื่อแสดงปุ่มลอยสำหรับกลับมาที่แอพ
     * @param autoReturnSeconds จำนวนวินาทีที่จะกลับมาที่แอพโดยอัตโนมัติ (0 คือไม่ใช้ตัวจับเวลา)
     * @return true ถ้าเปิดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public static boolean openPdf(Context context, String pdfPath, ActivityResultLauncher<Intent> launcher, 
                                 boolean showReturnButton, int autoReturnSeconds) {
        try {
            if (pdfPath == null || pdfPath.isEmpty()) {
                Log.e(TAG, "path ของไฟล์ PDF เป็น null หรือว่างเปล่า");
                Toast.makeText(context, "ไม่พบ path ของไฟล์ PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
            
            Log.d(TAG, "กำลังเปิดไฟล์ PDF ด้วย ActivityResultLauncher: " + pdfPath);
            
            // หาไฟล์ PDF
            File file = getValidPdfFile(pdfPath);
            if (file == null || !file.exists() || !file.canRead()) {
                Log.e(TAG, "ไม่พบไฟล์ PDF หรือไม่สามารถอ่านได้: " + pdfPath);
                Toast.makeText(context, "ไม่พบไฟล์ PDF หรือไม่สามารถอ่านได้", Toast.LENGTH_SHORT).show();
                return false;
            }
            
            // สร้าง URI ด้วย FileProvider
            Uri uri;
            try {
                uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        file);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "ไม่สามารถสร้าง URI ด้วย FileProvider: " + e.getMessage(), e);
                return false;
            }
            
            // สร้าง Intent สำหรับเปิดไฟล์ PDF
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            // ตรวจสอบว่ามีแอพที่สามารถเปิดไฟล์ PDF หรือไม่
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                Log.e(TAG, "ไม่พบแอพสำหรับเปิดไฟล์ PDF");
                Toast.makeText(context, "ไม่พบแอพสำหรับเปิดไฟล์ PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
            
            // หยุดตัวจับเวลาและปิดปุ่มกลับเก่า (ถ้ามี)
            cancelAutoReturnTimer();
            hideReturnButton();
            
            // เริ่ม activity ด้วย launcher
            launcher.launch(intent);
            
            // แสดงปุ่มลอยสำหรับกลับมาที่แอพ (ถ้าต้องการ)
            if (showReturnButton) {
                // รอสักครู่ให้แอพ PDF เปิดก่อน แล้วจึงแสดงปุ่มลอย
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> showFloatingReturnButton(context, "กลับไปที่แอพ"),
                        1000);  // รอ 1 วินาที
            }
            
            // ตั้งตัวจับเวลาสำหรับกลับมาที่แอพโดยอัตโนมัติ (ถ้าต้องการ)
            if (autoReturnSeconds > 0) {
                startAutoReturnTimer(context, autoReturnSeconds);
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    
    /**
     * แสดงปุ่มลอยสำหรับกลับมาที่แอพ
     */
    private static void showFloatingReturnButton(Context context, String buttonText) {
        try {
            // เช็คว่ามีปุ่มเดิมแสดงอยู่หรือไม่ ถ้ามีให้ปิดก่อน
            hideReturnButton();
            
            // สร้างปุ่มลอย
            LayoutInflater inflater = LayoutInflater.from(context);
            View popupView = inflater.inflate(R.layout.popup_return_button, null);
            
            Button btnReturn = popupView.findViewById(R.id.btn_return_to_app);
            btnReturn.setText(buttonText);
            
            // สร้าง PopupWindow
            returnButtonPopup = new PopupWindow(
                    popupView,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    true);  // focusable
            
            // ตั้งค่าให้ปุ่มลอยแสดงเหนือแอพอื่น
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                returnButtonPopup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                returnButtonPopup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_PHONE);
            }
            
            // กำหนดให้ปุ่มเรียกแอพกลับมา
            btnReturn.setOnClickListener(v -> {
                // หยุดตัวจับเวลา
                cancelAutoReturnTimer();
                
                // ซ่อนปุ่ม
                hideReturnButton();
                
                // เรียกแอพกลับมา
                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(intent);
                
                // แสดงข้อความ
                Toast.makeText(context, "กลับมายังแอพแล้ว", Toast.LENGTH_SHORT).show();
            });
            
            // แสดงปุ่มลอยที่มุมขวาล่าง
            returnButtonPopup.showAtLocation(
                    new View(context),
                    Gravity.BOTTOM | Gravity.END,
                    30,  // ห่างจากขอบขวา 30px
                    100); // ห่างจากขอบล่าง 100px
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการแสดงปุ่มลอย: " + e.getMessage(), e);
        }
    }
    
    /**
     * ซ่อนปุ่มลอย
     */
    private static void hideReturnButton() {
        if (returnButtonPopup != null && returnButtonPopup.isShowing()) {
            returnButtonPopup.dismiss();
            returnButtonPopup = null;
        }
    }
    
    /**
     * เริ่มตัวจับเวลาสำหรับกลับมาที่แอพโดยอัตโนมัติ
     */
    private static void startAutoReturnTimer(Context context, int seconds) {
        // หยุดตัวจับเวลาเก่า (ถ้ามี)
        cancelAutoReturnTimer();
        
        // สร้างตัวจับเวลาใหม่
        autoReturnTimer = new Timer();
        autoReturnTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // ซ่อนปุ่มลอย
                hideReturnButton();
                
                // เรียกแอพกลับมา
                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(intent);
                
                // แสดงข้อความบน UI thread
                new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(context, "กลับมายังแอพโดยอัตโนมัติหลังจาก " + seconds + " วินาที", Toast.LENGTH_SHORT).show()
                );
            }
        }, seconds * 1000);  // แปลงวินาทีเป็นมิลลิวินาที
    }
    
    /**
     * หยุดตัวจับเวลา
     */
    private static void cancelAutoReturnTimer() {
        if (autoReturnTimer != null) {
            autoReturnTimer.cancel();
            autoReturnTimer = null;
        }
    }
    
    /**
     * เปิดไฟล์ PDF โดยใช้ ActivityResultLauncher (แบบง่าย)
     * 
     * @param context Context ของแอพ
     * @param pdfPath Path หรือ URL ของไฟล์ PDF
     * @param launcher ActivityResultLauncher สำหรับเริ่ม activity และรับผลลัพธ์
     * @return true ถ้าเปิดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public static boolean openPdf(Context context, String pdfPath, ActivityResultLauncher<Intent> launcher) {
        return openPdf(context, pdfPath, launcher, false, 0);
    }
    
    /**
     * เปิดไฟล์ PDF โดยใช้การเข้าถึงไฟล์โดยตรง
     */
    private static boolean openPdfWithDirectFile(Context context, File file) {
        try {
            Log.d(TAG, "กำลังเปิดไฟล์ PDF โดยตรง: " + file.getAbsolutePath());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // กำหนดประเภทของไฟล์
            intent.setDataAndType(Uri.fromFile(file), "application/pdf");
            
            // ตรวจสอบว่ามีแอพที่สามารถจัดการกับ Intent นี้หรือไม่
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
                return true;
            } else {
                Log.e(TAG, "ไม่พบแอพสำหรับเปิดไฟล์ PDF");
                Toast.makeText(context, "ไม่พบแอพสำหรับเปิดไฟล์ PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "ไม่พบแอพสำหรับเปิดไฟล์ PDF", e);
            Toast.makeText(context, "ไม่พบแอพสำหรับเปิดไฟล์ PDF", Toast.LENGTH_SHORT).show();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF โดยตรง", e);
            // ล้มเหลวในการเปิดไฟล์โดยตรง ให้ใช้ FileProvider แทน
            return openPdfWithFileProvider(context, file.getAbsolutePath());
        }
    }
    
    /**
     * เปิดไฟล์ PDF โดยใช้ FileProvider
     */
    private static boolean openPdfWithFileProvider(Context context, String pdfPath) {
        try {
            Log.d(TAG, "กำลังเปิดไฟล์ PDF ด้วย FileProvider: " + pdfPath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            Uri uri;
            
            // ตรวจสอบว่าเป็น URL หรือไม่
            if (pdfPath.startsWith("http://") || pdfPath.startsWith("https://")) {
                Log.d(TAG, "ใช้ URL โดยตรง: " + pdfPath);
                uri = Uri.parse(pdfPath);
            } else {
                // ถ้าเป็นไฟล์ใน local storage ให้ตรวจสอบ path และสร้าง File object
                File file = getValidPdfFile(pdfPath);
                
                if (file == null || !file.exists()) {
                    Log.e(TAG, "ไม่พบไฟล์ PDF ตาม path: " + pdfPath);
                    Toast.makeText(context, "ไม่พบไฟล์ PDF: " + pdfPath, Toast.LENGTH_SHORT).show();
                    // ลองสร้างไฟล์ว่างเปล่าเพื่อทดสอบการเข้าถึง
                    tryCreateEmptyFile(file);
                    return false;
                }
                
                if (!file.canRead()) {
                    Log.e(TAG, "ไม่สามารถอ่านไฟล์ PDF ได้: " + file.getAbsolutePath());
                    Toast.makeText(context, "ไม่สามารถอ่านไฟล์ PDF ได้: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                    logFilePermissions(file);
                    return false;
                }
                
                try {
                    Log.d(TAG, "ใช้ FileProvider สำหรับไฟล์: " + file.getAbsolutePath());
                    
                    // ใช้ FileProvider เพื่อแชร์ไฟล์กับแอพอื่น
                    uri = FileProvider.getUriForFile(
                            context,
                            context.getPackageName() + ".fileprovider",
                            file);
                    
                    Log.d(TAG, "URI จาก FileProvider: " + uri);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "FileProvider ไม่สามารถให้สิทธิ์เข้าถึงไฟล์ได้: " + e.getMessage(), e);
                    
                    // ลองใช้ ContentResolver
                    try {
                        ContentResolver contentResolver = context.getContentResolver();
                        uri = Uri.fromFile(file);
                        Log.d(TAG, "ใช้ Uri.fromFile แทน FileProvider: " + uri);
                    } catch (Exception ex) {
                        Log.e(TAG, "ไม่สามารถสร้าง URI จากไฟล์ได้: " + ex.getMessage(), ex);
                        Toast.makeText(context, 
                                "ไม่สามารถเข้าถึงไฟล์ PDF ได้: " + e.getMessage(), 
                                Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
            }
            
            intent.setDataAndType(uri, "application/pdf");
            
            // ตรวจสอบว่ามีแอพที่สามารถเปิดไฟล์ PDF ได้หรือไม่
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                Log.d(TAG, "กำลังเปิดไฟล์ PDF ด้วย external app");
                context.startActivity(intent);
                return true;
            } else {
                Log.e(TAG, "ไม่พบแอพสำหรับเปิดไฟล์ PDF");
                Toast.makeText(context, "ไม่พบแอพสำหรับเปิดไฟล์ PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF ด้วย FileProvider: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดไฟล์ PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    
    /**
     * ตรวจสอบและสร้าง File object ที่ถูกต้องสำหรับไฟล์ PDF
     */
    private static File getValidPdfFile(String pdfPath) {
        try {
            // ตรวจสอบว่าเป็น path เต็มหรือไม่
            if (pdfPath.startsWith("/")) {
                File file = new File(pdfPath);
                if (file.exists()) {
                    Log.d(TAG, "ใช้ path เต็ม: " + pdfPath);
                    return file;
                }
            }
            
            // ลองใช้ path สัมพัธ์ใน Download/pdf/
            File downloadPdfFile = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "pdf/" + new File(pdfPath).getName());
            if (downloadPdfFile.exists()) {
                Log.d(TAG, "พบไฟล์ใน Download/pdf/: " + downloadPdfFile.getAbsolutePath());
                return downloadPdfFile;
            }
            
            // ลองใช้ path แบบเก่า
            File legacyFile = new File("/storage/emulated/0/Download/pdf/", new File(pdfPath).getName());
            if (legacyFile.exists()) {
                Log.d(TAG, "พบไฟล์ใน legacy path: " + legacyFile.getAbsolutePath());
                return legacyFile;
            }
            
            // หากยังไม่พบ ให้ลองดูว่าเป็นชื่อไฟล์อย่างเดียวหรือไม่
            if (!pdfPath.contains("/")) {
                File fileNameOnly = new File("/storage/emulated/0/Download/pdf/", pdfPath);
                if (fileNameOnly.exists()) {
                    Log.d(TAG, "พบไฟล์โดยใช้ชื่อไฟล์อย่างเดียว: " + fileNameOnly.getAbsolutePath());
                    return fileNameOnly;
                }
            }
            
            // ถ้ายังไม่พบให้ลองใช้ path ตามที่รับมา
            Log.w(TAG, "ไม่พบไฟล์ในทุก path ที่ลองแล้ว ลองใช้ path เดิม: " + pdfPath);
            return new File(pdfPath);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการหาไฟล์ PDF: " + e.getMessage(), e);
            return new File(pdfPath); // ส่งคืน path เดิมถ้ามีข้อผิดพลาด
        }
    }
    
    /**
     * ลองสร้างไฟล์ว่างเปล่าเพื่อทดสอบการเข้าถึง
     */
    private static void tryCreateEmptyFile(File file) {
        try {
            if (file != null) {
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    boolean created = parentDir.mkdirs();
                    Log.d(TAG, "สร้างโฟลเดอร์พ่อแม่: " + (created ? "สำเร็จ" : "ไม่สำเร็จ"));
                }
                
                boolean created = file.createNewFile();
                Log.d(TAG, "ลองสร้างไฟล์ว่างเปล่า: " + (created ? "สำเร็จ" : "ไม่สำเร็จ"));
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการสร้างไฟล์ว่างเปล่า: " + e.getMessage(), e);
        }
    }
    
    /**
     * บันทึกข้อมูลสิทธิ์การเข้าถึงของไฟล์
     */
    private static void logFilePermissions(File file) {
        if (file != null) {
            Log.d(TAG, "ข้อมูลไฟล์: " + file.getAbsolutePath());
            Log.d(TAG, "- มีอยู่: " + file.exists());
            Log.d(TAG, "- สามารถอ่านได้: " + file.canRead());
            Log.d(TAG, "- สามารถเขียนได้: " + file.canWrite());
            Log.d(TAG, "- สามารถทำงานได้: " + file.canExecute());
            Log.d(TAG, "- ขนาด: " + file.length() + " ไบต์");
            
            // ตรวจสอบโฟลเดอร์พ่อแม่
            File parentDir = file.getParentFile();
            if (parentDir != null) {
                Log.d(TAG, "ข้อมูลโฟลเดอร์พ่อแม่: " + parentDir.getAbsolutePath());
                Log.d(TAG, "- มีอยู่: " + parentDir.exists());
                Log.d(TAG, "- สามารถอ่านได้: " + parentDir.canRead());
                Log.d(TAG, "- สามารถเขียนได้: " + parentDir.canWrite());
                Log.d(TAG, "- สามารถทำงานได้: " + parentDir.canExecute());
            }
        }
    }
} 