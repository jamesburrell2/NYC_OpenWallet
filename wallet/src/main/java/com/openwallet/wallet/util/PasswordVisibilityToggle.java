package com.openwallet.wallet.util;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import com.openwallet.wallet.R;

/**
 * Adds a tappable "eye" icon at the end of a password {@link EditText} so the user
 * can optionally reveal what they type. Dependency-free (no Material TextInputLayout,
 * no layout XML changes) — wired from each screen's controller with a single call.
 *
 * <p>Visibility is toggled via {@link PasswordTransformationMethod} (not by swapping
 * inputType), which preserves the field's typeface and keyboard behaviour and never
 * changes the underlying text.
 *
 * <p>SOC-2: this affects only on-screen rendering on the user's own device, defaults
 * to hidden, and never logs, stores, or transmits the password.
 */
public final class PasswordVisibilityToggle {
    private PasswordVisibilityToggle() {}

    /** Attaches a show/hide toggle to each given password field (nulls are ignored). */
    public static void attach(EditText... fields) {
        if (fields == null) return;
        for (EditText f : fields) attachOne(f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void attachOne(final EditText edit) {
        if (edit == null) return;
        final Drawable eye =
                ContextCompat.getDrawable(edit.getContext(), R.drawable.ic_visibility);
        final Drawable eyeOff =
                ContextCompat.getDrawable(edit.getContext(), R.drawable.ic_visibility_off);

        // Start hidden, showing the "reveal" (eye) affordance.
        edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        setEndIcon(edit, eye);

        edit.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP) return false;
                Drawable end = edit.getCompoundDrawablesRelative()[2];
                if (end == null) return false;
                int iconWidth = end.getBounds().width();
                boolean tappedIcon =
                        event.getX() >= edit.getWidth() - edit.getPaddingEnd() - iconWidth;
                if (!tappedIcon) return false;

                boolean currentlyHidden =
                        edit.getTransformationMethod() instanceof PasswordTransformationMethod;
                int sel = edit.getSelectionEnd();
                if (currentlyHidden) {
                    edit.setTransformationMethod(null);          // reveal
                    setEndIcon(edit, eyeOff);
                } else {
                    edit.setTransformationMethod(
                            PasswordTransformationMethod.getInstance()); // hide
                    setEndIcon(edit, eye);
                }
                if (sel >= 0) edit.setSelection(sel);
                v.performClick();
                return true;
            }
        });
    }

    private static void setEndIcon(EditText edit, Drawable icon) {
        edit.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null);
    }
}
