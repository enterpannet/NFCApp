/*
 * Copyright (C) 2023 Advanced Card Systems Ltd. All rights reserved.
 *
 * This software is the confidential and proprietary information of Advanced
 * Card Systems Ltd. ("Confidential Information").  You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with ACS.
 */

package com.acs.readertest;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.fragment.app.DialogFragment;

import com.acs.smartcard.ReadKeyOption;

/**
 * The {@code ReadKeyDialogFragment} class shows the read key option.
 *
 * @author Godfrey Chung
 * @version 1.0, 20 Dec 2023
 * @since 1.3
 */
public class ReadKeyDialogFragment extends AppCompatDialogFragment {

    /**
     * Interface definition for a callback to be invoked when positive or negative button is
     * clicked.
     */
    public interface ReadKeyDialogListener {

        /**
         * Called when the positive button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onReadKeyDialogPositiveClick(DialogFragment dialog);

        /**
         * Called when the negative button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onReadKeyDialogNegativeClick(DialogFragment dialog);
    }

    private ReadKeyDialogListener mListener;
    private EditText mTimeoutEditText;
    private EditText mPinMaxExtraDigitEditText;
    private EditText mKeyReturnConditionEditText;
    private EditText mEchoLcdStartPositionEditText;
    private EditText mEchoLcdModeEditText;
    private final ReadKeyOption mReadKeyOption = new ReadKeyOption();

    @Override
    public void onAttach(@NonNull Context context) {

        super.onAttach(context);
        try {
            mListener = (ReadKeyDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement ReadKeyDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.read_key_dialog, null);

        mTimeoutEditText = view.findViewById(R.id.read_key_dialog_edit_text_timeout);
        mPinMaxExtraDigitEditText = view.findViewById(
                R.id.read_key_dialog_edit_text_pin_max_extra_digit);
        mKeyReturnConditionEditText = view.findViewById(
                R.id.read_key_dialog_edit_text_key_return_condition);
        mEchoLcdStartPositionEditText = view.findViewById(
                R.id.read_key_dialog_edit_text_echo_lcd_start_position);
        mEchoLcdModeEditText = view.findViewById(R.id.read_key_dialog_edit_text_echo_lcd_mode);

        if (savedInstanceState == null) {

            mTimeoutEditText.setText(Hex.toHexString(mReadKeyOption.getTimeOut()));
            mPinMaxExtraDigitEditText.setText(
                    Hex.toHexString(mReadKeyOption.getPinMaxExtraDigit()));
            mKeyReturnConditionEditText.setText(
                    Hex.toHexString(mReadKeyOption.getKeyReturnCondition()));
            mEchoLcdStartPositionEditText.setText(
                    Hex.toHexString(mReadKeyOption.getEchoLcdStartPosition()));
            mEchoLcdModeEditText.setText(Hex.toHexString(mReadKeyOption.getEchoLcdMode()));
        }

        builder.setTitle(R.string.read_key)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {

                    updateData();
                    mListener.onReadKeyDialogPositiveClick(ReadKeyDialogFragment.this);
                })
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> mListener.onReadKeyDialogNegativeClick(
                                ReadKeyDialogFragment.this));

        return builder.create();
    }

    /**
     * Gets the read key option.
     *
     * @return the read key option
     */
    public ReadKeyOption getReadKeyOption() {
        return mReadKeyOption;
    }

    /**
     * Updates the data.
     */
    private void updateData() {

        byte[] buffer;

        buffer = Hex.toByteArray(mTimeoutEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mReadKeyOption.setTimeOut(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinMaxExtraDigitEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 1)) {
            mReadKeyOption.setPinMaxExtraDigit((buffer[0] & 0xFF) << 8 | (buffer[1] & 0xFF));
        }

        buffer = Hex.toByteArray(mKeyReturnConditionEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mReadKeyOption.setKeyReturnCondition(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mEchoLcdStartPositionEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mReadKeyOption.setEchoLcdStartPosition(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mEchoLcdModeEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mReadKeyOption.setEchoLcdMode(buffer[0] & 0xFF);
        }
    }
}
