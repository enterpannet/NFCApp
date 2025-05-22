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

/**
 * The {@code DisplayLcdMessageDialogFragment} class inputs and displays a message on the LCD.
 *
 * @author Godfrey Chung
 * @version 1.0, 27 Dec 2023
 * @since 1.3
 */
public class DisplayLcdMessageDialogFragment extends AppCompatDialogFragment {

    /**
     * Interface definition for a callback to be invoked when positive or negative button is
     * clicked.
     */
    public interface DisplayLcdMessageDialogListener {

        /**
         * Called when the positive button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onDisplayLcdMessageDialogPositiveClick(DialogFragment dialog);

        /**
         * Called when the negative button is clicked.
         *
         * @param dialog the dialog fragment
         */
        void onDisplayLcdMessageDialogNegativeClick(DialogFragment dialog);
    }

    private DisplayLcdMessageDialogListener mListener;
    private EditText mMessageEditText;
    private String mMessage;

    @Override
    public void onAttach(@NonNull Context context) {

        super.onAttach(context);
        try {
            mListener = (DisplayLcdMessageDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(
                    context + " must implement DisplayLcdMessageDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.display_lcd_message_dialog, null);

        mMessageEditText = view.findViewById(R.id.display_lcd_message_dialog_edit_text_message);

        if (savedInstanceState == null) {
            mMessageEditText.setText(mMessage);
        }

        builder.setTitle(R.string.display_lcd_message)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {

                    updateData();
                    mListener.onDisplayLcdMessageDialogPositiveClick(
                            DisplayLcdMessageDialogFragment.this);
                })
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> mListener.onDisplayLcdMessageDialogNegativeClick(
                                DisplayLcdMessageDialogFragment.this));

        return builder.create();
    }

    /**
     * Gets the message.
     *
     * @return the message
     */
    public String getMessage() {
        return mMessage;
    }

    /**
     * Sets the message.
     *
     * @param message the message
     */
    public void setMessage(String message) {
        mMessage = message;
    }

    /**
     * Updates the data.
     */
    private void updateData() {
        mMessage = mMessageEditText.getText().toString();
    }
}
