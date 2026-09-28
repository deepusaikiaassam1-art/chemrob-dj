# MedAdherence (Android)

MedAdherence is a native Android app that helps patients take their medicines on time and
measures how well they manage it. It is written in plain Java against the Android SDK, uses no
third-party libraries, and needs no Unity or any account to build.

## Download

Every push to `main` builds a fresh APK on GitHub and publishes it at a fixed link:

**https://github.com/deepusaikiaassam1-art/chemrob-dj/releases/download/apk-latest/MedAdherence.apk**

To install it:

1. Open that link on an Android phone running 8.0 or newer.
2. Open the downloaded file and allow "Install unknown apps" for your browser or file manager when
   Android asks.
3. Later builds install over the old one and keep your data, because every build is signed with
   the same key.

## What it does

- **Patient profile first.** On first launch the patient creates a profile, and nothing else is
  available until the name is saved. The profile holds:
  - name, date of birth and sex
  - phone number
  - conditions and allergies
  - doctor or pharmacy
  - emergency contact

  It is shown at the top of every adherence report and can be edited from the avatar in the top
  corner.
- **Medicine photos.** When adding a medicine, take a photo of the pack or tablet with the phone
  camera, or pick one from the gallery. At dose time the photo appears in the alarm notification
  and fills the ringing screen, next to the drug name, dose and instructions, so the patient takes
  the right tablet.
- **Modern, easy-to-read design.** Large text and buttons, rounded cards and an icon bottom bar.
  The Today screen leads with the next or due medicine and a big **I took it** button, followed by
  a progress ring for the day. There is a light and a dark theme, which follows the phone by
  default and can be changed under Pharmacist → Settings → Appearance.

- **Rings the phone at dose times.** A full-screen alarm appears over the lock screen and keeps
  sounding until the patient taps **Taken**, **Snooze** or **Skip**. If nobody answers, it rings
  again every 10 minutes, up to 4 times. Alarms are planned 7 days ahead and re-planned every time
  one fires, after a reboot and after a time change, so they never run out.
- **Fast regimen entry.** The patient or pharmacist enters the name, dose and instructions. One-tap
  presets set the frequency (OD / BD / TDS / QID / HS / Q8H / WEEKLY or your own times) and the
  duration (3–30 days or ongoing). Medicines can be paused and resumed; the history is kept and
  paused days don't count as missed.
- **Pharmacist mode.** Protected by a PIN (default `0000`). The pharmacist can:
  - paste a whole regimen at once, one line per medicine
  - share the regimen as text to load it on another phone
  - lock editing so only the pharmacist can change medicines
  - adjust the grace window, on-time window and snooze length
- **Adherence measurement:**
  - percentage of doses taken
  - percentage taken on time
  - days on which every dose was taken
  - missed, late and skipped counts for each medicine
  - current streak and a 14-day colour history
  - Adherent / Partially adherent / Non-adherent status, using the usual 80 % cut-off

  The report and the per-dose CSV log can be shared by WhatsApp, e-mail or SMS.
- **Observed doses ("special detection").** For medicines marked *observed*, the alarm opens the
  front camera and guides the patient through five steps: face in view, show the medicine,
  medicine in mouth, drink, and show an empty mouth. During each step the app checks for light,
  a person in view (skin tones in the middle of the frame) and live movement (so a still photo
  fails), and saves a photo. If all five steps pass, the dose counts as auto-verified; otherwise
  it goes to the pharmacist's review list with its photos.

  These checks are simple rules, not a trained pill or swallow detector.
- **Stock and refills.** Optionally track units in stock. The count drops with each dose taken,
  and a **Refill soon** warning appears when less than 5 days' supply is left.
- **Alarm reliability checks.** A settings screen shows whether notifications, exact alarms,
  ringing over the lock screen, battery optimisation and the camera are set up correctly. Each
  failing check has a **Fix** button, and a "ring in 1 minute" test confirms the alarm works.

Everything stays on the phone, and nothing is uploaded.

## Build it yourself

You need JDK 17 and the Android SDK. Either open this folder in Android Studio and press Run, or
build from the command line:

```
gradle -p MedAdherenceAndroid testDebugUnitTest assembleDebug
# APK: MedAdherenceAndroid/app/build/outputs/apk/debug/app-debug.apk
```

## Code layout

```
app/src/main/java/com/chemrob/medadherence/
  core/    plain Java: schedule, adherence, regimen parser, stock, camera-frame checks, JSON (unit-tested)
  alarm/   AlarmManager scheduling, alarm receiver, boot receiver, ringing notification
  ui/      MainActivity (profile + all tabs), AlarmActivity (lock-screen ringing with drug photo),
           ObserveActivity (observed-dose camera), PhotoActivity (medicine photo), Ui (theme and widgets)
  Store.java   the JSON data file in app-private storage
app/src/test/  JUnit tests for core/
```

The signing key `app/medadherence-debug.keystore` (password `android`) is committed on purpose, so
that downloaded builds update in place. Do not use it for a Play Store release; create a private
key for that.
