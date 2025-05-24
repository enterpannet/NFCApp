package com.acs.readertest;

import android.content.ActivityNotFoundException;
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
 * ช่วยเหลือในการเปิดไฟล์หลายประเภท (PDF, Video, Web)
 */
public class MediaHelper {
    private static final String TAG = "MediaHelper";
    private static Timer autoReturnTimer;
    private static PopupWindow returnButtonPopup;
    
    /**
     * ประเภทของสื่อ
     */
    public enum MediaType {
        PDF,
        VIDEO,
        WEB,
        UNKNOWN
    }
    
    /**
     * ข้อมูลของสื่อ
     */
    public static class MediaInfo {
        public MediaType type;
        public String path;
        public String displayName;
        
        public MediaInfo(MediaType type, String path, String displayName) {
            this.type = type;
            this.path = path;
            this.displayName = displayName;
        }
    }
    
    /**
     * ตรวจสอบประเภทของสื่อจาก path
     * 
     * @param path Path หรือ URL ของไฟล์
     * @return ประเภทของสื่อ
     */
    public static MediaType getMediaType(String path) {
        if (path == null || path.isEmpty()) {
            return MediaType.UNKNOWN;
        }
        
        String lowerPath = path.toLowerCase();
        
        // ตรวจสอบ content URI
        if (lowerPath.startsWith("content://")) {
            // ตรวจสอบจาก mime type หรือ path
            if (lowerPath.contains("pdf") || lowerPath.contains(".pdf")) {
                return MediaType.PDF;
            } else if (lowerPath.contains("video") || lowerPath.contains(".mp4") || 
                       lowerPath.contains(".avi") || lowerPath.contains(".mkv") ||
                       lowerPath.contains(".mov") || lowerPath.contains(".3gp") ||
                       lowerPath.contains(".webm")) {
                return MediaType.VIDEO;
            }
            // สำหรับ content URI ที่ไม่ชัดเจน ให้ตรวจสอบจาก provider
            if (lowerPath.contains("media") && lowerPath.contains("video")) {
                return MediaType.VIDEO;
            }
            return MediaType.UNKNOWN;
        }
        
        // ตรวจสอบ URL
        if (lowerPath.startsWith("http://") || lowerPath.startsWith("https://")) {
            // ตรวจสอบนามสกุลไฟล์ใน URL
            if (lowerPath.contains(".pdf")) {
                return MediaType.PDF;
            } else if (lowerPath.contains(".mp4") || lowerPath.contains(".avi") || 
                       lowerPath.contains(".mkv") || lowerPath.contains(".mov") ||
                       lowerPath.contains(".3gp") || lowerPath.contains(".webm")) {
                return MediaType.VIDEO;
            } else {
                // ถือว่าเป็น web page
                return MediaType.WEB;
            }
        }
        
        // ตรวจสอบนามสกุลไฟล์
        if (lowerPath.endsWith(".pdf")) {
            return MediaType.PDF;
        } else if (lowerPath.endsWith(".mp4") || lowerPath.endsWith(".avi") || 
                   lowerPath.endsWith(".mkv") || lowerPath.endsWith(".mov") ||
                   lowerPath.endsWith(".3gp") || lowerPath.endsWith(".webm") ||
                   lowerPath.endsWith(".flv") || lowerPath.endsWith(".wmv")) {
            return MediaType.VIDEO;
        }
        
        return MediaType.UNKNOWN;
    }
    
    /**
     * สร้าง MediaInfo จาก path
     * 
     * @param path Path หรือ URL ของไฟล์
     * @return MediaInfo object
     */
    public static MediaInfo createMediaInfo(String path) {
        MediaType type = getMediaType(path);
        String displayName = getDisplayName(path, type);
        return new MediaInfo(type, path, displayName);
    }
    
    /**
     * สร้างชื่อแสดงสำหรับไฟล์
     * 
     * @param path Path ของไฟล์
     * @param type ประเภทของสื่อ
     * @return ชื่อที่จะแสดง
     */
    private static String getDisplayName(String path, MediaType type) {
        if (path == null || path.isEmpty()) {
            return "ไฟล์ไม่ทราบชื่อ";
        }
        
        // ถ้าเป็น URL ให้แสดงแบบพิเศษ
        if (path.startsWith("http://") || path.startsWith("https://")) {
            switch (type) {
                case PDF:
                    return "PDF Online: " + getDomainName(path);
                case VIDEO:
                    return "Video Online: " + getDomainName(path);
                case WEB:
                    return "เว็บไซต์: " + getDomainName(path);
                default:
                    return "ลิงก์: " + getDomainName(path);
            }
        }
        
        // ถ้าเป็นไฟล์ local ให้แสดงชื่อไฟล์
        try {
            File file = new File(path);
            String fileName = file.getName();
            if (fileName.isEmpty()) {
                return "ไฟล์ไม่ทราบชื่อ";
            }
            return fileName;
        } catch (Exception e) {
            return path;
        }
    }
    
    /**
     * ดึงชื่อโดเมนจาก URL
     */
    private static String getDomainName(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception e) {
            Log.w(TAG, "ไม่สามารถดึงชื่อโดเมนได้: " + e.getMessage());
        }
        return "Unknown";
    }
    
    /**
     * เปิดสื่อตามประเภท
     * 
     * @param context Context ของแอพ
     * @param mediaPath Path หรือ URL ของไฟล์
     * @param launcher ActivityResultLauncher สำหรับเริ่ม activity และรับผลลัพธ์
     * @param showReturnButton true เพื่อแสดงปุ่มลอยสำหรับกลับมาที่แอพ
     * @param autoReturnSeconds จำนวนวินาทีที่จะกลับมาที่แอพโดยอัตโนมัติ (0 คือไม่ใช้ตัวจับเวลา)
     * @return true ถ้าเปิดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public static boolean openMedia(Context context, String mediaPath, ActivityResultLauncher<Intent> launcher, 
                                   boolean showReturnButton, int autoReturnSeconds) {
        MediaInfo mediaInfo = createMediaInfo(mediaPath);
        Log.d(TAG, "กำลังเปิดสื่อ: " + mediaInfo.displayName + " ประเภท: " + mediaInfo.type);
        
        switch (mediaInfo.type) {
            case PDF:
                return openPdf(context, mediaPath, launcher, showReturnButton, autoReturnSeconds);
            case VIDEO:
                return openVideo(context, mediaPath, launcher, showReturnButton, autoReturnSeconds);
            case WEB:
                return openWeb(context, mediaPath, launcher, showReturnButton, autoReturnSeconds);
            default:
                Log.e(TAG, "ประเภทไฟล์ไม่รองรับ: " + mediaPath);
                Toast.makeText(context, "ประเภทไฟล์ไม่รองรับ", Toast.LENGTH_SHORT).show();
                return false;
        }
    }
    
    /**
     * เปิดไฟล์ PDF
     */
    private static boolean openPdf(Context context, String pdfPath, ActivityResultLauncher<Intent> launcher, 
                                  boolean showReturnButton, int autoReturnSeconds) {
        return PdfHelper.openPdf(context, pdfPath, launcher, showReturnButton, autoReturnSeconds);
    }
    
    /**
     * เปิดไฟล์วิดีโอ
     */
    private static boolean openVideo(Context context, String videoPath, ActivityResultLauncher<Intent> launcher, 
                                    boolean showReturnButton, int autoReturnSeconds) {
        try {
            Log.d(TAG, "กำลังเปิดไฟล์วิดีโอ: " + videoPath);
            
            Intent intent;
            
            // ถ้าเป็น URL
            if (videoPath.startsWith("http://") || videoPath.startsWith("https://")) {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(videoPath), "video/*");
            } else if (videoPath.startsWith("content://")) {
                // ถ้าเป็น content URI
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(videoPath), "video/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // ถ้าเป็นไฟล์ local
                File videoFile = getValidVideoFile(videoPath);
                if (videoFile == null || !videoFile.exists()) {
                    Log.e(TAG, "ไม่พบไฟล์วิดีโอ: " + videoPath);
                    Toast.makeText(context, "ไม่พบไฟล์วิดีโอ", Toast.LENGTH_SHORT).show();
                    return false;
                }
                
                Uri uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        videoFile);
                
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "video/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            
            // ตรวจสอบว่ามีแอพที่สามารถเปิดวิดีโอหรือไม่
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                Log.e(TAG, "ไม่พบแอพสำหรับเปิดไฟล์วิดีโอ");
                Toast.makeText(context, "ไม่พบแอพสำหรับเปิดไฟล์วิดีโอ กรุณาติดตั้งแอพเล่นวิดีโอ", Toast.LENGTH_LONG).show();
                return false;
            }
            
            // หยุดตัวจับเวลาและปิดปุ่มกลับเก่า (ถ้ามี)
            cancelAutoReturnTimer();
            hideReturnButton();
            
            // เริ่ม activity
            launcher.launch(intent);
            
            // แสดงปุ่มลอยและตัวจับเวลา
            setupReturnFeatures(context, showReturnButton, autoReturnSeconds, "กลับจากวิดีโอ");
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดไฟล์วิดีโอ: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดไฟล์วิดีโอ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    
    /**
     * เปิดเว็บไซต์
     */
    private static boolean openWeb(Context context, String webUrl, ActivityResultLauncher<Intent> launcher, 
                                  boolean showReturnButton, int autoReturnSeconds) {
        try {
            Log.d(TAG, "กำลังเปิดเว็บไซต์: " + webUrl);
            
            // ตรวจสอบ URL
            if (!webUrl.startsWith("http://") && !webUrl.startsWith("https://")) {
                webUrl = "https://" + webUrl;
            }
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(webUrl));
            
            // ตรวจสอบว่ามีแอพที่สามารถเปิดเว็บไซต์หรือไม่
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                Log.e(TAG, "ไม่พบแอพสำหรับเปิดเว็บไซต์");
                Toast.makeText(context, "ไม่พบแอพสำหรับเปิดเว็บไซต์ กรุณาติดตั้งเบราว์เซอร์", Toast.LENGTH_LONG).show();
                return false;
            }
            
            // หยุดตัวจับเวลาและปิดปุ่มกลับเก่า (ถ้ามี)
            cancelAutoReturnTimer();
            hideReturnButton();
            
            // เริ่ม activity
            launcher.launch(intent);
            
            // แสดงปุ่มลอยและตัวจับเวลา
            setupReturnFeatures(context, showReturnButton, autoReturnSeconds, "กลับจากเว็บไซต์");
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดเว็บไซต์: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดเว็บไซต์: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    
    /**
     * ตั้งค่าปุ่มกลับและตัวจับเวลา
     */
    private static void setupReturnFeatures(Context context, boolean showReturnButton, 
                                           int autoReturnSeconds, String buttonText) {
        // แสดงปุ่มลอยสำหรับกลับมาที่แอพ (ถ้าต้องการ)
        if (showReturnButton) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> showFloatingReturnButton(context, buttonText),
                    1000);  // รอ 1 วินาที
        }
        
        // ตั้งตัวจับเวลาสำหรับกลับมาที่แอพโดยอัตโนมัติ (ถ้าต้องการ)
        if (autoReturnSeconds > 0) {
            startAutoReturnTimer(context, autoReturnSeconds);
        }
    }
    
    /**
     * หาไฟล์วิดีโอที่ถูกต้อง
     */
    private static File getValidVideoFile(String videoPath) {
        try {
            // ลองหาจาก path ที่ให้มา
            File directFile = new File(videoPath);
            if (directFile.exists() && directFile.canRead()) {
                return directFile;
            }
            
            // ลองหาในโฟลเดอร์ videos
            String fileName = new File(videoPath).getName();
            
            // ลองหาในโฟลเดอร์ Videos ใน Download
            File downloadVideosDir = new File("/storage/emulated/0/Download/videos/");
            File videoFile = new File(downloadVideosDir, fileName);
            if (videoFile.exists() && videoFile.canRead()) {
                return videoFile;
            }
            
            // ลองหาในโฟลเดอร์ Download
            File downloadDir = new File("/storage/emulated/0/Download/");
            videoFile = new File(downloadDir, fileName);
            if (videoFile.exists() && videoFile.canRead()) {
                return videoFile;
            }
            
            // ลองหาในโฟลเดอร์ Movies
            File moviesDir = new File("/storage/emulated/0/Movies/");
            videoFile = new File(moviesDir, fileName);
            if (videoFile.exists() && videoFile.canRead()) {
                return videoFile;
            }
            
            Log.e(TAG, "ไม่พบไฟล์วิดีโอ: " + videoPath);
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการหาไฟล์วิดีโอ: " + e.getMessage(), e);
            return null;
        }
    }
    
    // ใช้ฟังก์ชันต่างๆ จาก PdfHelper ที่เหมือนกัน
    
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
                    ((android.app.Activity) context).findViewById(android.R.id.content),
                    Gravity.BOTTOM | Gravity.END,
                    50, 150);
            
            Log.d(TAG, "แสดงปุ่มลอยกลับแอพ");
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการแสดงปุ่มลอย: " + e.getMessage(), e);
        }
    }
    
    /**
     * ซ่อนปุ่มลอย
     */
    private static void hideReturnButton() {
        try {
            if (returnButtonPopup != null && returnButtonPopup.isShowing()) {
                returnButtonPopup.dismiss();
                returnButtonPopup = null;
                Log.d(TAG, "ซ่อนปุ่มลอยแล้ว");
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการซ่อนปุ่มลอย: " + e.getMessage(), e);
        }
    }
    
    /**
     * เริ่มตัวจับเวลาสำหรับกลับมาที่แอพโดยอัตโนมัติ
     */
    private static void startAutoReturnTimer(Context context, int seconds) {
        autoReturnTimer = new Timer();
        autoReturnTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "ตัวจับเวลากลับแอพทำงาน: " + seconds + " วินาที");
                
                // ซ่อนปุ่มลอย
                hideReturnButton();
                
                // กลับมาที่แอพ
                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(intent);
                
                // แสดงข้อความแจ้งเตือน
                new Handler(Looper.getMainLooper()).post(() -> 
                    Toast.makeText(context, "กลับมาที่แอพโดยอัตโนมัติแล้ว", Toast.LENGTH_SHORT).show());
            }
        }, seconds * 1000L); // แปลงเป็น milliseconds
        
        Log.d(TAG, "ตั้งตัวจับเวลากลับแอพ: " + seconds + " วินาที");
    }
    
    /**
     * ยกเลิกตัวจับเวลา
     */
    private static void cancelAutoReturnTimer() {
        if (autoReturnTimer != null) {
            autoReturnTimer.cancel();
            autoReturnTimer = null;
            Log.d(TAG, "ยกเลิกตัวจับเวลากลับแอพแล้ว");
        }
    }
    
    /**
     * เปิดสื่อแบบง่าย (ไม่มีปุ่มกลับและตัวจับเวลา)
     */
    public static boolean openMedia(Context context, String mediaPath) {
        MediaInfo mediaInfo = createMediaInfo(mediaPath);
        
        try {
            Intent intent;
            
            switch (mediaInfo.type) {
                case PDF:
                    return PdfHelper.openPdf(context, mediaPath);
                    
                case VIDEO:
                    if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) {
                        intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(mediaPath), "video/*");
                    } else if (mediaPath.startsWith("content://")) {
                        // ถ้าเป็น content URI
                        intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(mediaPath), "video/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        File videoFile = getValidVideoFile(mediaPath);
                        if (videoFile == null || !videoFile.exists()) {
                            Toast.makeText(context, "ไม่พบไฟล์วิดีโอ", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", videoFile);
                        intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, "video/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    break;
                    
                case WEB:
                    String webUrl = mediaPath;
                    if (!webUrl.startsWith("http://") && !webUrl.startsWith("https://")) {
                        webUrl = "https://" + webUrl;
                    }
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(webUrl));
                    break;
                    
                default:
                    Toast.makeText(context, "ประเภทไฟล์ไม่รองรับ", Toast.LENGTH_SHORT).show();
                    return false;
            }
            
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                String errorMessage = "";
                switch (mediaInfo.type) {
                    case VIDEO:
                        errorMessage = "ไม่พบแอพเล่นวิดีโอ กรุณาติดตั้งแอพเล่นวิดีโอ";
                        break;
                    case WEB:
                        errorMessage = "ไม่พบเบราว์เซอร์ กรุณาติดตั้งเบราว์เซอร์";
                        break;
                    default:
                        errorMessage = "ไม่พบแอพที่รองรับสำหรับไฟล์นี้";
                        break;
                }
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show();
                return false;
            }
            
            context.startActivity(intent);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดสื่อ: " + e.getMessage(), e);
            Toast.makeText(context, "เกิดข้อผิดพลาดในการเปิดสื่อ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
} 