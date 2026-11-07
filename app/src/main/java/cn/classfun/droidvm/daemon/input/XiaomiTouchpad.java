// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.input;

import java.util.Arrays;

/** Xiaomi 15d9:00a1 type-B MT pad. Independent of Android for recorded-frame tests. */
public final class XiaomiTouchpad {
    public interface Output {
        void move(int x, int y);
        void button(int code, boolean down);
        void scroll(int vertical, int horizontal);
    }
    private final Output out;
    private final boolean naturalScroll;
    private final boolean[] active = new boolean[8];
    private final int[] x = new int[8], y = new int[8];
    private int slot, previousCount, maxCount, physicalButton;
    private int previousMask;
    private float lastX, lastY, travel, remainderX, remainderY, wheelX, wheelY;
    private long started;
    private boolean clicked;

    public XiaomiTouchpad(Output out) { this(out, true); }

    public XiaomiTouchpad(Output out, boolean naturalScroll) {
        this.out = out;
        this.naturalScroll = naturalScroll;
    }

    public void event(int type, int code, int value, long now) {
        if (type == 0 && code == 3) { // SYN_DROPPED: caller must reopen for a clean baseline.
            reset();
            return;
        }
        if (type == 3) {
            if (code == 0x2f) slot = value >= 0 && value < 8 ? value : -1;
            else if (slot >= 0) {
                if (code == 0x39) active[slot] = value >= 0;
                else if (code == 0x35) x[slot] = value;
                else if (code == 0x36) y[slot] = value;
            }
        } else if (type == 1 && code == 0x110) {
            if (value == 1 && physicalButton == 0) {
                int n = 0;
                for (boolean a : active) if (a) n++;
                physicalButton = n >= 2 ? 0x111 : 0x110;
                clicked = true;
                out.button(physicalButton, true);
            } else if (value == 0 && physicalButton != 0) {
                out.button(physicalButton, false);
                physicalButton = 0;
            }
        } else if (type == 0 && code == 0) frame(now);
    }

    private void frame(long now) {
        int count = 0, mask = 0;
        float cx = 0, cy = 0;
        for (int i = 0; i < 8; i++) if (active[i]) {
            count++; mask |= 1 << i; cx += x[i]; cy += y[i];
        }
        if (count > 0) { cx /= count; cy /= count; }
        if (previousCount == 0 && count > 0) {
            started = now; travel = 0; maxCount = 0; clicked = physicalButton != 0;
            remainderX = remainderY = wheelX = wheelY = 0;
        }
        maxCount = Math.max(maxCount, count);
        if (count > 0 && mask == previousMask) {
            // Actual pad resolutions: X=34 units/mm, Y=51 units/mm. Equal physical speed.
            float dx = (cx - lastX) / 34f, dy = (cy - lastY) / 51f;
            travel += (float) Math.hypot(dx, dy);
            if (count == 1 || physicalButton != 0) {
                remainderX += dx * 12; remainderY += dy * 12;
                int ix = (int) remainderX, iy = (int) remainderY;
                remainderX -= ix; remainderY -= iy;
                if (ix != 0 || iy != 0) out.move(ix, iy);
            } else if (count == 2) {
                wheelX += dx / 3; wheelY -= dy / 3;
                int hx = (int) wheelX, vy = (int) wheelY;
                wheelX -= hx; wheelY -= vy;
                if (hx != 0 || vy != 0)
                    out.scroll(naturalScroll ? -vy : vy, naturalScroll ? -hx : hx);
            }
        }
        if (count == 0 && previousCount > 0 && !clicked && travel < 2
                && now - started <= 250 && maxCount <= 2) {
            int button = maxCount == 2 ? 0x111 : 0x110;
            out.button(button, true); out.button(button, false);
        }
        if (mask != previousMask) wheelX = wheelY = remainderX = remainderY = 0;
        lastX = cx; lastY = cy; previousCount = count; previousMask = mask;
    }

    public void cancelTap() { clicked = true; }

    public void reset() {
        int released = physicalButton;
        physicalButton = previousCount = previousMask = maxCount = 0;
        Arrays.fill(active, false);
        remainderX = remainderY = wheelX = wheelY = 0;
        clicked = false;
        if (released != 0) out.button(released, false);
    }
}
