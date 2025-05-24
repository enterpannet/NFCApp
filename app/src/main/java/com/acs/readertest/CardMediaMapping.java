package com.acs.readertest;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * คลาสสำหรับจัดการ mapping ระหว่าง card ID (UID หรือ NDEF) กับไฟล์สื่อหลายประเภท (PDF, Video, Web)
 * พร้อมระบบ CRUD สำหรับจัดการข้อมูล
 */
public class CardMediaMapping {
    private static final String TAG = "CardMediaMapping";
    private static final String MAPPING_FILE_NAME = "mapping.json";
    
    private Map<String, String> mappingData = new HashMap<>();
    private Context context;
    
    /**
     * โหลด mapping จากไฟล์ mapping.json
     * 
     * @param context Context ของแอพ
     * @return true ถ้าโหลดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean loadMapping(Context context) {
        this.context = context;
        try {
            Log.d(TAG, "กำลังพยายามโหลด mapping สำหรับสื่อหลายประเภท...");
            
            // ลองโหลดจาก external storage ก่อน (ข้อมูลที่ผู้ใช้แก้ไข)
            File externalFile = new File(context.getExternalFilesDir(null), MAPPING_FILE_NAME);
            Log.d(TAG, "ลองโหลดจากไฟล์: " + externalFile.getAbsolutePath());
            if (externalFile.exists()) {
                boolean loaded = loadMappingFromFile(externalFile);
                if (loaded) {
                    Log.d(TAG, "โหลด mapping จาก external storage สำเร็จ");
                    return true;
                }
            }
            
            // ถ้าไม่มีใน external storage ให้ลองจาก assets
            if (loadMappingFromAssets(context)) {
                Log.d(TAG, "โหลด mapping จาก assets สำเร็จ");
                // บันทึกไปยัง external storage เพื่อให้แก้ไขได้ในอนาคต
                saveMapping();
                return true;
            }
            
            // ถ้าไม่มีไฟล์ให้สร้าง mapping ว่างเปล่า
            Log.d(TAG, "ไม่พบ mapping ใดๆ สร้าง mapping ว่างเปล่า");
            mappingData = new HashMap<>();
            // สร้างไฟล์ว่างใน external storage
            saveMapping();
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "ไม่สามารถโหลด mapping ได้", e);
            // ให้ทำงานต่อได้ด้วย mapping ว่างเปล่า
            mappingData = new HashMap<>();
            return false;
        }
    }
    
    /**
     * โหลด mapping จากไฟล์
     */
    private boolean loadMappingFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder jsonString = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonString.append(line);
            }
            
            if (jsonString.length() == 0) {
                Log.d(TAG, "ไฟล์ mapping ว่างเปล่า");
                mappingData = new HashMap<>();
                return true;
            }
            
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                mappingData = gson.fromJson(jsonString.toString(), type);
                
                if (mappingData == null) {
                    Log.e(TAG, "แปลง JSON เป็น mapping ไม่สำเร็จ");
                    mappingData = new HashMap<>();
                }
                
                Log.d(TAG, "โหลด mapping จากไฟล์สำเร็จ: " + mappingData.size() + " รายการ");
                return true;
            } catch (JsonSyntaxException e) {
                Log.e(TAG, "รูปแบบ JSON ไม่ถูกต้อง", e);
                mappingData = new HashMap<>();
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "ไม่สามารถอ่านไฟล์ mapping ได้", e);
            mappingData = new HashMap<>();
            return false;
        }
    }
    
    /**
     * โหลด mapping จาก assets
     */
    private boolean loadMappingFromAssets(Context context) {
        try {
            InputStream is = context.getAssets().open(MAPPING_FILE_NAME);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder jsonString = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonString.append(line);
                }
                
                if (jsonString.length() == 0) {
                    Log.d(TAG, "ไฟล์ mapping ใน assets ว่างเปล่า");
                    mappingData = new HashMap<>();
                    return true;
                }
                
                try {
                    Gson gson = new Gson();
                    Type type = new TypeToken<Map<String, String>>(){}.getType();
                    Log.d(TAG, "JSON Content: " + jsonString.toString());
                    mappingData = gson.fromJson(jsonString.toString(), type);
                    
                    if (mappingData == null) {
                        Log.e(TAG, "แปลง JSON ใน assets เป็น mapping ไม่สำเร็จ");
                        mappingData = new HashMap<>();
                    }
                    
                    Log.d(TAG, "โหลด mapping จาก assets สำเร็จ: " + mappingData.size() + " รายการ");
                    // แสดงข้อมูล mapping ที่โหลดได้
                    for (Map.Entry<String, String> entry : mappingData.entrySet()) {
                        MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(entry.getValue());
                        Log.d(TAG, "Mapping: " + entry.getKey() + " -> " + mediaInfo.displayName + " (" + mediaInfo.type + ")");
                    }
                    return true;
                } catch (JsonSyntaxException e) {
                    Log.e(TAG, "รูปแบบ JSON ใน assets ไม่ถูกต้อง", e);
                    mappingData = new HashMap<>();
                    return false;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "ไม่สามารถอ่านไฟล์ mapping จาก assets ได้", e);
            return false;
        }
    }
    
    /**
     * บันทึก mapping ลงไฟล์ external storage
     * 
     * @return true ถ้าบันทึกสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean saveMapping() {
        if (context == null) {
            Log.e(TAG, "ไม่สามารถบันทึกได้ เนื่องจาก context เป็น null");
            return false;
        }
        
        try {
            File externalFile = new File(context.getExternalFilesDir(null), MAPPING_FILE_NAME);
            
            Gson gson = new Gson();
            String jsonString = gson.toJson(mappingData);
            
            try (FileWriter writer = new FileWriter(externalFile)) {
                writer.write(jsonString);
                writer.flush();
            }
            
            Log.d(TAG, "บันทึก mapping ลงไฟล์สำเร็จ: " + externalFile.getAbsolutePath());
            Log.d(TAG, "ข้อมูลที่บันทึก: " + jsonString);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการบันทึก mapping", e);
            return false;
        }
    }
    
    /**
     * เพิ่มการ์ดใหม่ (CREATE)
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @param mediaPath path ของไฟล์สื่อ
     * @return true ถ้าเพิ่มสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean addCardMapping(String cardId, String mediaPath) {
        if (cardId == null || cardId.trim().isEmpty()) {
            Log.e(TAG, "cardId ไม่สามารถเป็น null หรือว่างเปล่าได้");
            return false;
        }
        
        if (mediaPath == null || mediaPath.trim().isEmpty()) {
            Log.e(TAG, "mediaPath ไม่สามารถเป็น null หรือว่างเปล่าได้");
            return false;
        }
        
        try {
            mappingData.put(cardId.trim(), mediaPath.trim());
            boolean saved = saveMapping();
            
            if (saved) {
                Log.d(TAG, "เพิ่มการ์ดใหม่สำเร็จ: " + cardId + " -> " + mediaPath);
                MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(mediaPath);
                Log.d(TAG, "ประเภทสื่อ: " + mediaInfo.type + ", ชื่อแสดง: " + mediaInfo.displayName);
            } else {
                Log.e(TAG, "เพิ่มการ์ดใหม่ไม่สำเร็จ: ไม่สามารถบันทึกไฟล์ได้");
            }
            
            return saved;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการเพิ่มการ์ด", e);
            return false;
        }
    }
    
    /**
     * อัปเดตข้อมูลการ์ด (UPDATE)
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @param newMediaPath path ใหม่ของไฟล์สื่อ
     * @return true ถ้าอัปเดตสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean updateCardMapping(String cardId, String newMediaPath) {
        if (cardId == null || cardId.trim().isEmpty()) {
            Log.e(TAG, "cardId ไม่สามารถเป็น null หรือว่างเปล่าได้");
            return false;
        }
        
        if (newMediaPath == null || newMediaPath.trim().isEmpty()) {
            Log.e(TAG, "newMediaPath ไม่สามารถเป็น null หรือว่างเปล่าได้");
            return false;
        }
        
        try {
            if (!mappingData.containsKey(cardId.trim())) {
                Log.w(TAG, "ไม่พบการ์ด " + cardId + " ในระบบ");
                return false;
            }
            
            String oldMediaPath = mappingData.get(cardId.trim());
            mappingData.put(cardId.trim(), newMediaPath.trim());
            boolean saved = saveMapping();
            
            if (saved) {
                Log.d(TAG, "อัปเดตการ์ดสำเร็จ: " + cardId);
                Log.d(TAG, "จาก: " + oldMediaPath + " เป็น: " + newMediaPath);
                MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(newMediaPath);
                Log.d(TAG, "ประเภทสื่อใหม่: " + mediaInfo.type + ", ชื่อแสดง: " + mediaInfo.displayName);
            } else {
                // หากบันทึกไม่สำเร็จ ให้คืนค่าเดิม
                mappingData.put(cardId.trim(), oldMediaPath);
                Log.e(TAG, "อัปเดตการ์ดไม่สำเร็จ: ไม่สามารถบันทึกไฟล์ได้");
            }
            
            return saved;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอัปเดตการ์ด", e);
            return false;
        }
    }
    
    /**
     * ลบการ์ด (DELETE)
     * 
     * @param cardId UID หรือ NDEF text จากการ์ดที่ต้องการลบ
     * @return true ถ้าลบสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean removeCardMapping(String cardId) {
        if (cardId == null || cardId.trim().isEmpty()) {
            Log.e(TAG, "cardId ไม่สามารถเป็น null หรือว่างเปล่าได้");
            return false;
        }
        
        try {
            if (!mappingData.containsKey(cardId.trim())) {
                Log.w(TAG, "ไม่พบการ์ด " + cardId + " ในระบบ");
                return false;
            }
            
            String removedMediaPath = mappingData.remove(cardId.trim());
            boolean saved = saveMapping();
            
            if (saved) {
                Log.d(TAG, "ลบการ์ดสำเร็จ: " + cardId + " (ไฟล์สื่อ: " + removedMediaPath + ")");
            } else {
                // หากบันทึกไม่สำเร็จ ให้เพิ่มข้อมูลกลับคืน
                mappingData.put(cardId.trim(), removedMediaPath);
                Log.e(TAG, "ลบการ์ดไม่สำเร็จ: ไม่สามารถบันทึกไฟล์ได้");
            }
            
            return saved;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการลบการ์ด", e);
            return false;
        }
    }
    
    /**
     * ตรวจสอบว่ามีการ์ดอยู่ในระบบหรือไม่
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @return true ถ้ามีการ์ดในระบบ, false ถ้าไม่มี
     */
    public boolean hasCard(String cardId) {
        if (cardId == null || cardId.trim().isEmpty()) {
            return false;
        }
        return mappingData.containsKey(cardId.trim());
    }
    
    /**
     * ล้างข้อมูลการ์ดทั้งหมด
     * 
     * @return true ถ้าล้างสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean clearAllMappings() {
        try {
            int originalSize = mappingData.size();
            mappingData.clear();
            boolean saved = saveMapping();
            
            if (saved) {
                Log.d(TAG, "ล้างข้อมูลการ์ดทั้งหมดสำเร็จ (ทั้งหมด " + originalSize + " รายการ)");
            } else {
                Log.e(TAG, "ล้างข้อมูลการ์ดไม่สำเร็จ: ไม่สามารถบันทึกไฟล์ได้");
            }
            
            return saved;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการล้างข้อมูลการ์ด", e);
            return false;
        }
    }
    
    /**
     * ค้นหาไฟล์สื่อที่ mapping กับ cardId
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @return path ของไฟล์สื่อถ้าพบ, null ถ้าไม่พบ
     */
    public String findMediaForCard(String cardId) {
        if (mappingData == null || cardId == null) {
            Log.e(TAG, "mappingData หรือ cardId เป็น null");
            return null;
        }
        
        Log.d(TAG, "ค้นหาสื่อสำหรับ cardId: " + cardId);
        if (mappingData.containsKey(cardId)) {
            String mediaPath = mappingData.get(cardId);
            
            if (mediaPath == null || mediaPath.isEmpty()) {
                Log.e(TAG, "พบ mapping แต่ path เป็น null หรือว่างเปล่า");
                return null;
            }
            
            // ตรวจสอบประเภทของสื่อ
            MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(mediaPath);
            Log.d(TAG, "พบสื่อ: " + mediaInfo.displayName + " ประเภท: " + mediaInfo.type);
            
            // ตรวจสอบว่าเป็น URL หรือไม่
            if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) {
                Log.d(TAG, "พบสื่อออนไลน์: " + mediaPath);
                return mediaPath;
            }
            
            // ถ้าเป็นไฟล์ local ให้ตรวจสอบการมีอยู่
            switch (mediaInfo.type) {
                case PDF:
                    return findLocalPdfFile(mediaPath);
                case VIDEO:
                    return findLocalVideoFile(mediaPath);
                default:
                    // สำหรับประเภทอื่นๆ (เช่น WEB หรือ UNKNOWN) ให้ส่งคืนค่า path ตัวเดิม
                    Log.d(TAG, "ส่งคืนค่า path สำหรับประเภท: " + mediaInfo.type);
                    return mediaPath;
            }
        }
        
        Log.d(TAG, "ไม่พบ mapping สำหรับ cardId: " + cardId);
        return null;
    }
    
    /**
     * หาไฟล์ PDF ใน local storage
     */
    private String findLocalPdfFile(String pdfPath) {
        // ถ้าเป็น path เต็มอยู่แล้ว
        if (pdfPath.startsWith("/")) {
            File pdfFile = new File(pdfPath);
            if (pdfFile.exists()) {
                Log.d(TAG, "พบไฟล์ PDF (path เต็ม): " + pdfPath);
                return pdfPath;
            } else {
                Log.e(TAG, "ไม่พบไฟล์ PDF ตาม path: " + pdfPath);
                // ลองใช้ path อื่นๆ
                File fallbackFile = tryAlternativePdfPaths(pdfPath);
                if (fallbackFile != null) {
                    return fallbackFile.getAbsolutePath();
                }
                return null;
            }
        } else {
            // เป็น path สัมพัธ์ ให้ลองหลาย path
            File pdfFile = new File("/storage/emulated/0/Download/pdf/", pdfPath);
            if (pdfFile.exists()) {
                Log.d(TAG, "พบไฟล์ PDF (path สัมพัธ์): " + pdfFile.getAbsolutePath());
                return pdfFile.getAbsolutePath();
            } else {
                // ลองหา path อื่นๆ
                File fallbackFile = tryAlternativePdfPaths(pdfPath);
                if (fallbackFile != null) {
                    return fallbackFile.getAbsolutePath();
                }
                
                // ถ้ายังไม่พบ แต่เราต้องการให้แอพทำงานต่อไปได้
                Log.w(TAG, "ไม่พบไฟล์ PDF ตาม path ที่กำหนด แต่จะส่งคืนค่า path เพื่อให้ MediaHelper ลองจัดการ: " + pdfPath);
                return pdfPath;
            }
        }
    }
    
    /**
     * หาไฟล์วิดีโอใน local storage
     */
    private String findLocalVideoFile(String videoPath) {
        // ถ้าเป็น path เต็มอยู่แล้ว
        if (videoPath.startsWith("/")) {
            File videoFile = new File(videoPath);
            if (videoFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอ (path เต็ม): " + videoPath);
                return videoPath;
            } else {
                Log.e(TAG, "ไม่พบไฟล์วิดีโอตาม path: " + videoPath);
                File fallbackFile = tryAlternativeVideoPaths(videoPath);
                if (fallbackFile != null) {
                    return fallbackFile.getAbsolutePath();
                }
                return null;
            }
        } else {
            // เป็น path สัมพัธ์ ให้ลองหลาย path
            File videoFile = new File("/storage/emulated/0/Download/videos/", videoPath);
            if (videoFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอ (path สัมพัธ์): " + videoFile.getAbsolutePath());
                return videoFile.getAbsolutePath();
            } else {
                File fallbackFile = tryAlternativeVideoPaths(videoPath);
                if (fallbackFile != null) {
                    return fallbackFile.getAbsolutePath();
                }
                
                Log.w(TAG, "ไม่พบไฟล์วิดีโอตาม path ที่กำหนด แต่จะส่งคืนค่า path เพื่อให้ MediaHelper ลองจัดการ: " + videoPath);
                return videoPath;
            }
        }
    }

    /**
     * ลองหาไฟล์ PDF จาก path ทางเลือกต่างๆ
     */
    private File tryAlternativePdfPaths(String pdfFileName) {
        try {
            // ตัดเอาเฉพาะชื่อไฟล์
            String fileName = new File(pdfFileName).getName();
            Log.d(TAG, "ลองหาไฟล์ PDF ด้วยชื่อไฟล์: " + fileName);
            
            // ลองหาในโฟลเดอร์ PDF ใน Download
            File downloadPdfDir = new File("/storage/emulated/0/Download/pdf/");
            File directDownloadFile = new File(downloadPdfDir, fileName);
            if (directDownloadFile.exists()) {
                Log.d(TAG, "พบไฟล์ PDF ใน Download/pdf/: " + directDownloadFile.getAbsolutePath());
                return directDownloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Download
            File downloadDir = new File("/storage/emulated/0/Download/");
            File downloadFile = new File(downloadDir, fileName);
            if (downloadFile.exists()) {
                Log.d(TAG, "พบไฟล์ PDF ใน Download/: " + downloadFile.getAbsolutePath());
                return downloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Documents
            File documentsDir = new File("/storage/emulated/0/Documents/");
            File documentsFile = new File(documentsDir, fileName);
            if (documentsFile.exists()) {
                Log.d(TAG, "พบไฟล์ PDF ใน Documents/: " + documentsFile.getAbsolutePath());
                return documentsFile;
            }
            
            Log.e(TAG, "ไม่พบไฟล์ PDF " + fileName + " ในทุก path ที่ลองแล้ว");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการหา PDF path ทางเลือก: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * ลองหาไฟล์วิดีโอจาก path ทางเลือกต่างๆ
     */
    private File tryAlternativeVideoPaths(String videoFileName) {
        try {
            // ตัดเอาเฉพาะชื่อไฟล์
            String fileName = new File(videoFileName).getName();
            Log.d(TAG, "ลองหาไฟล์วิดีโอด้วยชื่อไฟล์: " + fileName);
            
            // ลองหาในโฟลเดอร์ Videos ใน Download
            File downloadVideosDir = new File("/storage/emulated/0/Download/videos/");
            File directDownloadFile = new File(downloadVideosDir, fileName);
            if (directDownloadFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอใน Download/videos/: " + directDownloadFile.getAbsolutePath());
                return directDownloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Download
            File downloadDir = new File("/storage/emulated/0/Download/");
            File downloadFile = new File(downloadDir, fileName);
            if (downloadFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอใน Download/: " + downloadFile.getAbsolutePath());
                return downloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Movies
            File moviesDir = new File("/storage/emulated/0/Movies/");
            File moviesFile = new File(moviesDir, fileName);
            if (moviesFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอใน Movies/: " + moviesFile.getAbsolutePath());
                return moviesFile;
            }
            
            // ลองหาในโฟลเดอร์ DCIM (สำหรับวิดีโอที่ถ่ายเอง)
            File dcimDir = new File("/storage/emulated/0/DCIM/Camera/");
            File dcimFile = new File(dcimDir, fileName);
            if (dcimFile.exists()) {
                Log.d(TAG, "พบไฟล์วิดีโอใน DCIM/Camera/: " + dcimFile.getAbsolutePath());
                return dcimFile;
            }
            
            Log.e(TAG, "ไม่พบไฟล์วิดีโอ " + fileName + " ในทุก path ที่ลองแล้ว");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการหาวิดีโอ path ทางเลือก: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * รับข้อมูล MediaInfo สำหรับการ์ด
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @return MediaInfo object หรือ null ถ้าไม่พบ
     */
    public MediaHelper.MediaInfo getMediaInfoForCard(String cardId) {
        String mediaPath = findMediaForCard(cardId);
        if (mediaPath != null) {
            return MediaHelper.createMediaInfo(mediaPath);
        }
        return null;
    }
    
    /**
     * ดึงจำนวน mapping ทั้งหมด
     */
    public int getMappingCount() {
        return mappingData != null ? mappingData.size() : 0;
    }
    
    /**
     * ดึงข้อมูล mapping ทั้งหมด (สำหรับ debug)
     */
    public Map<String, String> getAllMappings() {
        return new HashMap<>(mappingData);
    }
    
    // เพิ่มฟังก์ชันเก่าเพื่อ backward compatibility
    /**
     * @deprecated ใช้ findMediaForCard แทน
     */
    @Deprecated
    public String findPdfForCard(String cardId) {
        String mediaPath = findMediaForCard(cardId);
        if (mediaPath != null) {
            MediaHelper.MediaType type = MediaHelper.getMediaType(mediaPath);
            if (type == MediaHelper.MediaType.PDF) {
                return mediaPath;
            }
        }
        return null;
    }
} 