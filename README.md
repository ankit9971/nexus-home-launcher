# Nexus Home: a night-sky TV launcher with twelve little creatures

An Apple TV-style home screen for Android TV, written in plain Java with no libraries, no network access and no
background service. A dock of four apps, a grid of the rest, an Inputs menu, a clock, and a night sky with stars
and a strip of grass where twelve small original creatures wander by, one at a time: they walk on, nap, hop,
wave, look around, spin, chase a striped ball, sit, and walk off.

![The twelve creatures](docs/critters.png)

Made for the **XGIMI MoGo 2** projector (Android TV 11, 1280x720), to go with the debloat list in
[cleanyourtv #516](https://github.com/Gathin23/cleanyourtv/pull/516). It is built to the same rules as that
project's reference launcher: small, offline, D-pad driven. Tested on that one projector and an Android TV emulator,
nothing else. Not affiliated with XGIMI, Google or Nintendo; the creatures are original drawings, not anyone's characters.

## What it does
- **Dock** of four apps, **grid** of everything else with a TV launcher entry (A to Z), **Inputs** dropdown, Wi-Fi and Settings pills.
- **Keys:** arrows move one tile, OK opens, Back and Home go to the dock; the last focused tile is remembered.
- **Critters** are all drawn in code (`CritterView.java`): no images, no video. They animate only while the launcher is in front.
- On the MoGo 2 it measured about 41 MB of memory and about 14 ms of GPU per frame while a creature is walking.
  I could not judge smoothness by eye on the wall; if it stutters, turn the theme down (below).

## Settings (`launcher/app/src/main/res/values/config.xml`, then rebuild)
| Key | Meaning |
|---|---|
| `dock_packages` | The four dock apps, by package name (`adb shell pm list packages -3`). A package that is not installed is skipped. The defaults are YouTube, Stremio, Nexus Display and Prime Video: change them. |
| `extra_packages` | Apps with no TV launcher entry that should still appear (for example `com.netflix.mediaclient`). |
| `critter_theme` | `false` gives a plain black launcher. |
| `critter_pixel_art` | `false` (default): smooth shaded shapes. `true`: chunky pixel art, lighter on the GPU. |

To see them all at once (after `adb shell am force-stop app.nexus.launcher`):
`adb shell am start -n app.nexus.launcher/.LauncherActivity --ez gallery true`.

## Build and install
Needs JDK 17+, the Android SDK (platform 34, build-tools 34) and `adb`. On the TV turn on Developer options,
then **USB debugging and Network debugging** (both, or port 5555 stays closed).

    TV=<tv ip>:5555 ./build-install.sh

Making it the home screen: many firmwares keep the stock launcher ahead of any other, so disable it first
(`adb shell pm disable-user --user 0 com.google.android.tvlauncher`) and then
`adb shell cmd package set-home-activity app.nexus.launcher/.LauncherActivity`.
**Undo:** `adb shell pm enable --user 0 com.google.android.tvlauncher`, then `adb shell pm uninstall app.nexus.launcher`.
Everything is reversible; nothing here uninstalls system apps. Take your own backup of `pm list packages` first.

**When you are done, close ADB:** turn off both debugging toggles, revoke USB debugging authorizations, reboot.
Port 5555 is plaintext and unauthenticated for anyone on your network who is already approved.

## Licence
MIT (see `LICENSE`). The Outfit font is under the SIL Open Font License 1.1.
