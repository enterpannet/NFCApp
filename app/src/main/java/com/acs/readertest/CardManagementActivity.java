package com.acs.readertest;

import android.database.Cursor;
import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.acs.smartcard.Reader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * หน้าจัดการ CRUD ข้อมูลการ์ด NFC
 */
public class CardManagementActivity extends AppCompatActivity implements CardReaderService.CardReaderListener {

    private static final String TAG = "CardManagementActivity";
    private static final String ACTION_USB_PERMISSION = "com.acs.readertest.USB_PERMISSION";
    private static final int REQUEST_STORAGE_PERMISSION = 101;

    // UI elements
    private TextView tvReaderStatus;
    private Button btnConnectReader;
    private Button btnReadCurrentCard;
    private TextView tvCurrentCard;
    private EditText etNewCardId;
    private EditText etNewMediaPath;
    private RadioGroup rgMediaType;
    private RadioButton rbPdf, rbVideo, rbWeb;
    private Button btnAddCard;
    private Button btnBack;
    private Button btnSelectFile;
    private RecyclerView rvCardList;
    private CardAdapter cardAdapter;
    private List<CardEntry> cardEntries;

    // ActivityResultLaunchers สำหรับเลือกไฟล์
    private ActivityResultLauncher<Intent> pdfFileLauncher;
    private ActivityResultLauncher<Intent> videoFileLauncher;

    // USB และ Reader
    private UsbManager mManager;
    private Reader mReader;
    private PendingIntent mPermissionIntent;
    private int mSlotNum = 0;
    private boolean mReaderOpened = false;
    private NfcCardReader nfcCardReader;
    private CardMediaMapping cardMediaMapping;
    private Timer cardPollingTimer;
    private String currentCardId = null;

    // ServiceConnection สำหรับเชื่อมต่อกับ CardReaderService
    private CardReaderService mCardReaderService;
    private boolean mBound = false;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CardReaderService.LocalBinder binder = (CardReaderService.LocalBinder) service;
            mCardReaderService = binder.getService();
            mBound = true;
            mCardReaderService.setListener(CardManagementActivity.this);
            Log.d(TAG, "เชื่อมต่อกับบริการอ่านการ์ดสำเร็จ");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            Log.d(TAG, "ยกเลิกการเชื่อมต่อกับบริการอ่านการ์ด");
        }
    };

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

    // Data class สำหรับเก็บข้อมูลการ์ด
    public static class CardEntry {
        public String cardId;
        public String mediaPath;
        public MediaHelper.MediaType mediaType;
        
        public CardEntry(String cardId, String mediaPath) {
            this.cardId = cardId;
            this.mediaPath = mediaPath;
            this.mediaType = MediaHelper.getMediaType(mediaPath);
        }
    }

    // Adapter สำหรับ RecyclerView
    private class CardAdapter extends RecyclerView.Adapter<CardAdapter.CardViewHolder> {
        private List<CardEntry> cards;

        public CardAdapter(List<CardEntry> cards) {
            this.cards = cards;
        }

        @NonNull
        @Override
        public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_card_entry, parent, false);
            return new CardViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
            CardEntry card = cards.get(position);
            holder.bind(card, position);
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }

        public void updateData(List<CardEntry> newCards) {
            this.cards = newCards;
            notifyDataSetChanged();
        }

        class CardViewHolder extends RecyclerView.ViewHolder {
            TextView tvCardId, tvMediaPath, tvMediaType;
            Button btnEdit, btnDelete, btnTest;
            ImageView ivMediaIcon;

            CardViewHolder(View itemView) {
                super(itemView);
                tvCardId = itemView.findViewById(R.id.tv_card_id);
                tvMediaPath = itemView.findViewById(R.id.tv_media_path);
                tvMediaType = itemView.findViewById(R.id.tv_media_type);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
                btnTest = itemView.findViewById(R.id.btn_test);
                ivMediaIcon = itemView.findViewById(R.id.iv_media_icon);
            }

            void bind(CardEntry card, int position) {
                tvCardId.setText("UID: " + card.cardId);
                tvMediaPath.setText(card.mediaPath);
                
                // แสดงประเภทสื่อ
                String typeText = "";
                String iconText = "";
                switch (card.mediaType) {
                    case PDF:
                        typeText = "PDF Document";
                        iconText = "📄";
                        break;
                    case VIDEO:
                        typeText = "Video File";
                        iconText = "🎬";
                        break;
                    case WEB:
                        typeText = "Website";
                        iconText = "🌐";
                        break;
                    default:
                        typeText = "Unknown";
                        iconText = "📁";
                        break;
                }
                tvMediaType.setText(iconText + " " + typeText);

                // ปุ่มแก้ไข
                btnEdit.setOnClickListener(v -> showEditDialog(card, position));

                // ปุ่มลบ
                btnDelete.setOnClickListener(v -> showDeleteDialog(card, position));

                // ปุ่มทดสอบ
                btnTest.setOnClickListener(v -> testMediaFile(card));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_management);

        initializeUIElements();
        initializeReaderComponents();
        initializeCardMapping();
        registerActivityResultLaunchers();
        setupEventListeners();
        registerUsbReceiver();
        loadCardEntries();

        // เชื่อมต่อกับ service
        Intent intent = new Intent(this, CardReaderService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * ตั้งค่า UI elements
     */
    private void initializeUIElements() {
        tvReaderStatus = findViewById(R.id.tv_reader_status);
        btnConnectReader = findViewById(R.id.btn_connect_reader);
        btnReadCurrentCard = findViewById(R.id.btn_read_current_card);
        tvCurrentCard = findViewById(R.id.tv_current_card);
        etNewCardId = findViewById(R.id.et_new_card_id);
        etNewMediaPath = findViewById(R.id.et_new_media_path);
        btnAddCard = findViewById(R.id.btn_add_card);
        btnBack = findViewById(R.id.btn_back);
        btnSelectFile = findViewById(R.id.btn_select_file);
        rvCardList = findViewById(R.id.rv_card_list);
        rgMediaType = findViewById(R.id.rg_media_type);
        rbPdf = findViewById(R.id.rb_pdf);
        rbVideo = findViewById(R.id.rb_video);
        rbWeb = findViewById(R.id.rb_web);

        // ตั้งค่า RecyclerView
        cardEntries = new ArrayList<>();
        cardAdapter = new CardAdapter(cardEntries);
        rvCardList.setLayoutManager(new LinearLayoutManager(this));
        rvCardList.setAdapter(cardAdapter);
        rvCardList.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
    }

    /**
     * ตั้งค่า components สำหรับ Reader
     */
    private void initializeReaderComponents() {
        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbReader usbReader = UsbReader.getInstance(this);
        mReader = usbReader.getReader();
        nfcCardReader = new NfcCardReader(mReader);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
    }

    /**
     * เริ่มต้น Card Mapping
     */
    private void initializeCardMapping() {
        try {
            cardMediaMapping = new CardMediaMapping();
            boolean mappingLoaded = cardMediaMapping.loadMapping(this);
            if (mappingLoaded) {
                Log.d(TAG, "โหลด mapping สำเร็จ");
            } else {
                Log.w(TAG, "ไม่สามารถโหลด mapping ได้");
                cardMediaMapping = new CardMediaMapping();
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการโหลด mapping: ", e);
            cardMediaMapping = new CardMediaMapping();
        }
    }

    /**
     * ลงทะเบียน ActivityResultLaunchers สำหรับเลือกไฟล์
     */
    private void registerActivityResultLaunchers() {
        // Launcher สำหรับเลือกไฟล์ PDF
        pdfFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri fileUri = result.getData().getData();
                    if (fileUri != null) {
                        String filePath = getFilePathFromUri(fileUri);
                        if (filePath != null) {
                            etNewMediaPath.setText(filePath);
                            logMessage("เลือกไฟล์ PDF: " + filePath);
                        } else {
                            etNewMediaPath.setText(fileUri.toString());
                            logMessage("เลือกไฟล์ PDF (URI): " + fileUri.toString());
                        }
                    }
                }
            }
        );

        // Launcher สำหรับเลือกไฟล์วิดีโอ
        videoFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri fileUri = result.getData().getData();
                    if (fileUri != null) {
                        String filePath = getFilePathFromUri(fileUri);
                        if (filePath != null) {
                            etNewMediaPath.setText(filePath);
                            logMessage("เลือกไฟล์วิดีโอ: " + filePath);
                        } else {
                            etNewMediaPath.setText(fileUri.toString());
                            logMessage("เลือกไฟล์วิดีโอ (URI): " + fileUri.toString());
                        }
                    }
                }
            }
        );
    }

    /**
     * แปลง URI เป็น file path หรือ copy ไฟล์ไปยัง app directory
     */
    private String getFilePathFromUri(Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                // ลองดึง real path ก่อน
                String realPath = getRealPathFromURI(uri);
                if (realPath != null && !realPath.isEmpty()) {
                    Log.d(TAG, "ได้ real path: " + realPath);
                    return realPath;
                }
                
                // ถ้าไม่ได้ real path ให้ copy ไฟล์ไปยัง app directory
                String copiedPath = copyFileToAppDirectory(uri);
                if (copiedPath != null) {
                    Log.d(TAG, "Copy ไฟล์สำเร็จ: " + copiedPath);
                    return copiedPath;
                }
                
                // สุดท้ายถ้าไม่สามารถ copy ได้ ให้ใช้ URI โดยตรง
                Log.w(TAG, "ใช้ content URI โดยตรง: " + uri.toString());
                return uri.toString();
                
            } else if ("file".equals(uri.getScheme())) {
                // สำหรับ file URI ให้แปลงเป็น path
                String path = uri.getPath();
                Log.d(TAG, "ได้ file path: " + path);
                return path;
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการแปลง URI เป็น path", e);
        }
        return uri.toString();
    }

    /**
     * ดึง real path จาก content URI
     */
    private String getRealPathFromURI(Uri uri) {
        String realPath = null;
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                // สำหรับ Documents Provider
                String docId = DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                if (contentUri != null && split.length > 1) {
                    String selection = "_id=?";
                    String[] selectionArgs = new String[]{split[1]};
                    realPath = getDataColumn(contentUri, selection, selectionArgs);
                }
            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                // สำหรับ content URI ทั่วไป
                realPath = getDataColumn(uri, null, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการดึง real path", e);
        }
        return realPath;
    }

    /**
     * ดึงข้อมูลจาก MediaStore
     */
    private String getDataColumn(Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        String column = "_data";
        String[] projection = {column};

        try {
            cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(columnIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการดึงข้อมูลจาก MediaStore", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Copy ไฟล์จาก content URI ไปยัง app directory
     */
    private String copyFileToAppDirectory(Uri uri) {
        try {
            // สร้างชื่อไฟล์ใหม่
            String fileName = getFileNameFromContentUri(uri);
            if (fileName == null) {
                fileName = "file_" + System.currentTimeMillis();
            }

            // สร้างโฟลเดอร์ใน app directory
            File appDir = new File(getExternalFilesDir(null), "selected_files");
            if (!appDir.exists()) {
                appDir.mkdirs();
            }

            File destFile = new File(appDir, fileName);

            // Copy ไฟล์
            try (InputStream inputStream = getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {

                if (inputStream != null) {
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                    outputStream.flush();

                    Log.d(TAG, "Copy ไฟล์สำเร็จ: " + destFile.getAbsolutePath());
                    return destFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการ copy ไฟล์", e);
        }
        return null;
    }

    /**
     * ดึงชื่อไฟล์จาก content URI
     */
    private String getFileNameFromContentUri(Uri uri) {
        String fileName = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                } else {
                    // ลองหา column อื่นๆ
                    String[] possibleColumns = {"_display_name", "title", "name"};
                    for (String column : possibleColumns) {
                        int index = cursor.getColumnIndex(column);
                        if (index >= 0) {
                            fileName = cursor.getString(index);
                            if (fileName != null && !fileName.isEmpty()) {
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการดึงชื่อไฟล์", e);
        }

        // ถ้าไม่ได้ชื่อไฟล์ ให้ใช้ timestamp
        if (fileName == null || fileName.isEmpty()) {
            fileName = "file_" + System.currentTimeMillis();
        }

        return fileName;
    }

    /**
     * กำหนด Event Listeners
     */
    private void setupEventListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnConnectReader.setOnClickListener(v -> {
            if (!mReaderOpened) {
                connectToReader();
            } else {
                new Thread(() -> closeReader()).start();
            }
        });

        btnReadCurrentCard.setOnClickListener(v -> {
            if (mReaderOpened) {
                new Thread(() -> readCardForInput()).start();
            } else {
                showMessage("กรุณาเชื่อมต่อเครื่องอ่านก่อน");
            }
        });

        btnAddCard.setOnClickListener(v -> addNewCard());

        // ปุ่มเลือกไฟล์
        btnSelectFile.setOnClickListener(v -> selectFileBasedOnMediaType());

        // ปุ่มเลือกไฟล์สื่อ (Quick templates)
        Button btnSelectMedia = findViewById(R.id.btn_select_media);
        btnSelectMedia.setOnClickListener(v -> showMediaSelectionDialog());

        // Radio button listeners
        rgMediaType.setOnCheckedChangeListener((group, checkedId) -> {
            updateHintBasedOnMediaType();
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

            if (Build.VERSION.SDK_INT >= 31) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการลงทะเบียน BroadcastReceiver", e);
        }
    }

    /**
     * โหลดรายการการ์ดทั้งหมด
     */
    private void loadCardEntries() {
        cardEntries.clear();
        Map<String, String> mappings = cardMediaMapping.getAllMappings();
        
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            cardEntries.add(new CardEntry(entry.getKey(), entry.getValue()));
        }
        
        cardAdapter.updateData(cardEntries);
        logMessage("โหลดข้อมูลการ์ด " + cardEntries.size() + " รายการ");
        
        // ตั้งค่า hint เริ่มต้น
        updateHintBasedOnMediaType();
    }

    /**
     * เชื่อมต่อกับเครื่องอ่าน
     */
    private void connectToReader() {
        HashMap<String, UsbDevice> deviceList = mManager.getDeviceList();
        if (deviceList.isEmpty()) {
            showMessage("ไม่พบเครื่องอ่านการ์ด");
            return;
        }

        for (UsbDevice device : deviceList.values()) {
            if (isAcsDevice(device)) {
                requestUsbPermission(device);
                return;
            }
        }

        showMessage("ไม่พบเครื่องอ่าน ACR122U ที่รองรับ");
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
            logMessage("มีสิทธิ์เข้าถึง USB แล้ว กำลังเชื่อมต่อ...");
            new Thread(() -> openReader(device)).start();
        }
    }

    /**
     * เปิดการเชื่อมต่อกับเครื่องอ่าน
     */
    private void openReader(UsbDevice device) {
        try {
            mReader.open(device);
            runOnUiThread(() -> {
                mReaderOpened = true;
                String deviceName = device.getProductName() != null ? device.getProductName() : device.getDeviceName();
                updateReaderStatus("เชื่อมต่อแล้ว: " + deviceName);
                btnConnectReader.setText("ยกเลิกการเชื่อมต่อ");
                logMessage("เชื่อมต่อกับเครื่องอ่านสำเร็จ: " + deviceName);
                
                // เริ่มการ polling อัตโนมัติ
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
            // หยุด polling ก่อน
            stopCardPolling();
            
            mReader.close();
            runOnUiThread(() -> {
                mReaderOpened = false;
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
     * อ่านการ์ดเพื่อนำ UID มาใส่ใน input field
     */
    private void readCardForInput() {
        try {
            if (!mReaderOpened) {
                showMessage("กรุณาเชื่อมต่อเครื่องอ่านก่อน");
                return;
            }

            runOnUiThread(() -> tvCurrentCard.setText("กำลังอ่านการ์ด..."));

            String uid = nfcCardReader.readCardUid(mSlotNum);
            if (uid != null) {
                currentCardId = uid;
                runOnUiThread(() -> {
                    tvCurrentCard.setText("UID: " + uid);
                    etNewCardId.setText(uid);
                    logMessage("อ่าน UID สำเร็จ: " + uid);
                    
                    // ตรวจสอบว่าการ์ดนี้มีในระบบแล้วหรือไม่
                    if (cardMediaMapping.hasCard(uid)) {
                        String existingPath = cardMediaMapping.findMediaForCard(uid);
                        etNewMediaPath.setText(existingPath);
                        showMessage("พบการ์ดในระบบแล้ว กรุณาตรวจสอบข้อมูล");
                    } else {
                        etNewMediaPath.setText("");
                        showMessage("การ์ดใหม่ กรุณาใส่ path ของไฟล์สื่อ");
                    }
                });
                return;
            }

            String ndefText = nfcCardReader.readNdefText(mSlotNum);
            if (ndefText != null) {
                currentCardId = ndefText;
                runOnUiThread(() -> {
                    tvCurrentCard.setText("NDEF: " + ndefText);
                    etNewCardId.setText(ndefText);
                    logMessage("อ่าน NDEF Text สำเร็จ: " + ndefText);
                    
                    // ตรวจสอบว่าการ์ดนี้มีในระบบแล้วหรือไม่
                    if (cardMediaMapping.hasCard(ndefText)) {
                        String existingPath = cardMediaMapping.findMediaForCard(ndefText);
                        etNewMediaPath.setText(existingPath);
                        showMessage("พบการ์ดในระบบแล้ว กรุณาตรวจสอบข้อมูล");
                    } else {
                        etNewMediaPath.setText("");
                        showMessage("การ์ดใหม่ กรุณาใส่ path ของไฟล์สื่อ");
                    }
                });
                return;
            }

            runOnUiThread(() -> {
                tvCurrentCard.setText("ไม่พบการ์ดบนเครื่องอ่าน");
                logMessage("ไม่พบการ์ดบนเครื่องอ่าน หรือรูปแบบการ์ดไม่รองรับ");
            });

        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการอ่านการ์ด", e);
            runOnUiThread(() -> {
                tvCurrentCard.setText("เกิดข้อผิดพลาดในการอ่านการ์ด");
                logMessage("เกิดข้อผิดพลาดในการอ่านการ์ด: " + e.getMessage());
            });
        }
    }

    /**
     * เพิ่มการ์ดใหม่
     */
    private void addNewCard() {
        String cardId = etNewCardId.getText().toString().trim();
        String mediaPath = etNewMediaPath.getText().toString().trim();

        if (cardId.isEmpty()) {
            showMessage("กรุณาใส่ UID ของการ์ด");
            return;
        }

        if (mediaPath.isEmpty()) {
            showMessage("กรุณาใส่ path ของไฟล์สื่อ");
            return;
        }

        // ตรวจสอบว่าการ์ดมีอยู่แล้วหรือไม่
        if (cardMediaMapping.findMediaForCard(cardId) != null) {
            new AlertDialog.Builder(this)
                .setTitle("การ์ดมีอยู่แล้ว")
                .setMessage("การ์ด UID: " + cardId + " มีอยู่ในระบบแล้ว\nต้องการอัปเดตข้อมูลหรือไม่?")
                .setPositiveButton("อัปเดต", (dialog, which) -> {
                    updateCard(cardId, mediaPath);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
            return;
        }

        // เพิ่มการ์ดใหม่
        if (cardMediaMapping.addCardMapping(cardId, mediaPath)) {
            etNewCardId.setText("");
            etNewMediaPath.setText("");
            tvCurrentCard.setText("รอการอ่านการ์ด...");
            loadCardEntries();
            logMessage("เพิ่มการ์ดใหม่สำเร็จ: " + cardId + " -> " + mediaPath);
            showMessage("เพิ่มการ์ดใหม่สำเร็จ");
        } else {
            logMessage("เกิดข้อผิดพลาดในการเพิ่มการ์ด");
            showMessage("เกิดข้อผิดพลาดในการเพิ่มการ์ด");
        }
    }

    /**
     * อัปเดตข้อมูลการ์ด
     */
    private void updateCard(String cardId, String mediaPath) {
        if (cardMediaMapping.updateCardMapping(cardId, mediaPath)) {
            loadCardEntries();
            logMessage("อัปเดตการ์ดสำเร็จ: " + cardId + " -> " + mediaPath);
            showMessage("อัปเดตการ์ดสำเร็จ");
        } else {
            logMessage("เกิดข้อผิดพลาดในการอัปเดตการ์ด");
            showMessage("เกิดข้อผิดพลาดในการอัปเดตการ์ด");
        }
    }

    /**
     * เลือกไฟล์ตามประเภทสื่อที่เลือก
     */
    private void selectFileBasedOnMediaType() {
        int checkedId = rgMediaType.getCheckedRadioButtonId();
        
        if (checkedId == R.id.rb_pdf) {
            // เลือกไฟล์ PDF
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                pdfFileLauncher.launch(intent);
                logMessage("เปิดการเลือกไฟล์ PDF");
            } catch (Exception e) {
                showMessage("ไม่สามารถเปิดการเลือกไฟล์ PDF ได้");
                Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดการเลือกไฟล์ PDF", e);
            }
        } else if (checkedId == R.id.rb_video) {
            // เลือกไฟล์วิดีโอ
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                videoFileLauncher.launch(intent);
                logMessage("เปิดการเลือกไฟล์วิดีโอ");
            } catch (Exception e) {
                showMessage("ไม่สามารถเปิดการเลือกไฟล์วิดีโอได้");
                Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดการเลือกไฟล์วิดีโอ", e);
            }
        } else if (checkedId == R.id.rb_web) {
            // สำหรับเว็บไซต์ไม่ต้องเลือกไฟล์
            showMessage("กรุณาใส่ URL ของเว็บไซต์ในช่องด้านล่าง");
            etNewMediaPath.requestFocus();
        } else {
            showMessage("กรุณาเลือกประเภทสื่อก่อน");
        }
    }

    /**
     * อัปเดต hint ของ EditText ตามประเภทสื่อที่เลือก
     */
    private void updateHintBasedOnMediaType() {
        int checkedId = rgMediaType.getCheckedRadioButtonId();
        
        if (checkedId == R.id.rb_pdf) {
            etNewMediaPath.setHint("path ของไฟล์ PDF หรือกดปุ่ม 📁 เพื่อเลือกไฟล์");
        } else if (checkedId == R.id.rb_video) {
            etNewMediaPath.setHint("path ของไฟล์วิดีโอ หรือกดปุ่ม 📁 เพื่อเลือกไฟล์");
        } else if (checkedId == R.id.rb_web) {
            etNewMediaPath.setHint("URL ของเว็บไซต์ (เช่น https://www.example.com)");
        }
    }

    /**
     * แสดง dialog สำหรับแก้ไขข้อมูลการ์ด
     */
    private void showEditDialog(CardEntry card, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_card, null);
        
        EditText etCardId = view.findViewById(R.id.et_edit_card_id);
        EditText etMediaPath = view.findViewById(R.id.et_edit_media_path);
        RadioGroup rgEditMediaType = view.findViewById(R.id.rg_edit_media_type);
        RadioButton rbEditPdf = view.findViewById(R.id.rb_edit_pdf);
        RadioButton rbEditVideo = view.findViewById(R.id.rb_edit_video);
        RadioButton rbEditWeb = view.findViewById(R.id.rb_edit_web);
        Button btnEditSelectFile = view.findViewById(R.id.btn_edit_select_file);
        
        etCardId.setText(card.cardId);
        etMediaPath.setText(card.mediaPath);
        
        // ตั้งค่า radio button ตามประเภทสื่อปัจจุบัน
        switch (card.mediaType) {
            case PDF:
                rbEditPdf.setChecked(true);
                break;
            case VIDEO:
                rbEditVideo.setChecked(true);
                break;
            case WEB:
                rbEditWeb.setChecked(true);
                break;
            default:
                rbEditPdf.setChecked(true); // default เป็น PDF
                break;
        }
        
        // อัปเดต hint ตาม radio button ที่เลือก
        updateEditDialogHint(rgEditMediaType, etMediaPath);
        
        // Listener สำหรับ radio button
        rgEditMediaType.setOnCheckedChangeListener((group, checkedId) -> {
            updateEditDialogHint(rgEditMediaType, etMediaPath);
        });
        
        // Listener สำหรับปุ่มเลือกไฟล์
        btnEditSelectFile.setOnClickListener(v -> {
            selectFileForEditDialog(rgEditMediaType, etMediaPath);
        });
        
        AlertDialog dialog = builder.setView(view)
            .setTitle("แก้ไขข้อมูลการ์ด")
            .setPositiveButton("บันทึก", null) // ตั้งเป็น null ก่อนเพื่อจัดการ manually
            .setNegativeButton("ยกเลิก", null)
            .create();
            
        dialog.setOnShowListener(dialogInterface -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setOnClickListener(view1 -> {
                String newCardId = etCardId.getText().toString().trim();
                String newMediaPath = etMediaPath.getText().toString().trim();
                
                if (newCardId.isEmpty() || newMediaPath.isEmpty()) {
                    showMessage("กรุณากรอกข้อมูลให้ครบถ้วน");
                    return;
                }
                
                // ลบข้อมูลเก่า
                cardMediaMapping.removeCardMapping(card.cardId);
                
                // เพิ่มข้อมูลใหม่
                if (cardMediaMapping.addCardMapping(newCardId, newMediaPath)) {
                    loadCardEntries();
                    logMessage("แก้ไขการ์ดสำเร็จ: " + newCardId + " -> " + newMediaPath);
                    showMessage("แก้ไขการ์ดสำเร็จ");
                    dialog.dismiss();
                } else {
                    // หากเพิ่มไม่สำเร็จ ให้เพิ่มข้อมูลเก่าคืน
                    cardMediaMapping.addCardMapping(card.cardId, card.mediaPath);
                    showMessage("เกิดข้อผิดพลาดในการแก้ไขการ์ด");
                }
            });
        });
        
        dialog.show();
    }

    /**
     * อัปเดต hint สำหรับ edit dialog
     */
    private void updateEditDialogHint(RadioGroup rgEditMediaType, EditText etMediaPath) {
        int checkedId = rgEditMediaType.getCheckedRadioButtonId();
        
        if (checkedId == R.id.rb_edit_pdf) {
            etMediaPath.setHint("path ของไฟล์ PDF หรือกดปุ่ม 📁 เพื่อเลือกไฟล์");
        } else if (checkedId == R.id.rb_edit_video) {
            etMediaPath.setHint("path ของไฟล์วิดีโอ หรือกดปุ่ม 📁 เพื่อเลือกไฟล์");
        } else if (checkedId == R.id.rb_edit_web) {
            etMediaPath.setHint("URL ของเว็บไซต์ (เช่น https://www.example.com)");
        }
    }

    /**
     * เลือกไฟล์สำหรับ edit dialog
     */
    private void selectFileForEditDialog(RadioGroup rgEditMediaType, EditText etMediaPath) {
        int checkedId = rgEditMediaType.getCheckedRadioButtonId();
        
        if (checkedId == R.id.rb_edit_pdf) {
            // เลือกไฟล์ PDF
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            
            ActivityResultLauncher<Intent> editPdfLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri fileUri = result.getData().getData();
                        if (fileUri != null) {
                            String filePath = getFilePathFromUri(fileUri);
                            if (filePath != null) {
                                etMediaPath.setText(filePath);
                                logMessage("เลือกไฟล์ PDF สำหรับแก้ไข: " + filePath);
                            } else {
                                etMediaPath.setText(fileUri.toString());
                                logMessage("เลือกไฟล์ PDF สำหรับแก้ไข (URI): " + fileUri.toString());
                            }
                        }
                    }
                }
            );
            
            try {
                editPdfLauncher.launch(intent);
                logMessage("เปิดการเลือกไฟล์ PDF สำหรับแก้ไข");
            } catch (Exception e) {
                showMessage("ไม่สามารถเปิดการเลือกไฟล์ PDF ได้");
                Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดการเลือกไฟล์ PDF สำหรับแก้ไข", e);
            }
            
        } else if (checkedId == R.id.rb_edit_video) {
            // เลือกไฟล์วิดีโอ
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            
            ActivityResultLauncher<Intent> editVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri fileUri = result.getData().getData();
                        if (fileUri != null) {
                            String filePath = getFilePathFromUri(fileUri);
                            if (filePath != null) {
                                etMediaPath.setText(filePath);
                                logMessage("เลือกไฟล์วิดีโอสำหรับแก้ไข: " + filePath);
                            } else {
                                etMediaPath.setText(fileUri.toString());
                                logMessage("เลือกไฟล์วิดีโอสำหรับแก้ไข (URI): " + fileUri.toString());
                            }
                        }
                    }
                }
            );
            
            try {
                editVideoLauncher.launch(intent);
                logMessage("เปิดการเลือกไฟล์วิดีโอสำหรับแก้ไข");
            } catch (Exception e) {
                showMessage("ไม่สามารถเปิดการเลือกไฟล์วิดีโอได้");
                Log.e(TAG, "เกิดข้อผิดพลาดในการเปิดการเลือกไฟล์วิดีโอสำหรับแก้ไข", e);
            }
            
        } else if (checkedId == R.id.rb_edit_web) {
            // สำหรับเว็บไซต์ไม่ต้องเลือกไฟล์
            showMessage("กรุณาใส่ URL ของเว็บไซต์ในช่องด้านล่าง");
            etMediaPath.requestFocus();
        } else {
            showMessage("กรุณาเลือกประเภทสื่อก่อน");
        }
    }

    /**
     * แสดง dialog สำหรับเลือกประเภทสื่อ
     */
    private void showMediaSelectionDialog() {
        String[] options = {"📄 ไฟล์ PDF", "🎬 ไฟล์วิดีโอ", "🌐 เว็บไซต์", "📝 ใส่ path เอง"};
        
        new AlertDialog.Builder(this)
            .setTitle("เลือกประเภทสื่อ")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // PDF
                        etNewMediaPath.setText("/storage/emulated/0/Download/pdf/");
                        etNewMediaPath.setSelection(etNewMediaPath.getText().length());
                        break;
                    case 1: // Video
                        etNewMediaPath.setText("/storage/emulated/0/Download/videos/");
                        etNewMediaPath.setSelection(etNewMediaPath.getText().length());
                        break;
                    case 2: // Website
                        etNewMediaPath.setText("https://");
                        etNewMediaPath.setSelection(etNewMediaPath.getText().length());
                        break;
                    case 3: // Custom path
                        etNewMediaPath.setText("");
                        etNewMediaPath.requestFocus();
                        break;
                }
            })
            .show();
    }

    /**
     * อัปเดตสถานะของเครื่องอ่าน
     */
    private void updateReaderStatus(String status) {
        tvReaderStatus.setText(status);
    }

    /**
     * แสดงข้อความ log
     */
    private void logMessage(String message) {
        Log.d(TAG, message);
        runOnUiThread(() -> {
            // อาจจะเพิ่ม TextView สำหรับแสดง log ได้ตามต้องการ
        });
    }

    /**
     * แสดงข้อความ toast
     */
    private void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // Implement CardReaderListener methods
    @Override
    public void onReaderStatusChanged(boolean connected, String readerName) {
        runOnUiThread(() -> {
            updateReaderStatus(connected ? "เชื่อมต่อแล้ว: " + readerName : "ไม่ได้เชื่อมต่อ");
            logMessage("สถานะเครื่องอ่านเปลี่ยนเป็น: " + (connected ? "เชื่อมต่อแล้ว" : "ไม่ได้เชื่อมต่อ"));
        });
    }

    @Override
    public void onCardDetected(String cardId) {
        runOnUiThread(() -> {
            logMessage("ตรวจพบการ์ด: " + cardId);
        });
    }

    @Override
    public void onPdfOpened(String cardId, String pdfPath) {
        runOnUiThread(() -> {
            logMessage("เปิดไฟล์ PDF: " + pdfPath);
        });
    }

    /**
     * เริ่มการ polling อัตโนมัติเพื่อตรวจสอบการ์ด
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
                    // อ่านการ์ดแบบไม่รบกวน UI
                    try {
                        String uid = nfcCardReader.readCardUid(mSlotNum);
                        if (uid != null && !uid.equals(currentCardId)) {
                            currentCardId = uid;
                            runOnUiThread(() -> {
                                tvCurrentCard.setText("UID: " + uid + " (ตรวจพบอัตโนมัติ)");
                                // ไม่เติมใน input field อัตโนมัติเพื่อไม่รบกวนผู้ใช้
                                logMessage("ตรวจพบการ์ดอัตโนมัติ: " + uid);
                            });
                        }
                    } catch (Exception e) {
                        // ไม่แสดงข้อผิดพลาดจาก polling เพื่อไม่รบกวน
                        Log.d(TAG, "Polling error (normal): " + e.getMessage());
                    }
                }
            }
        }, 2000, 3000); // เริ่มหลัง 2 วินาที ทำซ้ำทุก 3 วินาที
        
        logMessage("เริ่มการตรวจสอบการ์ดอัตโนมัติ");
    }

    /**
     * หยุดการ polling อัตโนมัติ
     */
    private void stopCardPolling() {
        if (cardPollingTimer != null) {
            cardPollingTimer.cancel();
            cardPollingTimer = null;
            logMessage("หยุดการตรวจสอบการ์ดอัตโนมัติ");
        }
    }

    /**
     * แสดง dialog สำหรับยืนยันการลบ
     */
    private void showDeleteDialog(CardEntry card, int position) {
        new AlertDialog.Builder(this)
            .setTitle("ยืนยันการลบ")
            .setMessage("ต้องการลบการ์ด UID: " + card.cardId + " หรือไม่?")
            .setPositiveButton("ลบ", (dialog, which) -> {
                if (cardMediaMapping.removeCardMapping(card.cardId)) {
                    loadCardEntries();
                    logMessage("ลบการ์ดสำเร็จ: " + card.cardId);
                    showMessage("ลบการ์ดสำเร็จ");
                } else {
                    logMessage("เกิดข้อผิดพลาดในการลบการ์ด");
                    showMessage("เกิดข้อผิดพลาดในการลบการ์ด");
                }
            })
            .setNegativeButton("ยกเลิก", null)
            .show();
    }

    /**
     * ทดสอบเปิดไฟล์สื่อ
     */
    private void testMediaFile(CardEntry card) {
        logMessage("ทดสอบเปิดสื่อ: " + card.mediaPath);
        
        try {
            // สร้าง MediaInfo เพื่อตรวจสอบประเภทสื่อ
            MediaHelper.MediaInfo mediaInfo = MediaHelper.createMediaInfo(card.mediaPath);
            logMessage("ประเภทสื่อ: " + mediaInfo.type + ", ชื่อแสดง: " + mediaInfo.displayName);
            
            // ตรวจสอบการมีอยู่ของไฟล์สำหรับไฟล์ local
            if (!card.mediaPath.startsWith("http://") && !card.mediaPath.startsWith("https://")) {
                if (mediaInfo.type == MediaHelper.MediaType.PDF || mediaInfo.type == MediaHelper.MediaType.VIDEO) {
                    File mediaFile = new File(card.mediaPath);
                    if (!mediaFile.exists()) {
                        logMessage("ไฟล์ไม่มีอยู่ในตำแหน่งที่ระบุ: " + card.mediaPath);
                        showMessage("ไฟล์ไม่มีอยู่ในตำแหน่งที่ระบุ");
                        return;
                    }
                }
            }
            
            // ลองเปิดสื่อ
            boolean success = false;
            
            switch (mediaInfo.type) {
                case PDF:
                case VIDEO:
                case WEB:
                    success = MediaHelper.openMedia(this, card.mediaPath);
                    break;
                default:
                    // สำหรับไฟล์ประเภทอื่นๆ ลองเปิดแบบ generic
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        Uri uri;
                        
                        if (card.mediaPath.startsWith("http://") || card.mediaPath.startsWith("https://")) {
                            uri = Uri.parse(card.mediaPath);
                        } else {
                            File file = new File(card.mediaPath);
                            uri = androidx.core.content.FileProvider.getUriForFile(this, 
                                getPackageName() + ".fileprovider", file);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                        
                        intent.setData(uri);
                        
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                            success = true;
                        } else {
                            logMessage("ไม่พบแอพที่รองรับไฟล์ประเภทนี้");
                            showMessage("ไม่พบแอพที่รองรับไฟล์ประเภทนี้");
                        }
                    } catch (Exception e) {
                        logMessage("เกิดข้อผิดพลาดในการเปิดไฟล์: " + e.getMessage());
                        showMessage("เกิดข้อผิดพลาดในการเปิดไฟล์");
                    }
                    break;
            }
            
            if (success) {
                logMessage("เปิดสื่อสำเร็จ");
                showMessage("เปิดสื่อสำเร็จ");
            } else {
                logMessage("ไม่สามารถเปิดสื่อได้");
                
                // แสดงข้อมูลเพิ่มเติมสำหรับการ debug
                String debugInfo = "ข้อมูลเพิ่มเติม:\n";
                debugInfo += "- ประเภทสื่อ: " + mediaInfo.type + "\n";
                debugInfo += "- Path: " + card.mediaPath + "\n";
                
                if (!card.mediaPath.startsWith("http")) {
                    File file = new File(card.mediaPath);
                    debugInfo += "- ไฟล์มีอยู่: " + file.exists() + "\n";
                    debugInfo += "- สามารถอ่านได้: " + file.canRead() + "\n";
                }
                
                logMessage(debugInfo);
                showMessage("ไม่สามารถเปิดสื่อได้ - ดูรายละเอียดใน log");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดในการทดสอบสื่อ", e);
            logMessage("เกิดข้อผิดพลาดในการทดสอบสื่อ: " + e.getMessage());
            showMessage("เกิดข้อผิดพลาดในการทดสอบสื่อ");
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (mReaderOpened) {
                new Thread(() -> closeReader()).start();
            }
            
            if (mBound) {
                unbindService(mConnection);
                mBound = false;
            }
            
            try {
                unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "เกิดข้อผิดพลาดในการยกเลิกการลงทะเบียน receiver: ", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดใน onDestroy: ", e);
        } finally {
            super.onDestroy();
        }
    }
} 