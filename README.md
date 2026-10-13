# Hilight Link

Links your notifications to the Hilight LEDs on the Pixel 11 Pro. Pick an app, pick a
pattern and colours, and the LEDs play it whenever that app notifies you.

## What it does

- **Per-app patterns**: 14 styles (breathe, chase, sparkle, rainbow and more), up to three
  colours, with speed, length and brightness. A new rule starts in the colour of the app's icon.
- **Channels and contacts**: inside an app, give one notification channel, one contact or one
  group its own pattern, or keep it dark while the rest of the app lights up.
- **All other apps**: one catch-all rule for apps without their own.
- **Incoming calls**: a pattern that loops for as long as the phone rings, for phone calls and
  for calls from apps.
- **Stay lit until dismissed**: a dim glow stays on after the pattern, like the old notification
  LED. When several apps are waiting, each colour gets its share of the LEDs.
- **Quiet hours**: lights off, or dimmed, between the times you choose, and no light for what
  Do Not Disturb silences.
- **Missed notification reminder**: replays the pattern every few minutes until you unlock.
- **Charging light**: a colour while charging and another when full, or a battery meter across
  the LEDs, plus a red blink at 15% and 5%.
- **Music visualizer**: seven styles that follow whatever the phone is playing, including a
  Shuffle that changes style with the music.
- **Timer light**: the LEDs drain as a countdown runs out, then blink. Follows Clock timers
  or one started in the app.
- **Pause all lights**: in the app or from a Quick Settings tile.
- **Backup**: save and restore every rule and setting as one file.

## What you need

- A Pixel 11 Pro. It is the only phone this has been tested on. On a phone without Hilight
  LEDs the app installs but lights nothing.
- Root: Magisk, KernelSU or APatch. Apps are not allowed to reach the lights service, so a
  small root helper does it for the app.

## Install

1. Download `HilightLink-module.zip` from the
   [latest release](https://github.com/atrejokm301/HilightLink/releases/latest) and flash it in
   your root manager. It installs the app too.
2. Reboot.
3. Open Hilight Link and allow root for it in your root manager.
4. Tap **Add app** and build your first pattern. **Test on phone** plays it on the LEDs.

The module starts the helper at boot. When it is installed it also turns on notification
access for the app and exempts it from battery optimisation, so calls and notifications are
never missed while the phone sleeps. Both can be undone in Settings.

Without the module you can install `HilightLink.apk` by itself. The app then starts the helper
through `su` when it needs it, and asks you to turn on notification access.

To remove it, uninstall the module in your root manager and uninstall the app.

If you have a build from before the first public release, it was signed with a different key
and cannot be updated in place: save your rules with **Backup** in the app's settings,
uninstall the app, install this one and restore the backup.

## Permissions

- **Notification access**: to see which app and channel posted a notification. The title is
  looked at only for apps where you made a rule for a contact or a group, to see whether it
  matches. The message is never read, and nothing is stored.
- **Microphone**: only for the music visualizer. Android puts reading the phone's own sound
  output behind this permission. Only loudness per pitch range is used; nothing is recorded.
- **Internet**: only for a loopback connection to the helper on the phone itself. The app
  never talks to the network.

## How it works

```
notification -> LedListenerService -> Leds -> loopback socket -> root helper -> lights HAL
```

The helper (`daemon/Main.kt`) lives inside the APK and is run by `app_process` as root. It
listens on a loopback port and only obeys requests that carry a token stored in the app's
private files. It asks the lights HAL which lights are the Hilight LEDs and writes frames to
them at about 30 per second. `engine/Effect.kt` renders the frames for both the helper and
the previews in the app, so what you see on screen is what the LEDs play.

When more than one light wants the LEDs, the helper shows the first of these that is active:
a ringing call, a timer that ran out or a low battery warning, a notification pattern, the
timer countdown, the music visualizer, the glow of waiting notifications, the charging light.

### Inside the system (optional)

With an Xposed framework installed (Vector, LSPosed), switch Hilight Link on there for
**System Framework** and reboot. The lights then run from inside the system process:

```
notification -> system listener -> Engine -> lights HAL
```

In there the system hands over every notification without notification access, the lights
HAL can be reached without the root helper, and nothing puts the process to sleep. Nothing is
hooked. The app stays where the settings are made: it finds the engine (`system/Engine.kt`),
sends it the settings, and leaves the notification, call, charging and timer lights to it.
The home screen shows "In the system" in place of "Root helper" when this is on.

The engine is loaded from the installed APK and again after every update of the app, so an
update takes no reboot. To keep it from loading, run `setprop persist.sys.hilightlink.off 1`
as root, or switch the app off in the Xposed framework, and reboot.

This has been used on one Pixel 11 Pro with Vector on KernelSU. The root helper is still the
default, and what the app falls back on whenever the engine is not there.

## Build

Needs JDK 17, the Android SDK and Python.

```
./build.sh
```

This writes `dist/HilightLink.apk` and `dist/HilightLink-module.zip`.

`./gradlew :app:testDebugUnitTest` runs the tests.

A release build is signed with the key named in `~/.gradle/gradle.properties`
(`hilightlink.storeFile`, `hilightlink.storePassword`, `hilightlink.keyAlias`,
`hilightlink.keyPassword`), and with the Android debug key when those are not set. A build
signed with your own key cannot be installed over a released one, so uninstall that first.

## Licence

Hilight Link is free software under the [GNU General Public License, version 3](LICENSE).
You may use, change and share it; if you share a changed build, its source has to be
available under the same licence. It comes with no warranty.

It is not affiliated with Google. Pixel and Hilight are Google's names for its own products.

The app is built with AndroidX and Jetpack Compose, which are under the Apache License 2.0.
