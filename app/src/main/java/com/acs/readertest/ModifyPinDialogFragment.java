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

import com.acs.smartcard.PinModify;

/**
 * The {@code ModifyPinDialogFragment} class shows the PIN modification settings.
 *
 * @author Godfrey Chung
 * @version 1.0, 19 Dec 2023
 * @since 1.3
 */
public class ModifyPinDialogFragment extends AppCompatDialogFragment {

    /**
     * Interface definition for a callback to be invoked when positive or negative button is
     * clicked.
     */
    public interface ModifyPinDialogListener {

        /**
         * Called when the positive button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onModifyPinDialogPositiveClick(DialogFragment dialog);

        /**
         * Called when the negative button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onModifyPinDialogNegativeClick(DialogFragment dialog);
    }

    private ModifyPinDialogListener mListener;
    private EditText mTimeoutEditText;
    private EditText mTimeout2EditText;
    private EditText mFormatStringEditText;
    private EditText mPinBlockStringEditText;
    private EditText mPinLengthFormatEditText;
    private EditText mInsertionOffsetOldEditText;
    private EditText mInsertionOffsetNewEditText;
    private EditText mPinMaxExtraDigitEditText;
    private EditText mConfirmPinEditText;
    private EditText mEntryValidationConditionEditText;
    private EditText mNumberMessageEditText;
    private EditText mLangIdEditText;
    private EditText mMsgIndex1EditText;
    private EditText mMsgIndex2EditText;
    private EditText mMsgIndex3EditText;
    private EditText mTeoPrologueEditText;
    private EditText mDataEditText;
    private final PinModify mPinModify = new PinModify();

    @Override
    public void onAttach(@NonNull Context context) {

        super.onAttach(context);
        try {
            mListener = (ModifyPinDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement ModifyPinDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.modify_pin_dialog, null);

        mTimeoutEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_timeout);
        mTimeout2EditText = view.findViewById(R.id.modify_pin_dialog_edit_text_timeout2);
        mFormatStringEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_format_string);
        mPinBlockStringEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_pin_block_string);
        mPinLengthFormatEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_pin_length_format);
        mInsertionOffsetOldEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_insertion_offset_old);
        mInsertionOffsetNewEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_insertion_offset_new);
        mPinMaxExtraDigitEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_pin_max_extra_digit);
        mConfirmPinEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_confirm_pin);
        mEntryValidationConditionEditText = view.findViewById(
                R.id.modify_pin_dialog_edit_text_entry_validation_condition);
        mNumberMessageEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_number_message);
        mLangIdEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_lang_id);
        mMsgIndex1EditText = view.findViewById(R.id.modify_pin_dialog_edit_text_msg_index1);
        mMsgIndex2EditText = view.findViewById(R.id.modify_pin_dialog_edit_text_msg_index2);
        mMsgIndex3EditText = view.findViewById(R.id.modify_pin_dialog_edit_text_msg_index3);
        mTeoPrologueEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_teo_prologue);
        mDataEditText = view.findViewById(R.id.modify_pin_dialog_edit_text_data);

        if (savedInstanceState == null) {

            mTimeoutEditText.setText(Hex.toHexString(mPinModify.getTimeOut()));
            mTimeout2EditText.setText(Hex.toHexString(mPinModify.getTimeOut2()));
            mFormatStringEditText.setText(Hex.toHexString(mPinModify.getFormatString()));
            mPinBlockStringEditText.setText(Hex.toHexString(mPinModify.getPinBlockString()));
            mPinLengthFormatEditText.setText(Hex.toHexString(mPinModify.getPinLengthFormat()));
            mInsertionOffsetOldEditText.setText(
                    Hex.toHexString(mPinModify.getInsertionOffsetOld()));
            mInsertionOffsetNewEditText.setText(
                    Hex.toHexString(mPinModify.getInsertionOffsetNew()));
            mPinMaxExtraDigitEditText.setText(Hex.toHexString(mPinModify.getPinMaxExtraDigit()));
            mConfirmPinEditText.setText(Hex.toHexString(mPinModify.getConfirmPin()));
            mEntryValidationConditionEditText.setText(
                    Hex.toHexString(mPinModify.getEntryValidationCondition()));
            mNumberMessageEditText.setText(Hex.toHexString(mPinModify.getNumberMessage()));
            mLangIdEditText.setText(Hex.toHexString(mPinModify.getLangId()));
            mMsgIndex1EditText.setText(Hex.toHexString(mPinModify.getMsgIndex1()));
            mMsgIndex2EditText.setText(Hex.toHexString(mPinModify.getMsgIndex2()));
            mMsgIndex3EditText.setText(Hex.toHexString(mPinModify.getMsgIndex3()));
            mTeoPrologueEditText.setText(
                    String.format("%02X %02X %02X", mPinModify.getTeoPrologue(0),
                            mPinModify.getTeoPrologue(1), mPinModify.getTeoPrologue(2)));
            mDataEditText.setText(Hex.toHexString(mPinModify.getData()));
        }

        builder.setTitle(R.string.modify_pin)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {

                    updateData();
                    mListener.onModifyPinDialogPositiveClick(ModifyPinDialogFragment.this);
                })
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> mListener.onModifyPinDialogNegativeClick(
                                ModifyPinDialogFragment.this));

        return builder.create();
    }

    /**
     * Gets the PIN_MODIFY data.
     *
     * @return the PIN_MODIFY data
     */
    public PinModify getPinModify() {
        return mPinModify;
    }

    /**
     * Updates the data.
     */
    private void updateData() {

        byte[] buffer;

        buffer = Hex.toByteArray(mTimeoutEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setTimeOut(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mTimeout2EditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setTimeOut2(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mFormatStringEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setFormatString(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinBlockStringEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setPinBlockString(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinLengthFormatEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setPinLengthFormat(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mInsertionOffsetOldEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setInsertionOffsetOld(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mInsertionOffsetNewEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setInsertionOffsetNew(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mPinMaxExtraDigitEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 1)) {
            mPinModify.setPinMaxExtraDigit((buffer[0] & 0xFF) << 8 | (buffer[1] & 0xFF));
        }

        buffer = Hex.toByteArray(mConfirmPinEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setConfirmPin(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mEntryValidationConditionEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setEntryValidationCondition(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mNumberMessageEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setNumberMessage(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mLangIdEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 1)) {
            mPinModify.setLangId((buffer[0] & 0xFF) << 8 | (buffer[1] & 0xFF));
        }

        buffer = Hex.toByteArray(mMsgIndex1EditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setMsgIndex1(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mMsgIndex2EditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setMsgIndex2(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mMsgIndex3EditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setMsgIndex3(buffer[0] & 0xFF);
        }

        buffer = Hex.toByteArray(mTeoPrologueEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 2)) {

            mPinModify.setTeoPrologue(0, buffer[0] & 0xFF);
            mPinModify.setTeoPrologue(1, buffer[1] & 0xFF);
            mPinModify.setTeoPrologue(2, buffer[2] & 0xFF);
        }

        buffer = Hex.toByteArray(mDataEditText.getText().toString());
        if ((buffer != null) && (buffer.length > 0)) {
            mPinModify.setData(buffer, buffer.length);
        }
    }
}
