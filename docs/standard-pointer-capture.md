# Standard pointer capture

The native display page uses Android's public `View.requestPointerCapture()` API.
Only the display page owns capture. There is no root pointer helper, evdev device
selection, heartbeat socket, or vendor/product matching. The daemon's existing
VM input transport and physical-keyboard handling are independent of this feature.

## Input path

`CapturedPointerLayout` -> `StandardPointerCapture` -> `PointerEventQueue`
-> `EvdevEncoder` -> existing `InputSink` -> VM relative mouse socket.

Android versions that provide `SOURCE_MOUSE_RELATIVE` touchpad gestures keep the
system's movement, tap, drag, and gesture recognition. `InputDevice` capabilities
identify a touchpad even when its event source is relative mouse. Regular mouse
wheels do not inherit the app's touchpad scrolling preference. The system natural
scroll setting is read when capture is acquired, outside the event hot path.

Older systems may capture `SOURCE_TOUCHPAD` contacts. These go directly to
`TouchpadGestureRecognizer` as a stable pointer-ID mask and a centroid measured
in millimetres. No intermediate Linux input events or per-sample arrays are built.
Driver axis resolution determines physical scale; if missing, a nominal 100x60 mm
pad is assumed, so sensitivity on such devices may need future calibration.

## Ownership and ordering

- Enable capture from the display menu; Volume Down releases it.
- Capture requires a resumed, focused page with an input sink and a focused view.
- Requests are posted after focus/menu callbacks, without a fixed handover delay.
- Focus loss, pause, cancellation, disconnect, sink replacement, and closing the
  page release held buttons. Shutdown drains releases to the previous sink.
- Each input device has independent fractional movement, wheel, hover, and gesture
  state. Guest buttons are the union of all devices' held buttons.
- One pointer worker sends frames. Only adjacent motion/wheel frames coalesce;
  they cannot move across button transitions. A blocked transport therefore does
  not move pre-press motion into a drag or post-release motion back into it.

Pure JVM regression tests cover raw gestures, multi-device button ownership,
coalescing, press/release ordering, transport failure, and draining on close.
Android device validation additionally needs focus acquisition/recovery, real
captured MotionEvents, hotplug, and VM-side receipt of button/wheel frames.

The standalone adapter probe is reproducible with an Android SDK platform jar,
the build tools' D8 jar, a JDK, and an adb-connected device with a touchpad:

```sh
python scripts/validation/check_standard_capture.py --android-jar /sdk/platforms/android-35/android.jar --d8-jar /sdk/build-tools/35.0.0/lib/d8.jar
```

It runs production capture/encoding code with a recording sink in a separate
Android process. It does not request capture or inject input into the running VM.
Only the unrelated `InputForwarder` interface holder, channel constant holder, and
compile-time annotations are stubbed. This checks adapter behavior, not window
focus routing; actual capture and VM delivery require the hardware checks below.

## Verified build: 382caeb

- 28 JVM regression tests passed; complete Android unit tests passed in CI.
- Debug and Release APK builds passed; the signed debug APK was installed over
  the previous build with the persistent fork signing key.
- 12 on-device MotionEvent adapter checks passed, including history, fractional
  wheel isolation, raw contact index changes, cancellation, and separate mouse
  and touchpad wheel direction.
- Simulated evdev input on the tablet's physical touchpad node traversed Android
  capture and reached the VM mouse socket: movement, left tap, two-finger right
  tap, drag press/motion/release ordering, and scrolling. Toggling natural scroll
  reversed the delivered wheel sign for the same simulated finger movement.
- Backgrounding released capture; returning reacquired it. Volume Down released
  capture, and the menu could enable it again. The backend selector was absent.
- APK inspection confirmed no pointergrab helper. No pointergrab process ran.
  The VM stayed running through the update; crosvm and libvirglrenderer hashes
  matched their pre-update values.

Build: https://github.com/Suxto/DroidVM/actions/runs/42491641151

The repository's pre-existing static-check failures remain (ASCII, translation,
string-concatenation, and parameter-count checks). The capture changes added no
new diagnostics in these checks. Other brands, real hotplug, and physical gesture
feel have not been validated by this regression run.

References:
- https://developer.android.com/reference/android/view/View#requestPointerCapture()
- https://developer.android.com/reference/android/view/InputDevice#SOURCE_TOUCHPAD
- https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android12-release/services/inputflinger/docs/pointer_capture.md
