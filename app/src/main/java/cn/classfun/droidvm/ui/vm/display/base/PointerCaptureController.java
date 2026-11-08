// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;

/** Foreground ownership of Android pointer capture for one display page. */
public final class PointerCaptureController {
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private StandardPointerCapture standard;
    private InputForwarder.InputSink sink;
    private boolean enabled, resumed, focused, closed;
    private final Runnable acquire = () -> {
        if (ready()) standard.start(isNaturalScroll());
    };

    public PointerCaptureController(Context context) {
        // Keep the existing scrolling preference when upgrading from the trial builds.
        preferences = context.getSharedPreferences("pointer-grab", Context.MODE_PRIVATE);
        preferences.edit().remove("root_capture").apply();
    }
    public void setView(CapturedPointerLayout view) {
        if (standard != null) standard.close();
        standard = new StandardPointerCapture(view);
        standard.setSink(sink);
        apply();
    }
    public boolean isEnabled() { return enabled; }
    public boolean isNaturalScroll() { return preferences.getBoolean("natural_scroll", true); }
    public void setNaturalScroll(boolean value) {
        if (value == isNaturalScroll()) return;
        preferences.edit().putBoolean("natural_scroll", value).apply();
        apply();
    }
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value; apply();
    }
    public void setResumed(boolean value) {
        if (resumed == value) return;
        resumed = value; apply();
    }
    public void setFocused(boolean value) {
        if (focused == value) return;
        focused = value; apply();
    }
    public void setSink(InputForwarder.InputSink value) {
        if (closed) return;
        if (sink == value) return;
        sink = value;
        if (standard != null) standard.setSink(value);
        apply();
    }
    public void requestAfterClick(MotionEvent event) {
        if (ready()) standard.requestAfterClick(event);
    }
    private boolean ready() {
        return !closed && enabled && resumed && focused && sink != null && standard != null;
    }
    private void apply() {
        main.removeCallbacks(acquire);
        if (!ready()) {
            if (standard != null) standard.stop();
            return;
        }
        // Run after the menu/focus callbacks; no root-helper handover delay is needed.
        main.post(acquire);
    }
    public void close() {
        closed = true;
        main.removeCallbacks(acquire);
        if (standard != null) standard.close();
    }
}
