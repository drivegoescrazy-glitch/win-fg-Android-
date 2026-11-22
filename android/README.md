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

Without root the app still works as a settings editor + launcher + exporter; the
layer must be loaded by a host (e.g. Winlator/Bannerlator) that already consumes
win-fg. The system-wide install is the only part that needs root.

## Build

```bash
# toolchain in .toolchain/ (JDK 17, SDK 35, build-tools 35, NDK r27d, cmake, Vulkan-Headers)
bash android/build_apk.sh
```

Output: `android/dist/win-fg-v1.0.apk` (versionCode 1), arm64-v8a engine, signed.
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

## Notes

- Min SDK 26 (Android 8.0), target SDK 35.
- The launcher lists only apps exposing a `MAIN/LAUNCHER` activity; it does not
  request `QUERY_ALL_PACKAGES`.
- System layer install creates `/data/adb/modules/winfg`; reboot required. Removal
  also needs a reboot to unload the layer.
