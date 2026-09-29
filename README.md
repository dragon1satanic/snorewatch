# SnoreWatch

An Android app that listens for snoring while you sleep and **immediately sounds an
alarm to wake you up** as soon as snoring is detected.

Detection runs **100% on-device** using Google's YAMNet audio classification model
(TensorFlow Lite) — no internet connection or account needed, and no audio ever
leaves the phone.

## Download

**[Download SnoreWatch →](https://auto-machines.com/apps/snorewatch/)**

Android 8.0 or newer, ~32 MB, signed release build. If you'd rather skip the page, the
APK itself is at
[`auto-machines.com/apps/snorewatch/snorewatch-android.apk`](https://auto-machines.com/apps/snorewatch/snorewatch-android.apk).

Or build it yourself from this repo — see [Building](#building).

## How it works

1. A foreground service records short (≈1 s) audio windows from the microphone.
2. Each window is classified by YAMNet (TensorFlow Lite Task Library), which has a
   dedicated **`Snoring`** class among its 521 audio event classes.
3. When the snore probability exceeds your chosen threshold for N consecutive
   windows, the app:
   - plays the system alarm sound on the **alarm audio stream** (loops),
   - vibrates continuously,
   - posts a full-screen "Snoring detected!" notification that turns the screen on.
4. Tap **"I'm awake"** (in the app or on the notification) to silence the alarm.
   Detection then pauses for 15 s so the alarm doesn't instantly retrigger.

## Building

The project is a standard Gradle/Android project:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Or open the folder directly in **Android Studio**, which will prompt you to install
any missing SDK components and write its own `local.properties`.

Built against JDK 17, Gradle 8.9 and Android SDK 34 (`compileSdk`/`targetSdk` 34,
`minSdk` 26).

## Installing on your phone

Enable *Developer options → USB debugging* on the phone, connect it, then:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone and open it (allow "install from unknown sources").

## Usage tips

- **Plug the phone in overnight** — continuous microphone + inference uses battery.
- Place the phone on the nightstand, microphone facing you.
- Start with **Medium sensitivity / 3 snores**. If it wakes you on coughs or
  traffic noise, lower the sensitivity; if it misses snoring, raise it.
- On some phones (Xiaomi, Oppo, etc.) you may need to allow the app to
  "display pop-up windows while running in background" so the alarm screen
  appears over the lock screen, and exempt it from battery optimization.

## Tuning

| Setting | Effect |
|---|---|
| Sensitivity slider | Probability threshold 0.10 (most sensitive) – 0.55 (least) |
| Snores before alarm | Consecutive ~0.4 s windows that must look like snoring (1–5) |

Constants in `SnoreDetectionService.kt` you may want to tweak:

- `INFERENCE_INTERVAL_MS` — how often audio is classified (default 400 ms)
- `ALARM_COOLDOWN_MS` — quiet period after you dismiss the alarm (default 15 s)

## Release signing

`app/build.gradle.kts` reads release signing credentials from `keystore.properties`
at the project root. That file — and the keystore it points at — are **not** in this
repository (see `.gitignore`). When it is absent no release signing config is created
at all, so a fresh clone builds and installs debug builds with no setup, and
`assembleRelease` produces `app-release-unsigned.apk`.

To sign a release build yourself, create `keystore.properties`:

```properties
storeFile=keystore/your.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

## Privacy

Audio is processed in memory in real time and is **never recorded, stored, or
transmitted**. The app declares no `INTERNET` permission, so it has no way to
send audio anywhere even if it wanted to.

## More from Autonomous Machines

SnoreWatch is a small side project from **[auto-machines.com](https://auto-machines.com/)**.
The main thing over there is **[Zerx](https://auto-machines.com/zerx.html)** — a desktop AI
workspace for Windows and Linux that runs models locally on your own machine, is free, and
shows you every edit before it lands. It scratches the same itch as this app: your hardware,
your data, nothing shipped off to someone else's server.

Also on the site: [release news](https://auto-machines.com/news.html), and a couple of small
browser toys — [Bouncing Bubbles](https://auto-machines.com/bubbles.html) and
[Bubble Fountain](https://auto-machines.com/fountain.html).

## License

[MIT](LICENSE).

The bundled audio classifier is Google's **YAMNet** (`app/src/main/assets/yamnet.tflite`),
used under the Apache-2.0 license; the `Snoring` label it detects on is one of the
521 AudioSet classes.
