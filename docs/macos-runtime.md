# macOS crosvm runtime

A VM can select the macOS crosvm build with `"crosvm_runtime": "macos"` in its configuration. Provision the Android crosvm executable as `usr/bin/crosvm-macos` under the app's data directory. The launcher resolves the runtime by name to a fixed path. Existing configurations use `usr/bin/crosvm`.

The macOS runtime needs a compatible firmware image, boot image, and APFS data store. Its boot image must use the ADT derived from the FDT of the app launch configuration. Device placement can change when the app enables input, storage, and display devices.

Use the App's VNC screen exporter when the macOS crosvm build has VNC support. The macOS runtime executable and guest images are provisioned separately from the APK.
