// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.input;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;

/** Opt-in Xiaomi pad experiment. A foreground UI must renew its three-second lease. */
public final class PointerGrabManager {
    private final ServerContext ctx;
    private final Map<String, Integer> fds = new LinkedHashMap<>();
    private final byte[] buffer = new byte[24 * 64];
    private String vmId = "", screen = "";
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private volatile long expires;
    private long nextScan;
    private final Object fdLock = new Object();
    private int[] rescueFds = new int[0];
    private volatile boolean revoked;
    private final XiaomiTouchpad pad;

    public PointerGrabManager(ServerContext ctx) {
        this.ctx = ctx;
        pad = new XiaomiTouchpad(new XiaomiTouchpad.Output() {
            public void move(int x, int y) { write(EvdevEncoder.encodeMouseMove(x, y)); }
            public void button(int code, boolean down) {
                write(EvdevEncoder.encodeMouseButton((short) code, down));
            }
            public void scroll(int v, int h) { write(EvdevEncoder.encodeMouseWheel(v, h)); }
        });
        var worker = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "pointer-grab"); t.setDaemon(true); return t;
        });
        worker.scheduleWithFixedDelay(this::tick, 8, 8, TimeUnit.MILLISECONDS);
        var rescue = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "pointer-rescue"); t.setDaemon(true); return t;
        });
        rescue.scheduleWithFixedDelay(() -> {
            // Independent of the guest writer: even a blocked crosvm socket cannot trap the pad.
            synchronized (fdLock) {
                if (SystemClock.uptimeMillis() < expires || rescueFds.length == 0) return;
                revoked = true;
                for (int fd : rescueFds) UnixHelper.nativeEvdevGrab(fd, false);
                rescueFds = new int[0];
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
    }

    public synchronized int set(String id, String screenId, boolean grab, IBinder token) {
        if (!grab) {
            if (owner == token) release();
            return 0;
        }
        var vm = ctx.getVMs().findById(id);
        if (token == null || vm == null || vm.getState() != VMState.RUNNING) {
            if (owner == token) release();
            return 0;
        }
        if (owner != token || !vmId.equals(id) || !screen.equals(screenId)) {
            release();
            owner = token; vmId = id; screen = screenId; revoked = false;
            death = () -> { synchronized (this) { if (owner == token) release(); } };
            try { token.linkToDeath(death, 0); }
            catch (Exception e) { release(); return 0; }
        }
        expires = SystemClock.uptimeMillis() + 3000;
        return fds.size();
    }

    private synchronized void tick() {
        if (owner == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            var vm = ctx.getVMs().findById(vmId);
            if (revoked || now >= expires || vm == null || vm.getState() != VMState.RUNNING) {
                release(); return;
            }
            if (now >= nextScan) { scan(); nextScan = now + 1000; }
            for (var entry : fds.entrySet()) {
                int ready = UnixHelper.nativePollIn(entry.getValue(), 0);
                if (ready < 0) { release(); return; }
                if (ready == 0) continue;
                int n = UnixHelper.nativeRead(entry.getValue(), buffer, buffer.length);
                if (n <= 0 || n % 24 != 0) { release(); return; }
                // Xiaomi Mouse is the compatibility twin. Hold it, but only translate Touch.
                if (!entry.getKey().equals("Xiaomi Touch")) continue;
                var bytes = ByteBuffer.wrap(buffer, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                for (int offset = 0; offset < n; offset += 24) {
                    int type = bytes.getShort(offset + 16) & 0xffff;
                    int code = bytes.getShort(offset + 18) & 0xffff;
                    if (type == 0 && code == 3) { release(); return; }
                    pad.event(type, code, bytes.getInt(offset + 20), now);
                }
            }
        } catch (Throwable e) {
            Log.e("PointerGrab", "capture failed; releasing", e);
            release();
        }
    }

    private void scan() {
        // Rescan names AND bus/vendor/product; event numbers change across reconnects.
        var files = new File("/dev/input").listFiles();
        if (files == null) return;
        for (var file : files) {
            if (!file.getName().matches("event[0-9]+")) continue;
            int fd = UnixHelper.nativeEvdevOpen(file.getPath());
            if (fd < 0) continue;
            String name = UnixHelper.nativeEvdevName(fd);
            int[] ids = UnixHelper.nativeEvdevIds(fd);
            boolean wanted = ids != null && ids.length >= 3 && ids[0] == 6
                && ids[1] == 0x15d9 && (("Xiaomi Touch".equals(name) && ids[2] == 0xa1)
                || ("Xiaomi Mouse".equals(name) && ids[2] == 0xa2));
            if (wanted && !fds.containsKey(name) && UnixHelper.nativeEvdevGrab(fd, true) == 0) {
                synchronized (fdLock) {
                    fds.put(name, fd);
                    rescueFds = fds.values().stream().mapToInt(Integer::intValue).toArray();
                }
                Log.i("PointerGrab", cn.classfun.droidvm.lib.utils.StringUtils.fmt("grabbed %s", name));
            } else UnixHelper.nativeCloseFd(fd);
        }
        // Never swallow the compatibility mouse if the actual pad couldn't be captured.
        if (fds.size() != 2) release();
    }

    private void write(byte[] bytes) {
        if (bytes == null) return;
        var vm = ctx.getVMs().findById(vmId);
        if (vm == null || !vm.writeNativeInput(screen, NativeDisplay.MOUSE, bytes))
            throw new IllegalStateException("guest mouse channel unavailable");
    }

    private void release() {
        // Free hardware before writing button-up: the guest socket can be blocked.
        synchronized (fdLock) {
            rescueFds = new int[0];
            for (int fd : fds.values()) {
                try { UnixHelper.nativeEvdevGrab(fd, false); }
                finally { UnixHelper.nativeCloseFd(fd); }
            }
            fds.clear();
        }
        try { pad.reset(); } catch (Throwable ignored) { }
        if (owner != null && death != null) {
            try { owner.unlinkToDeath(death, 0); } catch (Exception ignored) { }
        }
        owner = null; death = null; vmId = screen = ""; nextScan = 0;
    }
}
