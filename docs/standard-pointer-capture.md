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

References:
- https://developer.android.com/reference/android/view/View#requestPointerCapture()
- https://developer.android.com/reference/android/view/InputDevice#SOURCE_TOUCHPAD
- https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android12-release/services/inputflinger/docs/pointer_capture.md
