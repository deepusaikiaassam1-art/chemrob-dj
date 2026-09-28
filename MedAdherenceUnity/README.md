# MedAdherence: Unity Android app for medication adherence

An Android app built with Unity that helps patients take their medicines on time and measures how
well they do.

- **Rings the phone at every dose time.** It uses a full-screen alarm that shows over the lock
  screen and keeps sounding until the patient answers. It repeats if nobody answers, and it survives
  reboots.
- **Quick regimen entry for patient or pharmacist.** Pick a medicine, tap a frequency (OD / BD / TDS
  / QID / HS / Q8H / WEEKLY), tap a duration, and save. A pharmacist can also paste a whole
  regimen, one line per medicine.
- **Adherence measurement.** It reports dose-taking %, timing %, proportion of days covered, and
  missed / late / skipped counts per medicine. It also shows a 14-day history, the current streak,
  and a shareable report plus a CSV dose log.
- **Observed-dose mode ("special detection").** For medicines marked *observed*, the alarm opens the
  app on the front camera. The patient is guided through the intake steps while the app checks
  that someone is in view and moving live, and it saves a timestamped photo for each step. Doses
  that pass every check are auto-verified. The rest go to the pharmacist's review queue.

## Opening it in Unity

1. Install **Unity 2022.3 LTS** (or Unity 6) with **Android Build Support** (SDK, NDK, OpenJDK) from
   Unity Hub.
2. Unity Hub → *Add project from disk* → select this `MedAdherenceUnity` folder.
3. In the editor menu: **MedAdherence → Configure for Android**. This creates the scene, sets the
   package id `com.chemrob.medadherence`, min SDK 24, target SDK 34, IL2CPP ARM64 + ARMv7, portrait,
   and switches the platform.
4. Press **Play** to try it in the editor. Alarms are simulated in-app with a generated tone, and
   the observed mode uses your webcam.
5. **MedAdherence → Build Android APK** writes `Builds/Android/MedAdherence.apk`. You can also use
   *File → Build And Run* with a phone connected over USB.

Command-line build:

```
Unity -batchmode -quit -projectPath MedAdherenceUnity \
      -executeMethod MedAdherence.EditorTools.BuildAndroid.Build -logFile -
```

Unit tests: *Window → General → Test Runner → EditMode → Run All*.

## Using the app

| Tab | What it does |
|---|---|
| **Today** | Today's doses with *Take* / *Take on camera* / *Skip*, the next dose, and 7-day adherence |
| **Medicines** | Add / edit / pause / delete medicines. The editor has one-tap frequency and duration presets |
| **Adherence** | 7 / 30 / 90 day / all-time metrics, a per-medicine breakdown, 14-day history, share report, export CSV |
| **Pharmacist** | PIN-protected (default `0000`, change it). Includes the observed-dose review queue, bulk import, share regimen, settings, and alarm reliability checks with a "ring in 1 minute" test |

Bulk import format (see `sample_regimen.txt`):

```
Name | Dose | Times or OD/BD/TDS/QID/HS/Q8H/WEEKLY | Duration (7, 2w, 6m, 0 = ongoing) | Start | Observed yes/no | Instructions
Metformin | 500 mg, 1 tablet | BD | 0 | today | no | after food
Rifampicin + Isoniazid | 2 tablets | 07:00 | 6m | today | yes | empty stomach
Methotrexate | 7.5 mg | WEEKLY 09:00 | 12w | today | no |
```

On first run, open **Pharmacist → Alarm reliability** and fix anything marked `!`:

- notification permission
- exact alarms
- full-screen alarm over the lock screen (Android 14)
- battery optimisation (needed on Xiaomi/Oppo/Vivo/Samsung devices that kill background apps)

## How adherence is measured

A dose is **due** once its time has passed. It becomes **missed** when nothing was recorded within
the grace window (default 120 min). Doses still inside the grace window are *pending* and are not
counted yet.

| Metric | Definition |
|---|---|
| Dose adherence | taken ÷ due (late doses count as taken) |
| Timing adherence | taken within ± on-time window (default 60 min) ÷ due |
| Days covered (PDC-style) | days on which every due dose was taken ÷ days with any due dose |
| Observed verified | auto-verified or pharmacist-approved ÷ observed doses due |
| Category | ≥ 80 % *Adherent*, 50–79 % *Partially adherent*, < 50 % *Non-adherent* |

Paused periods are excluded, so doses are neither due nor missed while a medicine is paused.

## Observed-dose (DOT) mode

The five steps are: face in frame, show the medicine, medicine in mouth, drink and swallow, and
show an empty mouth. Each step lasts 6 s. During each step the app samples the camera 5 times a
second at 64 px width and checks three things:

- **light**: mean luminance, so a covered lens fails
- **live movement**: frame-to-frame luminance change, so a still photo held up to the camera fails
- **person in view**: share of skin-toned pixels in the centre of the frame (YCbCr rule)

A JPEG snapshot is saved for each step under the app's private storage. If all 5 steps pass, the
dose is *auto-verified*. Otherwise it is *waiting for pharmacist review* and appears with its photos
in the Pharmacist tab.

These checks are deliberately simple heuristics, not a medical-grade detector. If you need real
pill and swallow detection, replace the logic in `ObservationCriteria` / `FrameAnalysis` with a
model, for example Unity Sentis running an object-detection network. The photo evidence and review
workflow stay the same.

## Project layout

```
Assets/MedAdherence/Scripts/Core/      pure C# (no UnityEngine): models, schedule, adherence, parser, frame checks
Assets/MedAdherence/Scripts/Runtime/   Unity app: UI built from code, storage, alarm bridge, camera session, tone
Assets/MedAdherence/Editor/            Android configure/build menu + batch-mode entry point
Assets/MedAdherence/Tests/EditMode/    NUnit tests for the core logic
Assets/Plugins/Android/MedAlarm.androidlib/   native Java: AlarmManager scheduling, ringing screen,
                                              notification actions, boot re-scheduling
```

How the alarm pieces fit together:

- **Unity to Java.** Unity re-registers every unsettled dose for the next 14 days whenever the app
  starts, resumes, pauses, or the regimen changes (`MedAlarmPlugin.scheduleAll`).
- **When a dose is due.** `AlarmReceiver` posts an insistent alarm-channel notification whose
  full-screen intent opens `AlarmActivity`. That screen shows over the lock screen and offers
  *Taken / Snooze / Skip*, or *Take on camera* for observed medicines. An unanswered dose rings
  again every 10 min, up to 3 times.
- **Java back to Unity.** Actions taken while Unity is closed are queued in SharedPreferences.
  Unity reads them on resume (`drainEvents`), so the adherence log stays complete. If the patient
  taps *Take on camera*, the app launches straight into the observed-dose session.
- **Reboots.** `BootReceiver` re-arms stored alarms after a reboot, a time change, or an app update.

If the app isn't opened for 14 days, reminders stop. Opening the app at any time extends the window.

## Data and privacy

Everything stays on the phone, in `Application.persistentDataPath`:

- `medadherence.json` holds the regimen, dose log and settings
- `evidence/` holds the camera snapshots
- `exports/` holds CSV exports

Nothing is uploaded. Sharing a report goes through the Android share sheet, so the patient chooses
the recipient.
