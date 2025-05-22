package com.acs.readertest;

import android.util.Log;

import com.acs.smartcard.Reader;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * คลาสสำหรับอ่านข้อมูล UID และ NDEF จากการ์ด NFC ผ่าน ACR122U
 */
public class NfcCardReader {
    private static final String TAG = "NfcCardReader";
    
    // คำสั่ง APDU สำหรับอ่าน UID
    private static final byte[] GET_UID_COMMAND = {
            (byte) 0xFF, // CLA
            (byte) 0xCA, // INS
            (byte) 0x00, // P1
            (byte) 0x00, // P2
            (byte) 0x00  // Le
    };
    
    // คำสั่ง APDU สำหรับอ่าน NDEF
    private static final byte[] SELECT_NDEF_APP_COMMAND = {
            (byte) 0x00, // CLA
            (byte) 0xA4, // INS
            (byte) 0x04, // P1
            (byte) 0x00, // P2
            (byte) 0x07, // Lc
            // AID ของ NDEF: D2760000850101
            (byte) 0xD2, (byte) 0x76, (byte) 0x00, (byte) 0x00, (byte) 0x85, (byte) 0x01, (byte) 0x01,
            (byte) 0x00  // Le
    };
    
    private static final byte[] SELECT_NDEF_CC_FILE = {
            (byte) 0x00, // CLA
            (byte) 0xA4, // INS
            (byte) 0x00, // P1
            (byte) 0x0C, // P2
            (byte) 0x02, // Lc
            (byte) 0xE1, (byte) 0x03, // File ID ของ Capability Container
            (byte) 0x00  // Le
    };
    
    private static final byte[] SELECT_NDEF_FILE = {
            (byte) 0x00, // CLA
            (byte) 0xA4, // INS
            (byte) 0x00, // P1
            (byte) 0x0C, // P2
            (byte) 0x02, // Lc
            (byte) 0xE1, (byte) 0x04, // File ID ของ NDEF File
            (byte) 0x00  // Le
    };
    
    private static final byte[] READ_BINARY = {
            (byte) 0x00, // CLA
            (byte) 0xB0, // INS
            (byte) 0x00, // P1
            (byte) 0x00, // P2
            (byte) 0x0F  // Le: จำนวนไบต์ที่ต้องการอ่าน
    };
    
    private final Reader reader;
    
    /**
     * คอนสตรัคเตอร์
     * 
     * @param reader Reader object จาก ACS SDK
     */
    public NfcCardReader(Reader reader) {
        this.reader = reader;
    }
    
    /**
     * อ่าน UID จากการ์ด
     * 
     * @param slotNum หมายเลข slot ของเครื่องอ่าน
     * @return UID ในรูปแบบ Hex String หรือ null ถ้าอ่านไม่สำเร็จ
     */
    public String readCardUid(int slotNum) {
        try {
            if (!isCardPresent(slotNum)) {
                Log.d(TAG, "ไม่พบการ์ดใน slot " + slotNum);
                return null;
            }
            
            // รีเซ็ตการ์ด
            byte[] atr = reader.power(slotNum, Reader.CARD_WARM_RESET);
            if (atr == null || atr.length == 0) {
                Log.e(TAG, "ไม่สามารถเปิดใช้งานการ์ดได้");
                return null;
            }
            
            // ตั้งค่า protocol
            int protocol = reader.setProtocol(slotNum, Reader.PROTOCOL_T0 | Reader.PROTOCOL_T1);
            if (protocol < 0) {
                Log.e(TAG, "ไม่สามารถตั้งค่า protocol ได้");
                return null;
            }
            
            // ส่งคำสั่ง APDU เพื่ออ่าน UID
            byte[] response = new byte[300];
            int responseLength = reader.transmit(slotNum, GET_UID_COMMAND, GET_UID_COMMAND.length, response, response.length);
            
            // ตรวจสอบผลลัพธ์
            if (responseLength < 2) {
                Log.e(TAG, "ผลลัพธ์จากคำสั่งอ่าน UID ไม่ถูกต้อง");
                return null;
            }
            
            // ตรวจสอบ Status Word (2 ไบต์สุดท้าย)
            int sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.e(TAG, "Status word ของคำสั่งอ่าน UID ไม่ถูกต้อง: " + Integer.toHexString(sw));
                return null;
            }
            
            // แปลง UID เป็น Hex String
            byte[] uid = Arrays.copyOfRange(response, 0, responseLength - 2);
            return bytesToHex(uid);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่าน UID", e);
            return null;
        }
    }
    
    /**
     * อ่านข้อมูล NDEF จากการ์ด
     * 
     * @param slotNum หมายเลข slot ของเครื่องอ่าน
     * @return ข้อความจาก NDEF Record หรือ null ถ้าอ่านไม่สำเร็จ
     */
    public String readNdefText(int slotNum) {
        try {
            if (!isCardPresent(slotNum)) {
                Log.d(TAG, "ไม่พบการ์ดใน slot " + slotNum);
                return null;
            }
            
            // รีเซ็ตการ์ด
            byte[] atr = reader.power(slotNum, Reader.CARD_WARM_RESET);
            if (atr == null || atr.length == 0) {
                Log.e(TAG, "ไม่สามารถเปิดใช้งานการ์ดได้");
                return null;
            }
            
            // ตั้งค่า protocol
            int protocol = reader.setProtocol(slotNum, Reader.PROTOCOL_T0 | Reader.PROTOCOL_T1);
            if (protocol < 0) {
                Log.e(TAG, "ไม่สามารถตั้งค่า protocol ได้");
                return null;
            }
            
            // 1. เลือก NDEF Application
            byte[] response = new byte[300];
            int responseLength = reader.transmit(slotNum, SELECT_NDEF_APP_COMMAND, SELECT_NDEF_APP_COMMAND.length, response, response.length);
            
            // ตรวจสอบ Status Word
            int sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.d(TAG, "ไม่ใช่ NDEF Application, ลองอ่าน UID แทน");
                return null;
            }
            
            // 2. เลือก Capability Container
            responseLength = reader.transmit(slotNum, SELECT_NDEF_CC_FILE, SELECT_NDEF_CC_FILE.length, response, response.length);
            sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.e(TAG, "ไม่สามารถเลือก Capability Container ได้: " + Integer.toHexString(sw));
                return null;
            }
            
            // 3. เลือก NDEF File
            responseLength = reader.transmit(slotNum, SELECT_NDEF_FILE, SELECT_NDEF_FILE.length, response, response.length);
            sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.e(TAG, "ไม่สามารถเลือก NDEF File ได้: " + Integer.toHexString(sw));
                return null;
            }
            
            // 4. อ่านความยาวของ NDEF Message
            responseLength = reader.transmit(slotNum, READ_BINARY, READ_BINARY.length, response, response.length);
            sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.e(TAG, "ไม่สามารถอ่าน NDEF ได้: " + Integer.toHexString(sw));
                return null;
            }
            
            // ข้อมูลใน response: [0, 1] = ความยาวของ NDEF Message
            int ndefLength = ((response[0] & 0xff) << 8) | (response[1] & 0xff);
            Log.d(TAG, "ความยาวของ NDEF: " + ndefLength + " ไบต์");
            
            if (ndefLength == 0) {
                Log.d(TAG, "ไม่มีข้อมูล NDEF");
                return null;
            }
            
            // 5. อ่านข้อมูล NDEF
            byte[] readRecordCommand = {
                    (byte) 0x00, // CLA
                    (byte) 0xB0, // INS
                    (byte) 0x00, // P1
                    (byte) 0x02, // P2: เริ่มอ่านจาก offset 2
                    (byte) (ndefLength & 0xFF)  // Le: จำนวนไบต์ที่ต้องการอ่าน
            };
            
            responseLength = reader.transmit(slotNum, readRecordCommand, readRecordCommand.length, response, response.length);
            sw = ((response[responseLength - 2] & 0xff) << 8) | (response[responseLength - 1] & 0xff);
            if (sw != 0x9000) {
                Log.e(TAG, "ไม่สามารถอ่านข้อมูล NDEF ได้: " + Integer.toHexString(sw));
                return null;
            }
            
            // แปลง NDEF เป็นข้อความ
            byte[] ndefData = Arrays.copyOfRange(response, 0, responseLength - 2);
            
            // แปลง NDEF Data เป็นข้อความ (ตัดเฉพาะส่วนที่เป็นข้อความ)
            // นี่เป็นการแปลงแบบง่ายๆ สำหรับ NDEF Text Record
            String text = extractTextFromNdef(ndefData);
            if (text != null && !text.isEmpty()) {
                return text;
            }
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่าน NDEF", e);
            return null;
        }
    }
    
    /**
     * แปลงข้อมูล NDEF เป็นข้อความ (แบบง่าย)
     */
    private String extractTextFromNdef(byte[] ndefData) {
        try {
            // Skip NDEF header (TNF + Type Length + Payload Length + ID Length + Type)
            if (ndefData.length < 7) {
                return null;
            }
            
            // ตรวจสอบว่าเป็น Text Record หรือไม่ (Type = "T")
            int offset = 3; // Skip TNF and length fields
            int typeLength = ndefData[offset] & 0xFF;
            offset++; // Skip Type Length
            int payloadLength = ndefData[offset] & 0xFF;
            offset++; // Skip Payload Length
            int idLength = ndefData[offset] & 0xFF;
            offset++; // Skip ID Length
            
            // ตรวจสอบ Type
            if (typeLength != 1 || ndefData[offset] != 'T') {
                Log.d(TAG, "ไม่ใช่ Text Record");
                return null;
            }
            
            offset += typeLength; // Skip Type
            offset += idLength;   // Skip ID
            
            // Skip Text Record status byte (encoding & language code length)
            int languageCodeLength = ndefData[offset] & 0x3F; // 6 bits for length
            offset++;
            offset += languageCodeLength; // Skip language code
            
            // อ่านข้อความ
            int textLength = payloadLength - 1 - languageCodeLength;
            byte[] textBytes = Arrays.copyOfRange(ndefData, offset, offset + textLength);
            
            return new String(textBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการแปลงข้อมูล NDEF เป็นข้อความ", e);
            return null;
        }
    }
    
    /**
     * ตรวจสอบว่ามีการ์ดใน slot หรือไม่
     */
    private boolean isCardPresent(int slotNum) {
        try {
            int state = reader.getState(slotNum);
            return (state == Reader.CARD_PRESENT) ||
                   (state == Reader.CARD_POWERED) ||
                   (state == Reader.CARD_NEGOTIABLE) ||
                   (state == Reader.CARD_SPECIFIC);
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการตรวจสอบการ์ด", e);
            return false;
        }
    }
    
    /**
     * แปลงอาร์เรย์ไบต์เป็น Hex String
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
} 