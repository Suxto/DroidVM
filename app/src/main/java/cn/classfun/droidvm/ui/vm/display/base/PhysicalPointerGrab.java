// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.classfun.droidvm.daemon.input.XiaomiTouchpad;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;

/** Foreground-only lease to a tiny root helper; independent of the daemon implementation. */
public final class PhysicalPointerGrab {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService heartbeats = Executors.newSingleThreadExecutor();
    private InputForwarder.InputSink sink;
    private Session session;
    private boolean enabled, resumed, focused, closed, heartbeatPending;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            var current = session;
            if (closed || current == null) return;
            if (current.socket != null && !heartbeatPending) {
                heartbeatPending = true;
                heartbeats.execute(() -> {
                    try {
                        var socket = current.socket;
                        if (!current.cancelled && socket != null) socket.getOutputStream().write('H');
                    } catch (Exception e) { current.stop(); }
                    finally { main.post(() -> heartbeatPending = false); }
                });
            }
            main.postDelayed(this, 750);
        }
    };

    public PhysicalPointerGrab(Context context) { this.context = context.getApplicationContext(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; apply(); }
    public void setResumed(boolean value) { resumed = value; apply(); }
    public void setFocused(boolean value) { focused = value; apply(); }
    public void setSink(InputForwarder.InputSink value) {
        if (sink != value) stop();
        sink = value;
        apply();
    }
    private void apply() {
        if (closed) return;
        if (enabled && resumed && focused && sink != null) {
            if (session == null) {
                session = new Session(sink);
                new Thread(session, "pointer-grab-reader").start();
                main.post(heartbeat);
            }
        } else stop();
    }
    private void stop() {
        main.removeCallbacks(heartbeat);
        if (session != null) session.stop();
        session = null;
    }
    public void close() {
        stop(); closed = true; heartbeats.shutdown();
    }

    private final class Session implements Runnable {
        private final InputForwarder.InputSink target;
        private volatile boolean cancelled;
        private volatile LocalSocket socket;
        private LocalServerSocket server;
        private Process process;
        Session(InputForwarder.InputSink target) { this.target = target; }
        synchronized void stop() {
            cancelled = true;
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
            try { if (server != null) server.close(); } catch (Exception ignored) { }
            if (process != null) process.destroy();
        }
        private void write(byte[] bytes) {
            if (bytes != null && !target.write(2, bytes))
                throw new IllegalStateException("guest mouse channel unavailable");
        }
        public void run() {
            String name = UUID.randomUUID().toString();
            File executable = new File(context.getCacheDir(), "pointergrab-".concat(name));
            var pad = new XiaomiTouchpad(new XiaomiTouchpad.Output() {
                public void move(int x, int y) { write(EvdevEncoder.encodeMouseMove(x, y)); }
                public void button(int code, boolean down) {
                    write(EvdevEncoder.encodeMouseButton((short) code, down));
                }
                public void scroll(int v, int h) { write(EvdevEncoder.encodeMouseWheel(v, h)); }
            });
            try {
                String asset = String.format("bin/%s/pointergrab", Build.SUPPORTED_ABIS[0]);
                try (InputStream in = context.getAssets().open(asset)) {
                    Files.copy(in, executable.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (!executable.setExecutable(true, true)) throw new IllegalStateException("chmod helper failed");
                synchronized (this) {
                    if (cancelled) return;
                    server = new LocalServerSocket(name);
                    String command = String.format("'%s' %s %d", executable.getAbsolutePath(), name,
                        android.os.Process.myUid());
                    process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                }
                main.postDelayed(() -> {
                    if (!cancelled && socket == null) stop();
                }, 20000);
                LocalSocket connection = server.accept();
                synchronized (this) {
                    if (cancelled) { connection.close(); return; }
                    socket = connection;
                }
                if (connection.getPeerCredentials().getUid() != 0)
                    throw new SecurityException("helper peer must be root");
                byte[] event = new byte[24];
                var bytes = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN);
                InputStream input = connection.getInputStream();
                while (!cancelled) {
                    int offset = 0;
                    while (offset < event.length) {
                        int n = input.read(event, offset, event.length - offset);
                        if (n < 0) throw new java.io.EOFException();
                        offset += n;
                    }
                    long time = bytes.getLong(0) * 1000 + bytes.getLong(8) / 1000;
                    pad.event(bytes.getShort(16) & 0xffff, bytes.getShort(18) & 0xffff,
                        bytes.getInt(20), time);
                }
            } catch (Exception e) {
                if (!cancelled) Log.w("PointerGrab", "capture ended", e);
            } finally {
                stop(); // Release hardware before trying to send a guest button-up.
                executable.delete();
                main.post(() -> {
                    if (session == this) { PhysicalPointerGrab.this.stop(); enabled = false; }
                });
                try { pad.reset(); } catch (Exception ignored) { }
            }
        }
    }
}
