// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.input;

import java.util.Arrays;

/** Type-B conversion for Android captured contacts, including the pointer still listed on UP. */
public final class CapturedTouchpadFrames {
    public static final class Frame {
        final int[] ids, x, y;
        final int upIndex, buttons;
        final long time;
        public Frame(int[] ids, int[] x, int[] y, int upIndex, long time, int buttons) {
            this.ids = ids; this.x = x; this.y = y;
            this.upIndex = upIndex; this.time = time; this.buttons = buttons;
        }
    }
    private final int[] slots = new int[8];
    private final XiaomiTouchpad pad;
    private final XiaomiTouchpad.Output out;
    private int buttons;

    public CapturedTouchpadFrames(XiaomiTouchpad.Output out, boolean natural) {
        this.out = out;
        pad = new XiaomiTouchpad(out, natural);
        Arrays.fill(slots, -1);
    }
    public void accept(Frame frame) {
        for (int s = 0; s < slots.length; s++) {
            int index = -1;
            for (int i = 0; i < frame.ids.length; i++) if (slots[s] == frame.ids[i]) index = i;
            if (slots[s] >= 0 && (index < 0 || index == frame.upIndex)) {
                pad.event(3, 0x2f, s, frame.time); pad.event(3, 0x39, -1, frame.time); slots[s] = -1;
            }
        }
        for (int i = 0; i < frame.ids.length; i++) {
            if (i == frame.upIndex) continue;
            int slot = -1;
            for (int s = 0; s < slots.length; s++) if (slots[s] == frame.ids[i]) slot = s;
            if (slot < 0) {
                for (int s = 0; s < slots.length; s++) if (slots[s] < 0) { slot = s; break; }
                if (slot < 0) continue;
                slots[slot] = frame.ids[i];
                pad.event(3, 0x2f, slot, frame.time); pad.event(3, 0x39, frame.ids[i], frame.time);
            }
            pad.event(3, 0x2f, slot, frame.time);
            pad.event(3, 0x35, frame.x[i], frame.time);
            pad.event(3, 0x36, frame.y[i], frame.time);
        }
        if (((buttons ^ frame.buttons) & 1) != 0)
            pad.event(1, 0x110, (frame.buttons & 1) != 0 ? 1 : 0, frame.time);
        if (((buttons | frame.buttons) & ~1) != 0) pad.cancelTap();
        extraButtons(buttons, frame.buttons);
        buttons = frame.buttons;
        pad.event(0, 0, 0, frame.time);
        if ((buttons & ~1) != 0) pad.cancelTap();
    }
    private void extraButtons(int before, int after) {
        for (int bit = 1; bit < 5; bit++) {
            int mask = 1 << bit;
            if (((before ^ after) & mask) != 0) out.button(0x110 + bit, (after & mask) != 0);
        }
    }
    public void cancelTap() { pad.cancelTap(); }
    public void reset() {
        pad.reset(); extraButtons(buttons, 0); buttons = 0; Arrays.fill(slots, -1);
    }
}
