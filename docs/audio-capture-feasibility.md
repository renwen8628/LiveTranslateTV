# Milestone 4 — Audio capture feasibility (TCL 65Q77K)

Tested 2026-10-20 on the target TV with the app's built-in audio capture test
(`AudioProbeService`) plus read-only `adb shell dumpsys` queries. Public Android APIs only:
no root, no hidden or OEM APIs, no system permissions, no HDCP circumvention.

Device: `TCL/G08_4K_US/G08:14/UTT2.250416.001/AU16` — Android 14, MediaTek `mt5896`.
PS5 is on HDMI 1 (`com.tcl.tvinput/.TvPassThroughService/HW15`, CEC "PlayStation 5").

## Summary

| Question | Answer | Evidence level |
|---|---|---|
| Can a normal app capture HDMI (PS5) audio on this TV? | **No** | Verified* |
| Can it record the TV's built-in microphone? | Yes, but **only while the app is on screen** | Verified |
| Can it record the mic while HDMI is showing? | **No — Android silences it** | Verified |
| Can it capture audio of other Google TV apps (YouTube etc.)? | Probably, via `AudioPlaybackCapture` | Mechanism verified with our own audio; third-party apps not tested |
| Can the app hold a foreground service? | **Not reliably** — TCL firmware blocks it for this sideloaded app | Verified (logs) |

\* Verified as far as possible without listening in the room: all three independent signals agree (below). Re-running the
playback test while PS5 audio is clearly audible would close the remaining gap.

## Findings

### 1. HDMI audio is not exposed to Android apps

- **No HDMI input audio device.** Android lists only: built-in mic, remote submix, loopback, and
  "AO Input" (proxy). There is no `AUDIO_DEVICE_IN_HDMI`. Loopback, proxy and echo-reference
  require `CAPTURE_AUDIO_OUTPUT`, a system-only permission.
- **TV input framework gives no audio route.** Every HDMI port reports
  `TvInputHardwareInfo{type=9, audio_type=0, audio_addr=null}`.
- **Hardware-only audio path.** Switching to HDMI 1 creates an audio-flinger patch
  `"No software bridge"` straight to the speaker (device type `0x2`). The audio never passes
  through Android's software mixer, which is the only thing `AudioPlaybackCapture` can tap.
- **Measured:** with screen-capture consent granted and the TV on the PS5 input,
  `AudioPlaybackCapture` (usages MEDIA, GAME, UNKNOWN) returned **8 s of digital silence**
  (`-inf dBFS`). In the same session it captured our own 1 kHz test tone cleanly at −15 dBFS,
  so the capture path itself worked.

Conclusion: on this TV, PS5/HDMI audio can only be heard acoustically, not captured digitally
by an app.

### 2. Built-in microphone: works, but only in the foreground

The TV has a far-field mic ("Built-In Mic", `AUDIO_DEVICE_IN_BUILTIN_MIC`, 16/48 kHz),
although it does not declare `android.hardware.microphone`.

With the app on screen (TV volume 10/100), all public sources recorded:

| Source | Quiet room | With 1 kHz tone from TV speakers | Tone vs neighbouring bins |
|---|---|---|---|
| MIC | −86 dBFS | −71 dBFS | +62 dB |
| VOICE_RECOGNITION | −85 dBFS | −71 dBFS | +59 dB |
| UNPROCESSED | −82 dBFS | −71 dBFS | +58 dB |
| CAMCORDER | −82 dBFS | −71 dBFS | +62 dB |
| VOICE_COMMUNICATION | −81 dBFS | −71 dBFS | +57 dB |

So the mic does hear the TV's own speakers, and no source cancels it (no effective AEC). The
level is low and would need gain; speech-recognition quality from it is **not yet tested**.

But after switching to the PS5 input, **every source was silenced** (`isClientSilenced=true`,
all-zero audio). Android 11+ only lets an app record in the background from a foreground
service of type `microphone`. The process was at `IMPORTANT_FOREGROUND` (kept alive by the
visible overlay) with **no capabilities** (`curCapability=------`), so mic access was denied.

### 3. TCL blocks foreground services for this app

The TCL firmware ("TclAppBoot") refuses `startForeground()` for this sideloaded app:

```
W ActivityManager: [TclAppBoot]:forbid startForeground for ServiceRecord{… com.livetranslate.tv/.AudioProbeService}
I TclAppBoot: … calleePkgName = 'com.livetranslate.tv', bootType = 'start_foreground', isAutoStart = 'true', isAllow = 'false', reason = 'default_borbid'
```

- It happened for the `microphone` probe service **and** for `SubtitleOverlayService`
  (`specialUse`) whenever the app itself started them.
- It did **not** happen when the overlay service was started from `adb shell`, and no block was
  logged for the `mediaProjection` service. The rule behind this is unknown; do not rely on it.
- The call fails silently (no exception), so the service keeps running as a normal background
  service.
- No user setting or documented allowlist was found. Bypassing it would need OEM/hidden APIs,
  which the project rules exclude. Whether Play Store installs are treated differently is
  **not verified**.

**Impact on Milestone 1:** the overlay still works (you confirmed it over the PS5) because a
process with a visible overlay window is ranked *perceptible* (`oom adj 200`,
`hasOverlayUi=true`). It is therefore unlikely to be killed, but it is not a protected
foreground service on this TV. The `START_STICKY` restart and the "subtitles were on" setting
remain the recovery path.

### 4. Other Google TV apps' audio (not HDMI)

`AudioPlaybackCapture` kept working in the background with the TV on HDMI, and the
consent dialog ("Start recording or casting with LiveTranslateTV?") is available on Google TV.
It can therefore capture streaming apps that allow capture. Apps can opt out
(`ALLOW_CAPTURE_BY_NONE`), and DRM services commonly do. This was **only tested with our own
app's audio**; which real apps allow it is not verified. Consent is needed again after each
reboot or session end.

## What this means for the project

| Audio source | For PS5 / HDMI | For Google TV apps | Status |
|---|---|---|---|
| TV: HDMI capture | ✗ Not possible | — | Ruled out |
| TV: built-in mic | ✗ Silenced in background | ✗ Same | Ruled out unless TCL allows a mic foreground service |
| TV: app playback capture | ✗ | ✓ Likely, per-app | Possible on-device option for streaming apps |
| Smartphone mic near the TV | ✓ Hears the speakers | ✓ | Needs a phone client (browser page or app) |
| PC / external capture | ✓ e.g. PS Remote Play audio, or line-out/eARC to a USB interface | ✓ | Needs a PC client (e.g. ffmpeg/sounddevice) |

For the PS5, audio has to come from **outside the TV**. The planned architecture already supports this:
any audio source streams to the backend, and the backend sends subtitles to the TV over the
existing WebSocket. The TV app does not need to change.

## Reproducing

```sh
adb shell pm grant com.livetranslate.tv android.permission.RECORD_AUDIO
# Mic test (15 s countdown: switch to the HDMI input during it to test background capture)
adb shell "am start --activity-single-top -n com.livetranslate.tv/.MainActivity --es audio_probe mic"
# App playback capture test (accept the consent dialog on the TV)
adb shell "am start --activity-single-top -n com.livetranslate.tv/.MainActivity --es audio_probe playback"
# Results
adb logcat -s AudioProbe
adb exec-out run-as com.livetranslate.tv cat files/audio_probe/mic_MIC_quiet.wav > mic_MIC_quiet.wav
python3 tools/analyze_probe_wav.py .
```

The same tests are on the settings screen ("Audio capture test: …").
