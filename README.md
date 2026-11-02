# Open MK3

**Open MK3 turns an Android phone and a Maschine MK3 controller into a standalone
groovebox.** You play everything on the controller: pads, buttons, knobs, touch strip and both
colour screens. The phone stays in your pocket with its screen off and powers the controller
over USB.

> Works with the Maschine MK3 controller. Not affiliated with or endorsed by Native Instruments.

Open MK3 is an independent, open-source project. It doesn't use any of the original
software, sounds or artwork. The workflow follows the familiar conventions of the hardware
(groups, sounds, patterns, pad modes, the SHIFT functions printed on the controller), so
existing users feel at home. The implementation, look and sound content are all its own.

## Two apps

| App | What it is |
|---|---|
| **Open MK3** | The full groovebox. It grows towards complete coverage of the controller's workflow (see [docs/controls.md](docs/controls.md)). |
| **Open MK3 Vanilla** | Only the essentials, small and readable: a starting point if you want to build your own thing. |

Both are built from the same shared core (`core/`) and can be installed side by side.

## What works today

- **Direct USB audio to the controller's outputs.**
  - Its own isochronous driver, locked to the controller's clock (no drift).
  - About 8–10 ms of buffer, adaptive.
  - Runs with the phone screen off.
- **All controls:** pads with pressure, buttons, knobs (with touch), 4D encoder, touch strip.
- **Both screens**, drawn with Android's Canvas and updated with changed rectangles only.
- **LEDs:** button lights and RGB pad and group colours.
- **16 pads, 16-step sequencer:**
  - step mode and live recording with latency compensation
  - mute, solo and erase
  - swing
  - the pattern is saved automatically
- **Sample browser on the controller:**
  - WAV 8/16/24/32-bit or float, any sample rate (resampled to 48 kHz)
  - preview, then load onto any pad
  - the kit is saved
- **Built-in generated drum kit**, so it makes sound out of the box.
- **Fallback audio path** through Android's AAudio, if direct USB isn't available.

Planned work is tracked in [docs/controls.md](docs/controls.md) and [docs/roadmap.md](docs/roadmap.md).

## Requirements

- Android 13 or newer (API 33), 64-bit ARM.
- A USB-C OTG adapter and the controller's own USB cable. The controller runs on the phone's
  USB power.
- A Maschine MK3 controller. Other models aren't supported.

## Install

Signed APKs are published on the [Releases](../../releases) page.
1. Install **Open MK3** or **Open MK3 Vanilla**.
2. Plug in the controller.
3. When Android asks which app should handle it, choose Open MK3 and tick *always*.
4. Allow the permissions it asks for: notifications, microphone (for sampling) and "Music and audio" (for your WAV files).

For best results, set the app's battery usage to **Unrestricted** in Android settings, so it
keeps running with the screen off.

Put your WAV samples in `Download/OpenMK3/` (subfolders are fine). Press BROWSER on the
controller to load them.

## Building

```
./gradlew :full:assembleDebug        # Open MK3
./gradlew :vanilla:assembleDebug     # Open MK3 Vanilla
```

You need the Android SDK with NDK 27.2 and CMake 3.22.1. Every push builds debug APKs in
GitHub Actions. Release signing only happens in this repository's CI, from encrypted secrets
(see [docs/releasing.md](docs/releasing.md)).

## Project layout

```
core/      shared code, used by both apps
  cpp/engine.cpp          real-time audio engine: voices, mixer, sequencer, USB audio driver
  audio/                  Kotlin side of the engine, pattern, WAV decoder, kit storage
  mk3/                    controller driver (HID input, LEDs, screens), colour table, control map
  ui/                     pages on the controller screens (home, browser, setup, LED palette)
  Mk3Service.kt           foreground service that owns the controller, audio and UI
  Edition.kt              what each app customises (name, storage folder, home page)
full/      Open MK3 app
vanilla/   Open MK3 Vanilla app
docs/      hardware protocol notes, controls spec, roadmap, release process
```

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Please don't
submit content you don't have the rights to, such as samples from commercial libraries,
artwork, or text copied from other products' manuals.

## License

Open MK3 is free software under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
If you distribute a modified version, you must publish its source under the same license.

"Maschine" and "Native Instruments" are trademarks of their respective owners. They are used
here only to say which hardware this software works with.
