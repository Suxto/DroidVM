// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;

/** One foreground owner, with mutually exclusive Android and root capture backends. */
public final class PointerCaptureController {
    private final Context context;
    private final PhysicalPointerGrab root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private StandardPointerCapture standard;
    private InputForwarder.InputSink sink;
    private boolean enabled, resumed, focused, closed;
    private int generation;

    public PointerCaptureController(Context context) {
        this.context = context.getApplicationContext();
        root = new PhysicalPointerGrab(context);
    }
    public void setView(CapturedPointerLayout view) {
        standard = new StandardPointerCapture(view);
        standard.setSink(sink);
    }
    public boolean isEnabled() { return enabled; }
    public boolean isRootMode() {
        return context.getSharedPreferences("pointer-grab", Context.MODE_PRIVATE)
            .getBoolean("root_capture", false);
    }
    public void setRootMode(boolean value) {
        context.getSharedPreferences("pointer-grab", Context.MODE_PRIVATE).edit()
            .putBoolean("root_capture", value).apply();
        apply();
    }
    public boolean isNaturalScroll() { return root.isNaturalScroll(); }
    public void setNaturalScroll(boolean value) {
        context.getSharedPreferences("pointer-grab", Context.MODE_PRIVATE).edit()
            .putBoolean("natural_scroll", value).apply();
        apply();
    }
    public void setEnabled(boolean value) { enabled = value; apply(); }
    public void setResumed(boolean value) { resumed = value; root.setResumed(value); apply(); }
    public void setFocused(boolean value) { focused = value; root.setFocused(value); apply(); }
    public void setSink(InputForwarder.InputSink value) {
        sink = value;
        root.setEnabled(false);
        root.setSink(value);
        if (standard != null) standard.setSink(value);
        apply();
    }
    public void requestAfterClick(MotionEvent event) {
        if (!isRootMode() && standard != null) standard.requestAfterClick(event);
    }
    private void apply() {
        int request = ++generation;
        root.setEnabled(false);
        if (standard != null) standard.stop();
        if (closed || !enabled || !resumed || !focused || sink == null) return;
        // Let the old helper release evdev / Android acknowledge release before switching.
        main.postDelayed(() -> {
            if (closed || generation != request) return;
            if (isRootMode()) root.setEnabled(true);
            else if (standard != null) standard.start(isNaturalScroll());
        }, 250);
    }
    public void close() {
        closed = true; generation++;
        main.removeCallbacksAndMessages(null);
        root.close();
        if (standard != null) standard.close();
    }
}
