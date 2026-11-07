# Xiaomi keyboard-cover touchpad experiment

In the native display's floating menu, enable **Capture mouse / touchpad (experimental)**.
Capture is off by default and is not persisted across display instances. Press the tablet's
**Volume Down** button to disable capture, or turn it off in the floating menu. The
screen's touch input and physical-keyboard capture remain separate.

Select one of two persisted backends:

* **Standard capture (no root)** is the default. It uses Android's public
  `requestPointerCapture()` API, with a root layout that intercepts captured events
  before the focused IME. Relative mouse events and raw touchpad contacts are routed
  separately; only touchpad scrolling observes the natural-scroll setting. Both feed
  the existing relative virtual mouse. This input path never starts the root helper.
* **Root exclusive capture (Xiaomi pad)** keeps the original experiment. A tiny root
  helper exclusively grabs the measured virtual-HID pair (bus 6, vendor 15d9, products
  00a1 / 00a2), streams raw events through an authenticated local socket, and discards
  the Mouse compatibility stream. Both nodes must be grabbed. Device numbers are dynamic.

Switching releases the old backend before starting the other. Window focus loss,
pause, display-binder loss and destruction release input; held buttons are released.
The root helper additionally has a three-second main-loop heartbeat lease and uses
nonblocking socket sends so a stalled app or guest writer cannot trap the hardware.
The virtual-machine daemon still uses root as before, whichever input backend is selected.

Raw captured contacts are translated into one-finger movement, one/two-finger taps,
physical click-drag and two-finger scrolling. Android pointer IDs, pointer-up samples,
contact-count transitions and historical samples are handled explicitly. Pinch zoom,
Windows precision-touchpad gestures and VNC capture are outside this experiment.

This branch is based on cbe66e1, the version installed on the tablet. It deliberately
leaves the native daemon submodule and its Binder API unchanged.

The floating menu also offers **Natural touchpad scrolling**, enabled by default.
It reverses vertical and horizontal two-finger scrolling for this captured pad only.
The setting is remembered; changing it restarts capture to clear accumulated deltas.
Relative mouse wheel events keep their normal direction, including in standard capture.

## Validation

Run `./gradlew testDebugUnitTest assembleDebug`. Recorded synthetic MT frames cover
tap buttons, contact-count transitions, resolution scaling, scroll accumulation,
drag release and dropped frames. These tests do not establish physical device usability.

On hardware verify: enable capture; move across all screen edges; left/right tap;
physical click-drag; scroll in both axes; use Android task switching and return;
disconnect/reconnect the keyboard cover; stop the VM; kill or freeze the front-end;
verify Android regains the pointer within roughly 3.2 seconds on a lost heartbeat.
Ensure Volume Down and the floating menu both restore normal Android input.
In standard mode, check `dumpsys input` / the StandardPointerCapture log for acquired
Android capture and confirm no pointergrab process is running. In root mode verify
Android capture is off and the helper holds event9/event8. Switch in both directions,
including while the IME is focused; verify no duplicate clicks or stuck buttons.

The test APK uses the original package ID to access the existing daemon/data layout.
An APK signed with a different key cannot update the official installation in place.
Do not uninstall the existing app to bypass a signature error: that can delete VM
configuration and private data. Back up and plan signing/migration before device install.
