// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.util.Log;
import java.util.Arrays;
import cn.classfun.droidvm.daemon.input.XiaomiTouchpad;
import cn.classfun.droidvm.daemon.input.CapturedTouchpadFrames;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;

/** Android's public pointer-capture API. No su or evdev access on this path. */
public final class StandardPointerCapture implements CapturedPointerLayout.Handler {
    private final CapturedPointerLayout view;
    private InputForwarder forwarder;
    private CapturedTouchpadFrames pad;
    private boolean active, natural;
    private int mouseButtons;
    private boolean seenPad, seenMouse;
    private float mouseX, mouseY;
    private final float[] wheelV = new float[2], wheelH = new float[2];

    public StandardPointerCapture(CapturedPointerLayout view) {
        this.view = view;
        view.setCaptureHandler(this);
    }
    public void setSink(InputForwarder.InputSink sink) {
        stop();
        if (forwarder != null) forwarder.drainAndClose();
        forwarder = sink == null ? null : new InputForwarder(sink);
        if (forwarder != null) forwarder.setInputMode(InputMode.MOUSE);
    }
    public void start(boolean natural) {
        if (forwarder == null || !view.hasWindowFocus()) return;
        this.natural = natural;
        seenPad = seenMouse = false;
        pad = new CapturedTouchpadFrames(new XiaomiTouchpad.Output() {
            public void move(int x, int y) { forwarder.sendMouseMove(x, y); }
            public void button(int code, boolean down) { forwarder.sendPointerButton((short) code, down); }
            public void scroll(int v, int h) { forwarder.sendScroll(v, h); }
        }, natural);
        active = true;
        view.requestPointerCapture();
    }
    public void stop() {
        active = false;
        if (view.hasPointerCapture()) view.releasePointerCapture();
        reset();
    }
    public void close() { setSink(null); view.setCaptureHandler(null); }
    public boolean hasCapture() { return view.hasPointerCapture(); }
    public void requestAfterClick(MotionEvent event) {
        if (active && !hasCapture() && event.isFromSource(InputDevice.SOURCE_MOUSE))
            view.requestPointerCapture();
    }
    @Override public void onCaptureChanged(boolean captured) {
        if (!captured) reset();
        Log.i("StandardPointerCapture", captured ? "Android pointer capture acquired" : "Android pointer capture released");
    }
    private void reset() {
        if (pad != null) pad.reset();
        if (forwarder != null) {
            buttons(mouseButtons, 0);
        }
        mouseButtons = 0;
        mouseX = mouseY = 0;
        Arrays.fill(wheelV, 0); Arrays.fill(wheelH, 0);
    }
    @Override public boolean onEvent(MotionEvent event) {
        if (!active || forwarder == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { reset(); return true; }
        // OEMs may combine capability bits: raw TOUCHPAD takes precedence over mouse.
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            if (!seenPad) { logSource("touchpad", event); seenPad = true; }
            return touchpad(event);
        }
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                && !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        if (!seenMouse) { logSource("mouse", event); seenMouse = true; }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            for (int h = 0; h < event.getHistorySize(); h++)
                mouseMove(event.getHistoricalX(h), event.getHistoricalY(h));
            mouseMove(event.getX(), event.getY());
        }
        if (action == MotionEvent.ACTION_SCROLL) scroll(event, false);
        buttons(mouseButtons, event.getButtonState());
        mouseButtons = event.getButtonState();
        return true;
    }
    private void logSource(String kind, MotionEvent event) {
        Log.i("StandardPointerCapture", String.format("captured %s source=0x%x device=%s", kind,
            event.getSource(), event.getDevice() == null ? "unknown" : event.getDevice().getName()));
    }
    private void mouseMove(float x, float y) {
        mouseX += x; mouseY += y;
        int dx = (int) mouseX, dy = (int) mouseY;
        mouseX -= dx; mouseY -= dy;
        forwarder.sendMouseMove(dx, dy);
    }
    private void scroll(MotionEvent event, boolean touchpad) {
        int stream = touchpad ? 1 : 0;
        float sign = touchpad && natural ? -1 : 1;
        wheelV[stream] += event.getAxisValue(MotionEvent.AXIS_VSCROLL) * sign;
        wheelH[stream] += event.getAxisValue(MotionEvent.AXIS_HSCROLL) * sign;
        int v = (int) wheelV[stream], h = (int) wheelH[stream];
        wheelV[stream] -= v; wheelH[stream] -= h;
        if (v != 0 || h != 0) forwarder.sendScroll(v, h);
    }
    private void buttons(int before, int after) {
        int[] bits = {1, 2, 4, 8, 16};
        for (int i = 0; i < bits.length; i++)
            if (((before ^ after) & bits[i]) != 0)
                forwarder.sendPointerButton((short) (0x110 + i), (after & bits[i]) != 0);
    }
    private boolean touchpad(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) { pad.cancelTap(); scroll(event, true); return true; }
        for (int h = 0; h < event.getHistorySize(); h++) sample(event, h);
        sample(event, -1);
        return true;
    }
    private void sample(MotionEvent event, int history) {
        long time = history < 0 ? event.getEventTime() : event.getHistoricalEventTime(history);
        int action = event.getActionMasked();
        int up = history < 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
            ? event.getActionIndex() : -1;
        int count = event.getPointerCount();
        int[] ids = new int[count], x = new int[count], y = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            float px = history < 0 ? event.getX(i) : event.getHistoricalX(i, history);
            float py = history < 0 ? event.getY(i) : event.getHistoricalY(i, history);
            x[i] = normalize(event, MotionEvent.AXIS_X, px);
            y[i] = normalize(event, MotionEvent.AXIS_Y, py);
        }
        pad.accept(new CapturedTouchpadFrames.Frame(ids, x, y, up, time, event.getButtonState()));
    }
    private int normalize(MotionEvent event, int axis, float value) {
        boolean x = axis == MotionEvent.AXIS_X;
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);
        if (range == null && device != null) range = device.getMotionRange(axis);
        if (range == null) return Math.round(value);
        float resolution = range.getResolution();
        if (resolution <= 0) resolution = range.getRange() / (x ? 94f : 42f);
        return resolution > 0 ? Math.round((value - range.getMin()) * (x ? 34 : 51) / resolution)
            : Math.round(value);
    }
}
