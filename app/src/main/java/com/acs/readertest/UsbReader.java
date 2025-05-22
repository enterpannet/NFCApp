/*
 * Copyright (C) 2023 Advanced Card Systems Ltd. All rights reserved.
 *
 * This software is the confidential and proprietary information of Advanced
 * Card Systems Ltd. ("Confidential Information").  You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with ACS.
 */

package com.acs.readertest;

import android.content.Context;
import android.hardware.usb.UsbManager;

import com.acs.smartcard.Reader;

/**
 * The {@code UsbReader} class is a singleton that provides the USB manager and the reader.
 *
 * @author Godfrey Chung
 * @version 1.0, 7 Dec 2023
 * @since 1.2
 */
public class UsbReader {

    private static volatile UsbReader mInstance = null;
    private final UsbManager mManager;
    private final Reader mReader;

    /**
     * Creates an instance of {@code UsbReader}.
     */
    private UsbReader(Context context) {

        mManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        mReader = new Reader(mManager);
    }

    /**
     * Returns the instance of {@code UsbReader}.
     *
     * @param context the context
     * @return the instance
     */
    public static UsbReader getInstance(Context context) {

        if (mInstance == null) {
            synchronized (UsbReader.class) {
                if (mInstance == null) {
                    mInstance = new UsbReader(context);
                }
            }
        }

        return mInstance;
    }

    /**
     * Gets the USB manager.
     *
     * @return the USB manager
     */
    public UsbManager getManager() {
        return mManager;
    }

    /**
     * Gets the reader.
     *
     * @return the reader
     */
    public Reader getReader() {
        return mReader;
    }
}
