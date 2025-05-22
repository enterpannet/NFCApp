/*
 * Copyright (C) 2017-2023 Advanced Card Systems Ltd. All rights reserved.
 *
 * This software is the confidential and proprietary information of Advanced
 * Card Systems Ltd. ("Confidential Information").  You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with ACS.
 */

package com.acs.readertest;

/**
 * The {@code Hex} class provides the conversion routines between the HEX string and the byte array.
 *
 * @author Godfrey Chung
 * @version 1.1, 12 Dec 2023
 * @since 1.3
 */
public class Hex {

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /**
     * Converts the integer to HEX string.
     *
     * @param i the integer
     * @return the HEX string
     */
    public static String toHexString(int i) {

        String hexString = Integer.toHexString(i);
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }

        return hexString.toUpperCase();
    }

    /**
     * Converts the byte array to HEX string.
     *
     * @param buffer the buffer
     * @return the HEX string
     */
    public static String toHexString(byte[] buffer) {

        /* Check the parameter. */
        if (buffer == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(3 * buffer.length);

        /* For each byte, convert it to HEX digit. */
        for (int i = 0; i < buffer.length; i++) {

            int tmp = buffer[i] & 0xFF;

            if (i != 0) {
                builder.append(" ");
            }

            builder.append(HEX_DIGITS[tmp >>> 4]);
            builder.append(HEX_DIGITS[tmp & 0x0F]);
        }

        return builder.toString();
    }

    /**
     * Converts the HEX string to byte array.
     *
     * @param hexString the HEX string
     * @return the byte array
     */
    public static byte[] toByteArray(String hexString) {

        byte[] byteArray = null;

        if (hexString != null) {

            /* Count the number of hex characters. */
            int count = 0;
            for (int i = 0; i < hexString.length(); i++) {

                char c = hexString.charAt(i);
                if ((c >= '0') && (c <= '9') || (c >= 'A') && (c <= 'F')
                        || (c >= 'a') && (c <= 'f')) {
                    count++;
                }
            }

            /* Allocate the byte array. */
            byteArray = new byte[(count + 1) / 2];

            int length = 0;
            boolean first = true;

            /* For each HEX character, convert it to byte. */
            for (int i = 0; i < hexString.length(); i++) {

                char c = hexString.charAt(i);
                int value;

                if ((c >= '0') && (c <= '9')) {
                    value = c - '0';
                } else if ((c >= 'A') && (c <= 'F')) {
                    value = c - 'A' + 10;
                } else if ((c >= 'a') && (c <= 'f')) {
                    value = c - 'a' + 10;
                } else {
                    value = -1;
                }

                if (value >= 0) {

                    if (first) {

                        byteArray[length] = (byte) (value << 4);

                    } else {

                        byteArray[length] |= value;
                        length++;
                    }

                    first = !first;
                }
            }
        }

        return byteArray;
    }
}
