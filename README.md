# Perigee

A low-latency Android client for [Sunshine](https://github.com/LizardByte/Sunshine) and
[Apollo](https://github.com/ClassicOldSong/Apollo), built on top of
[Artemide](https://github.com/derflacco/moonlight-android) (DerFlacco's latency-focused fork of
[Artemis](https://github.com/ClassicOldSong/moonlight-android), itself a fork of
[Moonlight](https://github.com/moonlight-stream/moonlight-android)).

*Perigee* is the point in the Moon's orbit where it is closest to Earth — the name follows the
moon-themed lineage (Moonlight → Artemis → Artemide) and the goal of keeping the stream as close
to the host as possible.

Perigee tracks Artemide's `Artemide_Experimental_Async` branch and adds the changes listed below.
Everything Artemis and Artemide already offer (custom resolutions and bitrates, virtual display and
clipboard sync with Apollo, MediaTek RFI and low-latency decoder keys, Prefer Lower Delays / LFR,
async decoding, FSR upscaling, and so on) is unchanged.

## What Perigee adds

### MediaTek / TCL Android TVs

Developed and measured on a TCL G08 (MediaTek mt5896, Android 14). All of these are gated to
MediaTek TV hardware and are no-ops elsewhere.

- **Wi-Fi Direct latency workaround** (*Settings → on by default*). Some MediaTek TVs keep a
  background Wi-Fi Direct listen running; the radio leaves the router channel for it every
  ~1.15 s, causing an 80–100 ms gap on 5 GHz and a visible stutter plus an IDR request each time.
  Perigee calls `WifiP2pManager.stopListening()` at stream start. On the test TV the periodic
  ~52 spikes/minute went to zero over a 3-minute run (max RTT 19 ms). Side effect: the TV may not
  show up for Miracast discovery until it is used again.
- **TCL Game picture mode while streaming** (*on by default*). Switches the TV to its Game picture
  mode through TCL's own display service when a stream starts and restores it when the last
  stream ends. No root, permissions or settings writes; a persisted marker restores the mode after
  a crash.
- **MediaTek TV decoder preset.** For mt5896 the first decoder configuration uses only vendor keys
  that exist in the TV's codec and sets the operating rate from the stream frame rate instead of
  `Short.MAX_VALUE`. The existing Artemide presets remain as fallbacks.
- **Wi-Fi HAL low-latency request.** Asks the AIDL Wi-Fi HAL for low-latency mode at stream start
  (Android only does this when the chip advertises support). Best-effort; failures are logged and
  ignored.

### Rendering and pacing (all devices)

- **ADPF hint session.** Reports real per-frame work (render time plus measured decode time,
  excluding deliberate pacing sleeps) to `PerformanceHintManager` on Android 12+, so the CPU
  governor can scale for the renderer and Choreographer threads. Best-effort and a no-op where the
  vendor HAL refuses a session.
- **Live display-rate tracking.** The renderer used to sample the display refresh rate once at
  stream start. Perigee follows the display that actually hosts the stream and resets pacing when
  its rate changes, such as when a USB-C monitor is attached or removed (120 Hz ↔ 60 Hz) or the
  window moves between displays. This avoids beat patterns from stale pacing.
- **High refresh rate pinning.** For streams above 60 FPS the window keeps an explicit
  high-refresh mode request, so devices that report 120 Hz during setup and then drop to 60 Hz
  once rendering starts (or bounce during an HDR codec restart) stay at the high rate.
- **SDR window with direct HDR playback.** With direct `SurfaceView` playback the HDR video is
  composed as its own layer, so the activity window is no longer forced into HDR mode on every
  format change. The GL upscaler path keeps the existing behavior.

### Pixel / Google AV1 (opt-in experiments)

- **ADPF submission hints** (*Settings → ADPF submission hints (Experimental)*, off by default).
  For Pixel 9a with the Google AV1 decoder, replaces the rendering hints with measured app-side
  submission hints and includes the app-owned codec worker thread in Balanced mode. Measured
  submission time dropped, but no end-to-end decode latency gain has been demonstrated yet.
- **Build-time experiments** (off in release builds): AV1 operating-rate hint
  (`-Pav1OperatingRateExperiment=true`) and payload-sized AV1 block input
  (`-Pav1BlockInputExperiment=true`).

### Quiet release build

The published (`perf`) build strips app logging, `android.util.Log`, trace sections and native
logging at compile time, so a release stream does no log formatting or tracing work. The
`perfDebug` build type is a separate, debuggable package with logging kept.

## Downloads

- [GitHub Releases](https://github.com/XT-Martinez/perigee/releases). Use the `nonRoot_game`
  APK for your device ABI (`arm64-v8a` for most phones, `armeabi-v7a` for most Android TVs).
- [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.xtmartinez.perigee%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXT-Martinez%2Fperigee%22%2C%22author%22%3A%22XT-Martinez%22%2C%22name%22%3A%22Perigee%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D)

Perigee installs as its own package (`io.github.xtmartinez.perigee`), so it can sit next to
Moonlight, Artemis or Artemide. Hosts must be paired again in Perigee.

## Building

1. Install the Android SDK and NDK `27.0.12077973` (the version pinned in `app/build.gradle`).
2. `git submodule update --init --recursive`
3. Create `local.properties` with `ndk.dir=/path/to/ndk/27.0.12077973`.
4. `./gradlew assembleNonRoot_gamePerf`

To sign with your own key, copy `keystore.properties.example` to `keystore.properties` and fill
it in. Without it, the `perf` build is signed with your local debug key.

## Credits

- [DerFlacco](https://github.com/derflacco): Artemide, MediaTek decoder latency work, LFR, async
  decoding and FSR.
- [ClassicOldSong](https://github.com/ClassicOldSong): Artemis / Moonlight Noir and Apollo.
- The Moonlight authors: [Cameron Gutman](https://github.com/cgutman),
  [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer),
  [Andrew Hennessy](https://github.com/yetanothername).

Licensed under the GPLv3, like Moonlight. See [LICENSE.txt](LICENSE.txt).
