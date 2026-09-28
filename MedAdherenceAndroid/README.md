# MedAdherence (Android)

MedAdherence is a native Android app that helps patients take their medicines on time and
measures how well they manage it. It is written in plain Java against the Android SDK. Its only libraries
are Google ML Kit face and pose detection, which run on the phone, and it needs no Unity or any
account to build.

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
- **Face scan in the profile.** Profile set-up includes a face scan using on-device face
  detection. It guides the patient ("move closer", "look straight at the camera", "keep your eyes
  open", "more light") and captures automatically once exactly one steady, frontal face is in view.
  The photo and a face-geometry signature stay on the phone.
- **AI-checked observed doses.** Google ML Kit face and pose detection runs on the phone, with no
  internet needed and nothing uploaded. It checks each step of a camera-observed dose:
  - a face looking at the camera that matches the enrolled face
  - a hand raised with the medicine
  - the hand at an open mouth
  - drinking with the head tilted back
  - the mouth held wide open at the end

  Across the whole session it also needs a blink, which proves a live person rather than a photo.
  Each step ends as soon as the AI confirms it, or after 12 seconds, and the screen shows what is
  still missing. If every check passes, the dose is auto-verified. Otherwise the pharmacist reviews
  the step photos next to the enrolled face.

  Limits: this confirms a live, matching face and the right gestures, but no camera app can prove a
  tablet was actually swallowed. The face match is a simple geometry comparison, not biometric
  recognition, which is why failures go to the pharmacist rather than being rejected outright.
- **Voice guidance.** Once the patient accepts a dose, the phone speaks, using its own
  text-to-speech in the phone's language:
  - after "I took it", it reads out what to take and confirms the dose is recorded
  - in camera mode, it reads each step, says "Good" when a step is confirmed, and says what it still
    needs to see

  Voice guidance can be switched off under Pharmacist → Settings.
- **Medicine photos.** When adding a medicine, take a photo of the pack or tablet with the phone
  camera, or pick one from the gallery. At dose time the photo appears in the alarm notification
  and fills the ringing screen, next to the drug name, dose and instructions, so the patient takes
  the right tablet.
- **Doctor follow-ups.** The Doctor tab stores each visit's date and time, doctor, hospital and
  purpose, with quick buttons for 1 week, 2 weeks, 1 month or 3 months ahead. The phone reminds
  the patient the day before and 2 hours before. The next visit appears on Today and in the
  shared report, and visits can be marked done.
- **SOS emergency button.** The red SOS button at the top of every screen starts a 5-second
  countdown with a big Cancel, so an accidental tap does nothing. Then the app:
  - calls the emergency contact directly, with no tap needed
  - turns on the loudspeaker and repeats an automated voice message asking for help
  - when the call ends, opens the messaging app with the patient's name, conditions, allergies and
    a map link to their location filled in; the patient taps Send

  The app does not request SMS permission, because Google Play Protect blocks installing apps from
  outside the Play Store that ask for it. Android also does not let apps put audio straight into a
  phone call, so the voice is played through the speaker for the call's microphone to pick up.
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
gradle -p MedAdherenceAndroid testDebugUnitTest assembleRelease
# APK: MedAdherenceAndroid/app/build/outputs/apk/release/app-release.apk
```

## Code layout

```
app/src/main/java/com/chemrob/medadherence/
  core/    plain Java: schedule, adherence, regimen parser, stock, AI intake rules, face signature, JSON (unit-tested)
  alarm/   AlarmManager scheduling, alarm receiver, boot receiver, ringing notification
  ui/      MainActivity (profile + all tabs), EmergencyActivity (SOS), FaceEnrollActivity (face scan),
           Vision (ML Kit wrapper), Voice (spoken guidance), AlarmActivity (lock-screen ringing with drug photo),
           ObserveActivity (observed-dose camera), PhotoActivity (medicine photo), Ui (theme and widgets)
  Store.java   the JSON data file in app-private storage
app/src/test/  JUnit tests for core/
```

## Signing

The published APK is a release build: not debuggable, with code shrinking. It is signed like this:

- **Your own key (recommended).** Create one once, keep it private and back it up. Every future
  update must be signed with the same key.

  ```
  keytool -genkeypair -v -keystore medadherence-release.jks -storetype PKCS12 \
    -alias medadherence -keyalg RSA -keysize 4096 -validity 10000
  base64 -w0 medadherence-release.jks > keystore.b64      # Windows PowerShell:
  # [Convert]::ToBase64String([IO.File]::ReadAllBytes("medadherence-release.jks")) > keystore.b64
  ```

  Add these repository secrets under Settings → Secrets and variables → Actions:
  - `RELEASE_KEYSTORE_BASE64`: the contents of `keystore.b64`
  - `RELEASE_KEYSTORE_PASSWORD`
  - `RELEASE_KEY_ALIAS` (`medadherence`)
  - `RELEASE_KEY_PASSWORD`

  From then on, the workflow signs every build with your key.
- **Otherwise,** builds are signed with the shared test key `app/medadherence-debug.keystore`
  (password `android`), which is committed so builds update in place.

Switching keys means the new build will not install over the old one: uninstall the app first,
and that deletes its data. Set up your own key before you start using the app for real.

To sign an APK by hand:

```
zipalign -p -f -v 4 in.apk aligned.apk
apksigner sign --ks medadherence-release.jks --ks-key-alias medadherence --out signed.apk aligned.apk
apksigner verify --verbose --print-certs signed.apk
```
