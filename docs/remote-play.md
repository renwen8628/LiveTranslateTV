# PS5 Remote Play capture

LiveTranslateTV reads the PS5 picture over the local network using PlayStation Remote Play,
because the TV does not let apps capture HDMI video or audio (see
[audio-capture-feasibility.md](audio-capture-feasibility.md)). The TV keeps showing the PS5
on HDMI as usual; the Remote Play stream is decoded off-screen and only used to read
subtitles.

Remote Play support comes from [chiaki-ng](https://github.com/streetpea/chiaki-ng), an
open-source client, included as a git submodule (`third_party/chiaki-ng`, pinned to
`c8bea26`). Its protocol is reverse-engineered, not an official Sony API, so a PS5 firmware
update could break it.

## License

chiaki-ng is **AGPL-3.0** (with an OpenSSL exception). An app that includes it must also be
distributed under AGPL-3.0, including source code for anyone who receives the app. Copied
files keep their SPDX headers:

- `app/src/main/cpp/chiaki-jni/*` — chiaki-ng's Android JNI glue (audio playback removed;
  one deadlock fix in `video-decoder.c`)
- `app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt` — its Kotlin wrapper (package name must
  stay the same because the JNI functions are bound to it)

## Building

One-time setup (fetches the submodule and a Python environment for chiaki-ng's code
generator):

```sh
./tools/setup-native-build.sh
./gradlew assembleDebug
```

Needs NDK `27.1.12297006` and CMake `3.22.1` (Android SDK manager). Only `armeabi-v7a` is
built because the TCL TV runs a 32-bit userspace. The first build also downloads and builds
OpenSSL, json-c and miniupnpc.

## Using it

On the settings screen:

- **PS5: not paired** → opens the pairing screen. It finds the PS5 on the network; enter your
  PSN account ID (base64, remembered for next time) and the 8-digit code from
  **PS5 Settings → System → Remote Play → Link Device**.
- **PS5 translation: ON/OFF** — one switch for everything: subtitle overlay, Remote Play, OCR,
  and the translation server (if a server URL is set). The choice is saved, so translation
  resumes whenever the app is opened.

On the TCL TV the firmware blocks automatic service restarts (`[TclAppBoot]:forbid restart
service`), so if Android kills the app in the background, translation stays off until the
app is opened again.

## Pairing over ADB (alternative)

1. On the PS5: **Settings → System → Remote Play → Link Device** shows an 8-digit code.
2. Send it to the app with your PSN account ID (base64):

```sh
adb shell "am startservice -n com.livetranslate.tv/.SubtitleOverlayService \
  -a com.livetranslate.tv.action.RP_PAIR --es psn_account_id '<base64 id>' --es pin <code>"
```

The PS5 is found automatically (add `--es host <ip>` to skip discovery). The pairing keys are
kept in private app storage and excluded from backups.

## Running

Use the "PS5 translation" switch, or over ADB (this also saves the on/off setting):

```sh
adb shell "am startservice -n com.livetranslate.tv/.SubtitleOverlayService -a com.livetranslate.tv.action.RP_START"
# add --ez save_frames true to save one grayscale frame per second to files/rp_frames/
adb shell "am startservice -n com.livetranslate.tv/.SubtitleOverlayService -a com.livetranslate.tv.action.RP_STOP"
```

Measured on the TCL 65Q77K: 1280×720 H.264 at about 30 fps, about 12% of one CPU core while
streaming (without debug frame saving).

## Known limitations

- The PS5 allows one Remote Play connection at a time. Close Lensor or other Remote Play apps
  first. If the app is killed without stopping, the PS5 can report "already in use" for a
  minute or more until it times out.
- A Remote Play login PIN (if set on the PS5) is not supported yet.
- Waking the PS5 from rest mode is not implemented yet.
