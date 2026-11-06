# Knock

**Tap your desk to control your phone.** Knock twice or three times on the table your Android phone is lying on, and Knock runs the action you picked: scroll, go back, play or pause music, take a screenshot, open an app, and more.

Knock hears where you knocked using the phone's two microphones. That gives you four actions with no touching:

| | Knock twice | Knock three times |
|---|---|---|
| **Near** the phone (or on its **left** in landscape) | action 1 | action 2 |
| **Far** from the phone (or on its **right** in landscape) | action 3 | action 4 |

## Download

**[Download the latest APK](https://github.com/yemcube/knock/releases/latest)**: open it on your phone and allow installs from your browser when asked. Needs Android 11 or newer.

Knock is also in review on Google Play. The Play version is signed with a different key, so to switch between the APK and Play, uninstall one first.

## Getting started

1. **Turn on the microphone and Accessibility.** The app walks you through both. Accessibility is only used to carry out the actions you choose.
2. **Teach it your knock.** Lay the phone on your desk and knock in each spot while a live graph shows what the mics hear. It learns your table, your knuckles and your room.
3. **Pick your actions.** Tap any of the four tiles to choose what it does.
4. **Switch it on** with the toggle at the top. A notification shows while Knock is listening.

### Actions

Scroll down / up, swipe left / right, back, home, recent apps, notifications, quick settings, play / pause, next / previous track, assistant, screenshot, lock screen, flashlight, or open any app.

### Also

- **Profiles:** keep different action sets (say, *Reading* and *Music*) and switch between them.
- **Practice mode:** knocks are recognised and shown, but nothing runs. Good for getting the feel.
- **Light and dark** follow your phone's setting.

## Privacy

Sound is analysed on the phone, as it arrives, and thrown away. Nothing is recorded, stored or sent anywhere, and Knock has no internet permission. The microphone is only on while Knock is switched on. Full policy: [littyl.com/privacy/knock](https://www.littyl.com/privacy/knock/)

## Building from source

Requires JDK 17 and the Android SDK (platform 36).

```sh
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Release builds look for a signing config outside the repo and fall back to unsigned without it.

## How it works

- `core/`: the detection pipeline. `Dsp` and `Detector` find sharp transients in both mic channels; `Grouper` turns them into double and triple knocks; `Model` tells the spots apart from the level and timing difference between the two mics, against what you taught it.
- `KnockService`: the foreground service that listens and runs actions.
- `KnockAccess` and `Actions`: the Accessibility service that performs gestures and system actions.
- `Ui` and `Motion`: the dot-matrix design system and a small spring-based motion engine, built on plain Android views.
- `analysis/`: the Python scripts used to check, from raw recordings, that two phone mics can tell knock spots apart.

Made by [Yemcube](https://yemcube.tech).
