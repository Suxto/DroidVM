// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import cn.classfun.droidvm.display.INativeDisplayRootService;

/** Main-loop lease: even an ANR stops renewals and restores Android's pointer. */
public final class PhysicalPointerGrab {
    private final String vmId;
    private final Supplier<String> screen;
    private final Binder token = new Binder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private INativeDisplayRootService service;
    private boolean enabled, resumed, closed, pending;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (closed || !enabled || !resumed || service == null) return;
            // At most one outstanding renewal; never accumulate leases during a stalled IPC.
            if (!pending) send(true);
            main.postDelayed(this, 750);
        }
    };

    public PhysicalPointerGrab(String vmId, Supplier<String> screen) {
        this.vmId = vmId; this.screen = screen;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; apply(); }
    public void setResumed(boolean value) { resumed = value; apply(); }
    public void setService(INativeDisplayRootService value) {
        if (service != null && service != value) send(false);
        service = value;
        apply();
    }
    private void apply() {
        main.removeCallbacks(heartbeat);
        if (closed) return;
        if (enabled && resumed && service != null) main.post(heartbeat);
        else send(false);
    }
    private void send(boolean grab) {
        var remote = service;
        if (remote == null || closed) return;
        String screenId = screen.get();
        if (grab) pending = true;
        calls.execute(() -> {
            try { remote.setPointerGrab(vmId, screenId, grab, token); }
            catch (Exception e) { Log.w("PointerGrab", "capture IPC failed", e); }
            finally { if (grab) main.post(() -> pending = false); }
        });
    }
    public void close() {
        if (closed) return;
        main.removeCallbacks(heartbeat);
        send(false);
        closed = true;
        calls.shutdown();
    }
}
