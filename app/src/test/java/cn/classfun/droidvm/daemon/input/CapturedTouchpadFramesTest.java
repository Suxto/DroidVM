// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.input;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CapturedTouchpadFramesTest {
    private final List<String> events = new ArrayList<>();
    private final XiaomiTouchpad.Output out = new XiaomiTouchpad.Output() {
        public void move(int x, int y) { events.add(String.format("move %d %d", x, y)); }
        public void button(int code, boolean down) { events.add(String.format("button %d %s", code, down)); }
        public void scroll(int v, int h) { events.add(String.format("scroll %d %d", v, h)); }
    };
    private final CapturedTouchpadFrames pad = new CapturedTouchpadFrames(out, true);
    private void single(int x, int up, long time, int buttons) {
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4}, new int[]{x}, new int[]{500}, up, time, buttons));
    }
    @Test public void androidUpStillListsLastFinger() {
        single(500, -1, 100, 0); single(500, 0, 180, 0);
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
    @Test public void twoFingerTapWithStaggeredLiftHasNoCentroidJump() {
        single(500, -1, 100, 0);
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4,8}, new int[]{500,1500}, new int[]{500,500}, -1, 120, 0));
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4,8}, new int[]{500,1500}, new int[]{500,500}, 1, 150, 0));
        single(500, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void reorderedPointerIndicesDoNotMoveTheCursor() {
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4,8}, new int[]{500,1500}, new int[]{500,500}, -1, 100, 0));
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{8,4}, new int[]{1500,500}, new int[]{500,500}, -1, 120, 0));
        assertTrue(events.isEmpty());
    }
    @Test public void physicalDragAndCaptureLossReleaseTheButton() {
        single(500, -1, 100, 0); single(500, -1, 110, 1);
        single(534, -1, 120, 1); pad.reset();
        assertEquals(List.of("button 272 true", "move 12 0", "button 272 false"), events);
    }
    @Test public void secondaryButtonNeverProducesAnExtraLeftTap() {
        single(500, -1, 100, 2); single(500, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void naturalScrollingUsesContactPositions() {
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4,8}, new int[]{500,1000}, new int[]{500,500}, -1, 100, 0));
        pad.accept(new CapturedTouchpadFrames.Frame(new int[]{4,8}, new int[]{500,1000}, new int[]{653,653}, -1, 120, 0));
        assertEquals(List.of("scroll 1 0"), events);
    }
    @Test public void explicitScrollCancelsThePendingTap() {
        single(500, -1, 100, 0); pad.cancelTap(); single(500, 0, 180, 0);
        assertTrue(events.isEmpty());
    }
}
