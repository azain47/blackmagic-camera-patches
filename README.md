# Blackmagic Camera patches

A small [Morphe](https://github.com/MorpheApp/morphe-patcher) / ReVanced-style patch
bundle for the Android **Blackmagic Camera** app, built from a field investigation into
random recording freezes on a Galaxy S23 Ultra. The patches are fingerprint-based, so
they are meant to survive app updates rather than being pinned to one build.

> **Personal-use tool.** Blackmagic Camera is a free app with no in-app purchases. These
> patches modify a copy of the app **you already installed**, on **your own device**, to fix
> performance problems and expose a usable bitrate range. They are **not** a redistribution
> of the app: you must supply your own APK (see *Usage*). Nothing here unlocks a paid
> feature — the app has none.

## What's included

| Patch | Default | What it does |
|-------|---------|--------------|
| **Disable license check** | on | Neutralizes PairIP's `LicenseClient.checkLicense`, which otherwise force-quits any re-signed (i.e. patched) build ~1 s after launch. Required for any patched build to run. |
| **Configurable bitrate tiers** | on | Rescales the LOW / MEDIUM / HIGH video bitrate tiers to **25% / 50% / 75% of the encoder's maximum** (MAX unchanged). On a flagship at 1080p30 H.265 this turns the stock **6 / 9 / 12 / 160 Mbps** options into usable **~40 / 80 / 120 / 160 Mbps** steps, removing the order-of-magnitude gap between HIGH and MAX. |

Both patches are verified end-to-end (compiled → applied with `morphe-desktop` → confirmed
in the output dex). See [`docs/BLACKMAGIC_REPORT.md`](docs/BLACKMAGIC_REPORT.md) for the full
investigation, including the memory/freeze root-cause analysis and the gallery-performance fix
that is still being ported to a fingerprint-based patch (see *Roadmap*).

## Why

The stock app at 1080p offers no middle-ground bitrate: the gap from HIGH (12 Mbps) to MAX
(160 Mbps, the hardware ceiling) is more than 10×. The bitrate patch fixes exactly that.
The license-check patch is only here because, like all PairIP-protected apps, a re-signed
build won't start without it.

## Build

Requirements: JDK 21+, a `kotlinc` on `PATH`, and the `morphe-desktop-*-all.jar`
([releases](https://github.com/MorpheApp/morphe-desktop/releases)).

```bash
MORPHE_JAR=/path/to/morphe-desktop-1.19.1-all.jar ./build.sh
# -> build/blackmagic-camera-patches.mpp
```

The patches are compiled directly against the patcher API inside the morphe-desktop jar, so
no Gradle or GitHub Packages authentication is needed.

## Usage

1. Obtain the genuine app. Blackmagic Camera ships as a **split APK bundle**. Pull the
   installed splits from your device, or download the bundle from a trusted mirror:
   ```bash
   adb shell pm path com.blackmagicdesign.android.blackmagiccam
   adb pull <base.apk path>           # and the split_config.* apks
   ```
2. Patch `base.apk` (all patched code lives in the base module):
   ```bash
   java -jar morphe-desktop-*-all.jar patch \
     --patches=build/blackmagic-camera-patches.mpp \
     --keystore=my.keystore --keystore-password=... \
     --keystore-entry-alias=... --keystore-entry-password=... \
     -o base-patched.apk base.apk
   ```
   Or use the **Morphe / ReVanced Manager** app on-device and point it at this bundle.
3. Install the patched `base-patched.apk` together with the **unmodified** `split_config.*`
   APKs, using a split-APK installer (e.g. SAI) or:
   ```bash
   adb install-multiple base-patched.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk
   ```
   Because the build is re-signed, you must uninstall the Play Store version first (this
   wipes the app's settings/presets/LUTs; your recorded videos in DCIM are untouched).

> Patching re-signs the app with your own key, so Play Store auto-updates stop. Re-patch
> new versions yourself.

## Compatibility

Developed and verified against **v3.5.0.0066** (`com.blackmagicdesign.android.blackmagiccam`).
The fingerprints avoid obfuscated names and anchor on stable types (e.g. the non-obfuscated
`BitrateLevel` enum and the `com.pairip.licensecheck` package), so they should apply to
nearby versions. Use `-f` / Manager's "suggested version" override to try other builds; a
fingerprint that no longer matches fails loudly instead of corrupting the app.

## Roadmap

The investigation also produced on-device-verified fixes for the **freezes** (the memory
pressure from the in-app gallery, which could trigger the low-memory killer mid-recording):

- **Smooth gallery thumbnails** — the grid thumbnail fetcher spins up a full ExoPlayer + GL
  readback pipeline per cell; replacing it with `MediaMetadataRetriever.getScaledFrameAtTime`
  cut browsing-time graphics memory from ~770 MB peak to ~105 MB and player instantiations
  from 121 to ~12 per session.
- Player release on gallery exit, placeholder-bitmap cache cap, muxer sample-window trim.

These are documented with exact bytecode in [`docs/BLACKMAGIC_REPORT.md`](docs/BLACKMAGIC_REPORT.md)
and are being ported from proven raw-smali edits to fingerprint-based patches for inclusion here.

## Credits & reporting

Built on [Morphe Patcher](https://github.com/MorpheApp/morphe-patcher) (itself based on
[ReVanced](https://github.com/ReVanced/revanced-patcher)). The findings in
[`docs/BLACKMAGIC_REPORT.md`](docs/BLACKMAGIC_REPORT.md) are intended to be shared upstream
with Blackmagic Design so the issues can be fixed in the real app.

## License

[MIT](LICENSE). This repository contains only original patch code and analysis; it does not
contain or distribute any part of the Blackmagic Camera app.
