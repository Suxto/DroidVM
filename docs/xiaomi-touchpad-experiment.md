# Xiaomi keyboard-cover touchpad experiment

In the native display's floating menu, enable **Capture Xiaomi touchpad (experimental)**.
It is off by default and is not persisted across display instances. Press the tablet's
**Volume Down** button to disable capture, or turn it off in the floating menu. The
touchscreen and the physical keyboard are not grabbed by this feature.

Supported hardware is the measured virtual-HID pair: bus 6, vendor 15d9, products
00a1 (Xiaomi Touch) and 00a2 (Xiaomi Mouse). Both nodes must be exclusively grabbed;
the Mouse compatibility stream is discarded to avoid duplicate movement/clicks.
Node numbers are discovered dynamically. Existing Android pointer forwarding remains
available when capture is off. This experiment does not change the guest image.

The root daemon translates type-B multitouch into the existing relative virtio mouse:
one-finger movement, one/two-finger tap for left/right click, physical click-and-drag,
and two-finger vertical/horizontal scrolling. X/Y resolution is calibrated to the
observed 34/51 units per mm. Tap-to-drag, configurable acceleration, pinch zoom,
Windows precision-touchpad gestures, other pad models and VNC are outside this version.

The foreground UI renews a three-second lease from its main loop. Window focus loss, pause, destruction,
binder death, stopped VM, read errors and lost evdev frames release capture. An
independent rescue worker releases the hardware if the lease expires even when the
guest input writer is blocked. Device discovery retries while the foreground request
is renewed. A held physical button is released on normal teardown where the guest
input channel is still available.

## Validation

Run `./gradlew testDebugUnitTest assembleDebug`. Recorded synthetic MT frames cover
tap buttons, contact-count transitions, resolution scaling, scroll accumulation,
drag release and dropped frames. These tests do not establish physical device usability.

On hardware verify: enable capture; move across all screen edges; left/right tap;
physical click-drag; scroll in both axes; use Android task switching and return;
disconnect/reconnect the keyboard cover; stop the VM; kill or freeze the front-end;
verify Android regains the pointer within roughly 3.2 seconds on a lost heartbeat.
Ensure Volume Down and the floating menu both restore normal Android input.

The test APK uses the original package ID to access the existing daemon/data layout.
An APK signed with a different key cannot update the official installation in place.
Do not uninstall the existing app to bypass a signature error: that can delete VM
configuration and private data. Back up and plan signing/migration before device install.
