package com.acs.readertest;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * คลาสสำหรับจัดการ mapping ระหว่าง card ID (UID หรือ NDEF) กับไฟล์ PDF
 */
public class CardPdfMapping {
    private static final String TAG = "CardPdfMapping";
    private static final String MAPPING_FILE_NAME = "mapping.json";
    
    private Map<String, String> mappingData = new HashMap<>();
    
    /**
     * โหลด mapping จากไฟล์ mapping.json
     * 
     * @param context Context ของแอพ
     * @return true ถ้าโหลดสำเร็จ, false ถ้ามีข้อผิดพลาด
     */
    public boolean loadMapping(Context context) {
        try {
            Log.d(TAG, "กำลังพยายามโหลด mapping...");
            // ลองโหลดจาก assets ก่อน
            if (loadMappingFromAssets(context)) {
                Log.d(TAG, "โหลด mapping จาก assets สำเร็จ");
                return true;
            }
            
            // ถ้าไม่สำเร็จให้ลองจาก external storage
            File externalFile = new File(context.getExternalFilesDir(null), MAPPING_FILE_NAME);
            Log.d(TAG, "ลองโหลดจากไฟล์: " + externalFile.getAbsolutePath());
            if (externalFile.exists()) {
                return loadMappingFromFile(externalFile);
            } else {
                Log.d(TAG, "ไม่พบไฟล์ mapping ใน external storage");
            }
            
            // ถ้าไม่มีไฟล์ให้สร้าง mapping ว่างเปล่า
            Log.d(TAG, "ไม่พบ mapping ใดๆ สร้าง mapping ว่างเปล่า");
            mappingData = new HashMap<>();
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
                        Log.d(TAG, "Mapping: " + entry.getKey() + " -> " + entry.getValue());
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
     * ค้นหาไฟล์ PDF ที่ mapping กับ cardId
     * 
     * @param cardId UID หรือ NDEF text จากการ์ด
     * @return path ของไฟล์ PDF ถ้าพบ, null ถ้าไม่พบ
     */
    public String findPdfForCard(String cardId) {
        if (mappingData == null || cardId == null) {
            Log.e(TAG, "mappingData หรือ cardId เป็น null");
            return null;
        }
        
        Log.d(TAG, "ค้นหา PDF สำหรับ cardId: " + cardId);
        if (mappingData.containsKey(cardId)) {
            String pdfPath = mappingData.get(cardId);
            
            if (pdfPath == null || pdfPath.isEmpty()) {
                Log.e(TAG, "พบ mapping แต่ path เป็น null หรือว่างเปล่า");
                return null;
            }
            
            // ตรวจสอบว่าเป็น URL หรือไม่
            if (pdfPath.startsWith("http://") || pdfPath.startsWith("https://")) {
                Log.d(TAG, "พบ PDF URL: " + pdfPath);
                return pdfPath;
            }
            
            // ถ้าไม่ใช่ URL ให้ตรวจสอบว่าเป็น path เต็มหรือไม่
            if (pdfPath.startsWith("/")) {
                // เป็น path เต็มอยู่แล้ว
                File pdfFile = new File(pdfPath);
                if (pdfFile.exists()) {
                    Log.d(TAG, "พบไฟล์ PDF (path เต็ม): " + pdfPath);
                    return pdfPath;
                } else {
                    Log.e(TAG, "ไม่พบไฟล์ PDF ตาม path: " + pdfPath);
                    // ลองใช้ path อื่นๆ
                    File fallbackFile = tryAlternativePaths(pdfPath);
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
                    File fallbackFile = tryAlternativePaths(pdfPath);
                    if (fallbackFile != null) {
                        return fallbackFile.getAbsolutePath();
                    }
                    
                    // ถ้ายังไม่พบ แต่เราต้องการให้แอพทำงานต่อไปได้ ให้ส่งคืนค่า path สัมพัธ์
                    // ซึ่ง PdfHelper จะจัดการเอง
                    Log.w(TAG, "ไม่พบไฟล์ PDF ตาม path ที่กำหนด แต่จะส่งคืนค่า path เพื่อให้ PdfHelper ลองจัดการ: " + pdfPath);
                    return pdfPath;
                }
            }
        }
        
        Log.d(TAG, "ไม่พบ mapping สำหรับ cardId: " + cardId);
        return null;
    }

    /**
     * ลองหาไฟล์ PDF จาก path ทางเลือกต่างๆ
     */
    private File tryAlternativePaths(String pdfFileName) {
        try {
            // ตัดเอาเฉพาะชื่อไฟล์
            String fileName = new File(pdfFileName).getName();
            Log.d(TAG, "ลองหาไฟล์ด้วยชื่อไฟล์: " + fileName);
            
            // ลองหาในโฟลเดอร์ PDF ใน Download
            File downloadPdfDir = new File("/storage/emulated/0/Download/pdf/");
            File directDownloadFile = new File(downloadPdfDir, fileName);
            if (directDownloadFile.exists()) {
                Log.d(TAG, "พบไฟล์ใน Download/pdf/: " + directDownloadFile.getAbsolutePath());
                return directDownloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Download
            File downloadDir = new File("/storage/emulated/0/Download/");
            File downloadFile = new File(downloadDir, fileName);
            if (downloadFile.exists()) {
                Log.d(TAG, "พบไฟล์ใน Download/: " + downloadFile.getAbsolutePath());
                return downloadFile;
            }
            
            // ลองหาในโฟลเดอร์ Documents
            File documentsDir = new File("/storage/emulated/0/Documents/");
            File documentsFile = new File(documentsDir, fileName);
            if (documentsFile.exists()) {
                Log.d(TAG, "พบไฟล์ใน Documents/: " + documentsFile.getAbsolutePath());
                return documentsFile;
            }
            
            Log.e(TAG, "ไม่พบไฟล์ " + fileName + " ในทุก path ที่ลองแล้ว");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการหา path ทางเลือก: " + e.getMessage(), e);
            return null;
        }
    }
} 