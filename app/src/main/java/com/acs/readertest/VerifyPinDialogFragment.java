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

import com.acs.smartcard.PinVerify;

/**
 * The {@code VerifyPinDialogFragment} class shows the PIN verification settings.
 *
 * @author Godfrey Chung
 * @version 1.0, 4 Dec 2023
 * @since 1.3
 */
public class VerifyPinDialogFragment extends AppCompatDialogFragment {

    /**
     * Interface definition for a callback to be invoked when positive or negative button is
     * clicked.
     */
    public interface VerifyPinDialogListener {

        /**
         * Called when the positive button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onVerifyPinDialogPositiveClick(DialogFragment dialog);

        /**
         * Called when the negative button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onVerifyPinDialogNegativeClick(DialogFragment dialog);
    }

    private VerifyPinDialogListener mListener;
    private EditText mTimeoutEditText;
    private EditText mTimeout2EditText;
    private EditText mFormatStringEditText;
    private EditText mPinBlockStringEditText;
    private EditText mPinLengthFormatEditText;
    private EditText mPinMaxExtraDigitEditText;
    private EditText mEntryValidationConditionEditText;
    private EditText mNumberMessageEditText;
    private EditText mLangIdEditText;
    private EditText mMsgIndexEditText;
    private EditText mTeoPrologueEditText;
    private EditText mDataEditText;
    private final PinVerify mPinVerify = new PinVerify();

    @Override
    public void onAttach(@NonNull Context context) {

        super.onAttach(context);
        try {
            mListener = (VerifyPinDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement VerifyPinDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.verify_pin_dialog, null);

        mTimeoutEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_timeout);
        mTimeout2EditText = view.findViewById(R.id.verify_pin_dialog_edit_text_timeout2);
        mFormatStringEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_format_string);
        mPinBlockStringEditText = view.findViewById(
                R.id.verify_pin_dialog_edit_text_pin_block_string);
        mPinLengthFormatEditText = view.findViewById(
                R.id.verify_pin_dialog_edit_text_pin_length_format);
        mPinMaxExtraDigitEditText = view.findViewById(
                R.id.verify_pin_dialog_edit_text_pin_max_extra_digit);
        mEntryValidationConditionEditText = view.findViewById(
                R.id.verify_pin_dialog_edit_text_entry_validation_condition);
        mNumberMessageEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_number_message);
        mLangIdEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_lang_id);
        mMsgIndexEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_msg_index);
        mTeoPrologueEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_teo_prologue);
        mDataEditText = view.findViewById(R.id.verify_pin_dialog_edit_text_data);

        if (savedInstanceState == null) {

            mTimeoutEditText.setText(Hex.toHexString(mPinVerify.getTimeOut()));
            mTimeout2EditText.setText(Hex.toHexString(mPinVerify.getTimeOut2()));
            mFormatStringEditText.setText(Hex.toHexString(mPinVerify.getFormatString()));
            mPinBlockStringEditText.setText(Hex.toHexString(mPinVerify.getPinBlockString()));
            mPinLengthFormatEditText.setText(Hex.toHexString(mPinVerify.getPinLengthFormat()));
            mPinMaxExtraDigitEditText.setText(Hex.toHexString(mPinVerify.getPinMaxExtraDigit()));
            mEntryValidationConditionEditText.setText(
                    Hex.toHexString(mPinVerify.getEntryValidationCondition()));
            mNumberMessageEditText.setText(Hex.toHexString(mPinVerify.getNumberMessage()));
            mLangIdEditText.setText(Hex.toHexString(mPinVerify.getLangId()));
            mMsgIndexEditText.setText(Hex.toHexString(mPinVerify.getMsgIndex()));
            mTeoPrologueEditText.setText(
                    String.format("%02X %02X %02X", mPinVerify.getTeoPrologue(0),
                            mPinVerify.getTeoPrologue(1), mPinVerify.getTeoPrologue(2)));
            mDataEditText.setText(Hex.toHexString(mPinVerify.getData()));
        }

        builder.setTitle(R.string.verify_pin)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {

                    updateData();
                    mListener.onVerifyPinDialogPositiveClick(VerifyPinDialogFragment.this);
                })
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> mListener.onVerifyPinDialogNegativeClick(
                                VerifyPinDialogFragment.this));

        return builder.create();
    }

    /**
     * Gets the PIN_VERIFY data.
     *
     * @return the PIN_VERIFY data
     */
    public PinVerify getPinVerify() {
        return mPinVerify;
    }

    /**
     * Updates the data.
     */
    private void updateData() {

        byte[] buffer;

        buffer = Hex.toByteArray(mTimeoutEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setTimeOut(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mTimeout2EditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setTimeOut2(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mFormatStringEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setFormatString(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinBlockStringEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setPinBlockString(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinLengthFormatEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setPinLengthFormat(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinMaxExtraDigitEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 1)) {
            mPinVerify.setPinMaxExtraDigit((buffer[0] & 0xFF) << 8 | (buffer[1] & 0xFF));
        }

        buffer = Hex.toByteArray(mEntryValidationConditionEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setEntryValidationCondition(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mNumberMessageEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setNumberMessage(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mLangIdEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 1)) {
            mPinVerify.setLangId((buffer[0] & 0xFF) << 8 | (buffer[1] & 0xFF));
        }

        buffer = Hex.toByteArray(mMsgIndexEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setMsgIndex(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mTeoPrologueEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 2)) {

            mPinVerify.setTeoPrologue(0, buffer[0] & 0xFF);
            mPinVerify.setTeoPrologue(1, buffer[1] & 0xFF);
            mPinVerify.setTeoPrologue(2, buffer[2] & 0xFF);
        }

        buffer = Hex.toByteArray(mDataEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinVerify.setData(buffer, buffer.length);
        }
    }
}
