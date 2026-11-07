// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.input;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class XiaomiTouchpadTest {
    private final List<String> events = new ArrayList<>();
    private final XiaomiTouchpad pad = new XiaomiTouchpad(new XiaomiTouchpad.Output() {
        public void move(int x, int y) { events.add(String.format("move %d %d", x, y)); }
        public void button(int code, boolean down) {
            events.add(String.format("button %d %s", code, down));
        }
        public void scroll(int v, int h) { events.add(String.format("scroll %d %d", v, h)); }
    });
    private void contact(int slot, int id, int x, int y) {
        pad.event(3, 0x2f, slot, 0); pad.event(3, 0x39, id, 0);
        position(slot, x, y);
    }
    private void position(int slot, int x, int y) {
        pad.event(3, 0x2f, slot, 0);
        pad.event(3, 0x35, x, 0); pad.event(3, 0x36, y, 0);
    }
    private void frame(long now) { pad.event(0, 0, 0, now); }

    @Test public void singleTapClicksLeft() {
        contact(0, 1, 500, 500); frame(100);
        contact(0, -1, 500, 500); frame(180);
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
    @Test public void twoFingerTapClicksRightWithoutCentroidJump() {
        contact(0, 1, 500, 500); frame(100);
        contact(1, 2, 1500, 1500); frame(120);
        contact(1, -1, 1500, 1500); frame(150);
        contact(0, -1, 500, 500); frame(180);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void equalMillimetresHaveEqualPointerSpeedAndMovementCancelsTap() {
        contact(0, 1, 500, 500); frame(100);
        position(0, 602, 653); frame(120);
        contact(0, -1, 602, 653); frame(180);
        assertEquals(List.of("move 36 36"), events);
    }
    @Test public void scrollAccumulatesSubNotchMovement() {
        contact(0, 1, 500, 500); contact(1, 2, 1000, 500); frame(100);
        position(0, 500, 551); position(1, 1000, 551); frame(120);
        position(0, 500, 602); position(1, 1000, 602); frame(140);
        assertTrue(events.isEmpty());
        position(0, 500, 653); position(1, 1000, 653); frame(160);
        assertEquals(List.of("scroll -1 0"), events);
    }
    @Test public void physicalDragAndResetReleaseButtonWithoutTap() {
        contact(0, 1, 500, 500); frame(100);
        pad.event(1, 0x110, 1, 110); frame(110);
        position(0, 534, 500); frame(120);
        pad.reset();
        frame(130);
        assertEquals(List.of("button 272 true", "move 12 0", "button 272 false"), events);
    }
    @Test public void threeFingerGestureDoesNotProduceClick() {
        for (int i = 0; i < 3; i++) contact(i, i + 1, 500, 500);
        frame(100);
        for (int i = 0; i < 3; i++) contact(i, -1, 500, 500);
        frame(150);
        assertTrue(events.isEmpty());
    }
    @Test public void droppedFrameReleasesDrag() {
        contact(0, 1, 500, 500); frame(100);
        pad.event(1, 0x110, 1, 110);
        pad.event(0, 3, 0, 120);
        frame(130);
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
}
