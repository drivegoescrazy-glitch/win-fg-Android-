# Win-FG Android launcher

A companion APK for Win-FG Native: edit the engine profile, launch games from a
library, and optionally install the bundled Vulkan layer system-wide on a rooted
(Magisk/KernelSU) device.

## What it does

- **Games** — lists every launchable app, games first. Tapping one writes the
  current win-fg profile and launches it.
- **Frame gen** — optical-flow model (symmetric vs bidirectional), frame increase
  (2×/3×/4×), performance preset, flow scale, C1 global motion, C2 TV-L1
  regularization and its iterations/lambda/dt/edge/eps, even pacing, extra
  swapchain images, debug trace.
- **Engine** — extracts the bundled `libwin_fg.so` + layer manifest, installs a
  Magisk module for system-wide Vulkan loading (root), exports the layer and
  `conf.toml` to a host folder (set `WIN_FG_ENABLE=1` there), shows the
  update-key fingerprint, and links to releases.

## How the profile reaches the layer

The launcher writes a `conf.toml` for hosts and for layer deployments. More
importantly, when the system layer is installed it mirrors every setting into
`winfg.*` Android system properties and bumps `winfg.rev`. The layer reads those
properties at device init and re-reads them live when the revision changes, so
launching a game applies the selected profile even though another app cannot
write into that game's private files.

Without root the app can also run a MediaProjection overlay: Android 12+ can
capture one app, and the overlay excludes itself with SurfaceFlinger's
skip-screenshot flag so the generated frames do not feed back into capture.
Capture buffers request `USAGE_GPU_SAMPLED_IMAGE | USAGE_GPU_COLOR_OUTPUT`:
Vulkan may only import an `AHardwareBuffer` that carries a GPU usage flag, and
a CPU-only buffer faults inside the GPU driver rather than failing cleanly.
Android 12/13 has no public single-app capture, so the overlay uses a non-SDK
lookup exempted at runtime; no root or LSPosed install is required. The app
handles frame generation itself when the overlay is active. The system-wide
Vulkan layer install is still the only part that needs root.

Secure/DRM windows (`FLAG_SECURE`) cannot be captured by MediaProjection, with
or without root; those games render black and need a rooted capture path.
Hardware H.264 decoding plus GPU optical flow is required for usable latency.

## Build

```bash
# toolchain in .toolchain/ (JDK 17, SDK 35, build-tools 35, NDK r27d, cmake, Vulkan-Headers)
bash android/build_apk.sh
```

Output: `android/dist/win-fg-v1.14.apk` (versionCode 15), arm64-v8a engine, signed.
The script is also what CI runs on tags.

## Release key — do not lose it

`android/keys/winfg-release.jks` (alias `winfg`) with its credentials in
`android/keys/keystore.properties` signs every build. Android only accepts an
update over an installed app when the certificate matches, so every future APK
must be signed with this same key. Bump `VERSION_CODE` in
`src/com/winfg/launcher/AppInfo.java` for each release, rebuild, and install the
new APK over the old one — no uninstall needed.

Because the key is committed for update continuity, treat it as a project-shared
release key, not a secret for defending a high-value app store identity.

## Frame synthesis back ends

The engine estimates dense optical flow and then synthesises the in-between
frame at alpha = 0.5. Two synthesis back ends exist, selectable live in
**Settings → Frame generation → Frame synthesis** (or `synth = interpolate|reproject`
in `conf.toml`, `WIN_FG_SYNTH`, system property `winfg.synth`):

- **Interpolate** (`wfg_synth`) — softmax importance blend of both warped frames
  (photometric residual + forward/backward consistency). The long-standing default.
- **Reproject** (`wfg_reproject`) — occlusion-adaptive: per pixel it uses the blend
  only where the field is coherent *and* the two warped colours agree, otherwise it
  reprojects the temporally nearer real frame, which cannot double an edge on a
  disocclusion or a bad vector. Falls back to the unwarped nearest frame when the
  field has no usable confidence.

Both declare the same descriptors, so switching is a pipeline swap with no change
to flow estimation or to the present path.

## Crash reports

A fault on a device with no adb used to be a screenshot of a dialog. Every crash
(uncaught Java exception or native renderer signal) is now saved as a report under
`files/win-fg/crash-reports/` with the launcher build, device, Android version, ABI,
display, overlay mode and frame-gen profile. Reports are deduplicated by the structure
of their trace, so a repeated fault counts up (`×3`) instead of filing duplicates.

**Engine → CRASH REPORTS → SEND CRASH REPORT** opens a prefilled GitHub issue on
`drivegoescrazy-glitch/win-fg-Android-` with the title and body already filled in. The
same offer appears in the native-crash dialog and as a prompt on the next launch. Filing
goes through the user's own browser session: the APK carries no GitHub token, and no token
is ever stored on the device. `Copy latest` puts the full report on the clipboard for a
manual paste.

## Notes

- Min SDK 26 (Android 8.0), target SDK 35.
- The launcher lists only apps exposing a `MAIN/LAUNCHER` activity; it does not
  request `QUERY_ALL_PACKAGES`.
- System layer install creates `/data/adb/modules/winfg`; reboot required. Removal
  also needs a reboot to unload the layer.
