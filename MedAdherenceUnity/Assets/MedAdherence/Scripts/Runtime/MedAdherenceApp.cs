using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MedAdherence.Core;
using MedAdherence.UI;
using UnityEngine;
using UnityEngine.UI;

namespace MedAdherence
{
    /// <summary>
    /// The whole app. It creates itself when any scene loads (see <see cref="Boot"/>), builds its UI
    /// in code, and keeps the native alarm schedule in sync with the regimen.
    ///
    /// Tabs: Today (take / skip doses), Medicines (load the regimen), Adherence (metrics and
    /// reports), Pharmacist (PIN-protected bulk import, evidence review, settings).
    /// </summary>
    public class MedAdherenceApp : MonoBehaviour
    {
        enum Tab { Today, Medicines, Adherence, Pharmacist }

        /// <summary>A dose may be marked taken up to this long before its scheduled time.</summary>
        const int EarlyWindowMinutes = 120;

        DataStore store;
        AppData D => store.Data;

        RectTransform safeRoot, content, overlayLayer;
        Text headerTitle, headerSub;
        readonly Dictionary<Tab, Image> navButtons = new Dictionary<Tab, Image>();
        Tab tab = Tab.Today;
        Rect appliedSafeArea;

        bool pharmacistUnlocked;
        int adherenceDays = 30;
        float nextDueCheck;
        GameObject ringOverlay, modal;
        ObservationSession observing;
        AlarmTone tone;
        DateTime? testAlarmAt;
        string todaySignature;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Boot()
        {
            if (FindObjectOfType<MedAdherenceApp>() != null) return;
            new GameObject("MedAdherenceApp").AddComponent<MedAdherenceApp>();
        }

        // ================================================================== lifecycle

        void Awake()
        {
            DontDestroyOnLoad(gameObject);
            Application.targetFrameRate = 30;
            Screen.orientation = ScreenOrientation.Portrait;

            store = new DataStore();
            store.Load();
            tone = gameObject.AddComponent<AlarmTone>();

            BuildShell();
            AlarmService.RequestStartupPermissions();
            Sync();
            Show(Tab.Today);
        }

        void OnApplicationFocus(bool focused)
        {
            if (focused && store != null) Sync();
        }

        void OnApplicationPause(bool paused)
        {
            if (store == null) return;
            if (paused) Reschedule();
            else Sync();
        }

        void Update()
        {
            if (Screen.safeArea != appliedSafeArea) ApplySafeArea();
            if (Time.unscaledTime >= nextDueCheck)
            {
                nextDueCheck = Time.unscaledTime + 5f;
                CheckDue();
            }
            if (Input.GetKeyDown(KeyCode.Escape)) OnBack();
        }

        /// <summary>Pulls in actions taken outside the app, re-arms alarms, and opens the camera if asked.</summary>
        void Sync()
        {
            bool changed = false;
            foreach (var e in AlarmService.DrainEvents())
            {
                if (!DoseKey.TryParse(e.doseKey, out var medId, out _) || D.FindMed(medId) == null) continue; // test alarm
                ScheduleEngine.Record(D, e.doseKey, e.status, e.at);
                changed = true;
            }
            if (changed) store.Save();
            Reschedule();

            string launch = AlarmService.ConsumeLaunchDose();
            if (launch != null) StartObservation(launch);
            Refresh();
        }

        void Reschedule()
        {
            AlarmService.Reschedule(D, DateTime.Now);
            if (testAlarmAt.HasValue && testAlarmAt.Value > DateTime.Now)
                AlarmService.Schedule(DoseKey.Make("TEST", testAlarmAt.Value), testAlarmAt.Value,
                    "Test alarm", "This is how your medicine reminder will ring.", false);
        }

        void OnBack()
        {
            if (modal != null) { CloseModal(); return; }
            if (ringOverlay != null || observing != null) return;
            if (tab != Tab.Today) Show(Tab.Today);
            else Application.Quit();
        }

        // ================================================================== shell

        void BuildShell()
        {
            var canvas = UIKit.CreateCanvas(transform);
            var bg = UIKit.Panel(canvas.transform, UIKit.Bg, false, "Background");
            UIKit.Stretch(bg.rectTransform);

            safeRoot = UIKit.Stretch(UIKit.Rect(canvas.transform, "SafeArea"));

            // Header
            var header = UIKit.Panel(safeRoot, UIKit.Primary, false, "Header");
            var hrt = header.rectTransform;
            hrt.anchorMin = new Vector2(0, 1); hrt.anchorMax = Vector2.one; hrt.pivot = new Vector2(0.5f, 1);
            hrt.sizeDelta = new Vector2(0, 210);
            var hv = header.gameObject.AddComponent<VerticalLayoutGroup>();
            UIKit.Configure(hv, 4, 40);
            hv.childAlignment = TextAnchor.MiddleLeft;
            headerTitle = UIKit.Label(header.transform, "", 56, Color.white, FontStyle.Bold);
            headerSub = UIKit.Label(header.transform, "", 34, UIKit.Hex("BFE3F0"));

            // Bottom navigation
            var nav = UIKit.Panel(safeRoot, UIKit.Surface, false, "Nav");
            var nrt = nav.rectTransform;
            nrt.anchorMin = Vector2.zero; nrt.anchorMax = new Vector2(1, 0); nrt.pivot = new Vector2(0.5f, 0);
            nrt.sizeDelta = new Vector2(0, 170);
            var nh = nav.gameObject.AddComponent<HorizontalLayoutGroup>();
            UIKit.Configure(nh, 12, 16);
            nh.childForceExpandHeight = true;
            foreach (Tab t in Enum.GetValues(typeof(Tab)))
            {
                var tt = t;
                var b = UIKit.Button(nav.transform, t.ToString(), UIKit.Surface, () => Show(tt), 138, 34, UIKit.Primary);
                navButtons[t] = b.GetComponent<Image>();
            }

            content = UIKit.Stretch(UIKit.Rect(safeRoot, "Content"), 0, 0, 210, 170);
            overlayLayer = UIKit.Stretch(UIKit.Rect(canvas.transform, "Overlays"));
            ApplySafeArea();
        }

        void ApplySafeArea()
        {
            appliedSafeArea = Screen.safeArea;
            if (Screen.width <= 0 || Screen.height <= 0) return;
            safeRoot.anchorMin = new Vector2(appliedSafeArea.xMin / Screen.width, appliedSafeArea.yMin / Screen.height);
            safeRoot.anchorMax = new Vector2(appliedSafeArea.xMax / Screen.width, appliedSafeArea.yMax / Screen.height);
            safeRoot.offsetMin = safeRoot.offsetMax = Vector2.zero;
        }

        void Show(Tab t)
        {
            tab = t;
            foreach (var kv in navButtons)
            {
                bool on = kv.Key == t;
                kv.Value.color = on ? UIKit.Primary : UIKit.Surface;
                kv.Value.GetComponentInChildren<Text>().color = on ? Color.white : UIKit.Primary;
            }
            Refresh();
        }

        void Refresh()
        {
            if (content == null) return;
            UIKit.Clear(content);
            var body = UIKit.Scroll(content);
            switch (tab)
            {
                case Tab.Today: BuildToday(body); break;
                case Tab.Medicines: BuildMedicines(body); break;
                case Tab.Adherence: BuildAdherence(body); break;
                case Tab.Pharmacist: BuildPharmacist(body); break;
            }
        }

        void SetHeader(string title, string sub)
        {
            headerTitle.text = title;
            headerSub.text = sub;
        }

        // ================================================================== Today

        void BuildToday(RectTransform body)
        {
            var now = DateTime.Now;
            string who = string.IsNullOrEmpty(D.settings.patientName) ? "" : ", " + D.settings.patientName;
            string greet = now.Hour < 12 ? "Good morning" : now.Hour < 17 ? "Good afternoon" : "Good evening";
            SetHeader(greet + who, now.ToString("dddd, d MMMM yyyy"));

            if (D.medications.Count == 0)
            {
                var c = UIKit.Card(body);
                UIKit.Label(c.transform, "No medicines yet", 46, UIKit.Ink, FontStyle.Bold);
                UIKit.Label(c.transform, "Add the patient's medicines with their times and duration. " +
                                         "The phone will ring at every dose time.", 36, UIKit.Muted);
                UIKit.Button(c.transform, "+ Add medicine", UIKit.Accent, () => EditMedication(null));
                UIKit.Button(c.transform, "Pharmacist: import a full regimen", UIKit.Primary, () => Show(Tab.Pharmacist));
                return;
            }

            var doses = ScheduleEngine.Doses(D, now.Date, now.Date.AddDays(1));
            todaySignature = TodaySignature(now);
            int taken = doses.Count(d => ScheduleEngine.StatusOf(D, d, now) == DoseStatus.Taken);

            foreach (var low in D.medications.Where(x => Inventory.NeedsRefill(x, now)).ToList())
            {
                var lm = low;
                var banner = UIKit.Card(body, UIKit.Hex("FDE7E5"));
                UIKit.Label(banner.transform, "<b>Refill " + lm.name + " soon</b> - " + Inventory.Label(lm) +
                            ". Contact your pharmacy so you don't run out.", 36, UIKit.Ink);
                UIKit.Button(banner.transform, "I have refilled it", UIKit.Accent, () => AskRefill(lm), 100, 34);
            }

            var summary = UIKit.Card(body);
            UIKit.Label(summary.transform, string.Format("<b>{0} of {1}</b> doses taken today", taken, doses.Count), 44);
            var next = ScheduleEngine.Doses(D, now, now.AddDays(7)).FirstOrDefault(d => ScheduleEngine.StatusOf(D, d, now) == DoseStatus.Pending);
            if (next.Med != null)
            {
                var span = next.Time - now;
                string inStr = span.TotalHours >= 24 ? next.Time.ToString("ddd HH:mm") :
                               span.TotalMinutes >= 60 ? string.Format("in {0}h {1}m", (int)span.TotalHours, span.Minutes) :
                               string.Format("in {0} min", Math.Max(0, (int)span.TotalMinutes));
                UIKit.Label(summary.transform, "Next: " + next.Med.name + " at " + TimeUtil.Clock(next.Time) + " (" + inStr + ")", 36, UIKit.Muted);
            }
            var week = AdherenceCalculator.Compute(D, now.Date.AddDays(-6), now).overall;
            UIKit.Bar(summary.transform, "Last 7 days adherence", week.TakingPercent);

            if (doses.Count == 0) UIKit.Label(body, "No doses scheduled today.", 38, UIKit.Muted);
            foreach (var dose in doses) DoseRow(body, dose, now);
        }

        void DoseRow(Transform parent, ScheduledDose dose, DateTime now)
        {
            var status = ScheduleEngine.StatusOf(D, dose, now);
            var rec = D.FindRecord(dose.Key);
            bool due = ScheduleEngine.IsDueNow(D, dose, now);

            var card = UIKit.Card(parent, due ? UIKit.Hex("FFF4DE") : UIKit.Surface);
            var top = UIKit.HBox(card.transform, 24);
            top.childAlignment = TextAnchor.MiddleLeft;
            var time = UIKit.Label(top.transform, TimeUtil.Clock(dose.Time), 52, UIKit.Primary, FontStyle.Bold);
            UIKit.Size(time, -1, 170);
            var name = UIKit.Label(top.transform, "<b>" + dose.Med.name + "</b>\n" + dose.Med.dose, 38);
            UIKit.Size(name, -1, -1, 1);

            string badge; Color color;
            switch (status)
            {
                case DoseStatus.Taken:
                    bool late = rec != null && Math.Abs((rec.ActionAt - dose.Time).TotalMinutes) > D.settings.onTimeWindowMinutes;
                    badge = (late ? "Late " : "Taken ") + (rec != null ? TimeUtil.Clock(rec.ActionAt) : "");
                    color = late ? UIKit.Warn : UIKit.Good; break;
                case DoseStatus.Skipped: badge = "Skipped"; color = UIKit.Muted; break;
                case DoseStatus.Missed: badge = "Missed"; color = UIKit.Bad; break;
                case DoseStatus.Snoozed: badge = "Snoozed"; color = UIKit.Warn; break;
                default: badge = due ? "Due now" : "Upcoming"; color = due ? UIKit.Warn : UIKit.Accent; break;
            }
            UIKit.Badge(top.transform, badge, color);

            if (!string.IsNullOrEmpty(dose.Med.instructions) || dose.Med.observed)
                UIKit.Label(card.transform, (dose.Med.observed ? "<b>Camera-observed dose.</b> " : "") + dose.Med.instructions, 32, UIKit.Muted);

            if (rec != null && dose.Med.observed && status == DoseStatus.Taken)
                UIKit.Label(card.transform, "Verification: " + VerificationLabel(rec.Verification), 32, UIKit.Muted);

            bool canAct = (status == DoseStatus.Pending || status == DoseStatus.Snoozed) && now >= dose.Time.AddMinutes(-EarlyWindowMinutes);
            bool canCatchUp = status == DoseStatus.Missed; // late dose after the grace window still counts as taken (late)
            if (!canAct && !canCatchUp) return;

            var actions = UIKit.HBox(card.transform, 16);
            actions.childForceExpandWidth = true;
            string key = dose.Key;
            if (dose.Med.observed)
                UIKit.Button(actions.transform, "Take on camera", UIKit.Good, () => StartObservation(key), 110, 36);
            else
                UIKit.Button(actions.transform, canCatchUp ? "Taken late" : "Take", UIKit.Good, () => RecordAction(key, DoseStatus.Taken), 110, 36);
            UIKit.Button(actions.transform, "Skip", UIKit.Muted, () => RecordAction(key, DoseStatus.Skipped), 110, 36);
        }

        static string VerificationLabel(VerificationStatus v)
        {
            switch (v)
            {
                case VerificationStatus.AutoVerified: return "verified on camera";
                case VerificationStatus.NeedsReview: return "waiting for pharmacist review";
                case VerificationStatus.PharmacistApproved: return "approved by pharmacist";
                case VerificationStatus.PharmacistRejected: return "rejected by pharmacist";
                default: return "not required";
            }
        }

        void RecordAction(string doseKey, DoseStatus status)
        {
            ScheduleEngine.Record(D, doseKey, status, DateTime.Now);
            store.Save();
            if (status == DoseStatus.Taken || status == DoseStatus.Skipped) AlarmService.Cancel(doseKey);
            else AlarmService.Silence(doseKey);
            Reschedule();
            Refresh();
        }

        // ================================================================== ringing

        void CheckDue()
        {
            if (ringOverlay != null || observing != null || D.medications.Count == 0) return;
            var now = DateTime.Now;
            var due = ScheduleEngine.Doses(D, now.AddMinutes(-D.settings.graceMinutes), now.AddTicks(1))
                                    .Where(d => ScheduleEngine.IsDueNow(D, d, now)).ToList();
            if (due.Count > 0) ShowRinging(due[0]);
            else if (tab == Tab.Today && modal == null)
            {
                // Keep "Upcoming / Due now / Missed" badges current without resetting scroll needlessly.
                string sig = TodaySignature(now);
                if (sig != todaySignature) Refresh();
            }
        }

        string TodaySignature(DateTime now)
        {
            var sb = new System.Text.StringBuilder();
            foreach (var d in ScheduleEngine.Doses(D, now.Date, now.Date.AddDays(1)))
                sb.Append((int)ScheduleEngine.StatusOf(D, d, now)).Append(ScheduleEngine.IsDueNow(D, d, now) ? 'd' : '-')
                  .Append(now >= d.Time.AddMinutes(-EarlyWindowMinutes) ? 'e' : '-');
            return sb.ToString();
        }

        /// <summary>Full-screen in-app alarm. The native notification rings too when the app is in the background.</summary>
        void ShowRinging(ScheduledDose dose)
        {
            var panel = UIKit.Panel(overlayLayer, UIKit.Primary, false, "Ringing");
            UIKit.Stretch(panel.rectTransform);
            ringOverlay = panel.gameObject;
            var v = panel.gameObject.AddComponent<VerticalLayoutGroup>();
            UIKit.Configure(v, 24, 72);
            v.childAlignment = TextAnchor.MiddleCenter;

            UIKit.Label(panel.transform, TimeUtil.Clock(DateTime.Now), 150, Color.white, FontStyle.Bold, TextAnchor.MiddleCenter);
            UIKit.Label(panel.transform, "Time for your medicine", 48, UIKit.Hex("BFE3F0"), FontStyle.Normal, TextAnchor.MiddleCenter);
            UIKit.Spacer(panel.transform, 40);
            UIKit.Label(panel.transform, dose.Med.name, 76, Color.white, FontStyle.Bold, TextAnchor.MiddleCenter);
            UIKit.Label(panel.transform, dose.Med.dose + "\nScheduled " + TimeUtil.Clock(dose.Time) +
                        (string.IsNullOrEmpty(dose.Med.instructions) ? "" : "\n" + dose.Med.instructions),
                        44, Color.white, FontStyle.Normal, TextAnchor.MiddleCenter);
            UIKit.Spacer(panel.transform, 60);

            string key = dose.Key;
            if (dose.Med.observed)
            {
                UIKit.Label(panel.transform, "This dose must be taken in front of the camera.", 38, UIKit.Hex("FFE08A"), FontStyle.Normal, TextAnchor.MiddleCenter);
                UIKit.Button(panel.transform, "Take on camera", UIKit.Good, () => { CloseRinging(); StartObservation(key); }, 150, 48);
            }
            else
            {
                UIKit.Button(panel.transform, "I have taken it", UIKit.Good, () => { CloseRinging(); RecordAction(key, DoseStatus.Taken); }, 150, 48);
            }
            UIKit.Button(panel.transform, "Snooze " + D.settings.snoozeMinutes + " min", UIKit.Warn, () => { CloseRinging(); RecordAction(key, DoseStatus.Snoozed); }, 150, 48);
            UIKit.Button(panel.transform, "Skip this dose", UIKit.Muted, () => { CloseRinging(); RecordAction(key, DoseStatus.Skipped); }, 150, 48);

            // On Android the insistent notification is already ringing; in the Editor we ring here.
            if (!AlarmService.IsNative || !AlarmService.Ringing().Contains(key)) tone.Play();
        }

        void CloseRinging()
        {
            tone.Stop();
            if (ringOverlay != null) Destroy(ringOverlay);
            ringOverlay = null;
        }

        // ================================================================== observation

        void StartObservation(string doseKey)
        {
            if (observing != null) return;
            if (!DoseKey.TryParse(doseKey, out var medId, out _)) return;
            var med = D.FindMed(medId);
            if (med == null) return;
            CloseRinging();
            AlarmService.ShowOverLockScreen(true);
            observing = ObservationSession.Begin(overlayLayer, med, doseKey, result =>
            {
                observing = null;
                AlarmService.ShowOverLockScreen(false);
                if (!result.completed)
                {
                    // Cancelled: the dose is still owed; ring again after the snooze interval.
                    RecordAction(doseKey, DoseStatus.Snoozed);
                    return;
                }
                var rec = ScheduleEngine.Record(D, doseKey, DoseStatus.Taken, DateTime.Now);
                rec.Verification = result.verdict;
                rec.livenessScore = result.livenessScore;
                rec.presenceScore = result.presenceScore;
                rec.evidence = result.evidence;
                rec.note = string.Format("{0}/{1} observation steps confirmed", result.stepsPassed, result.stepsTotal);
                store.Save();
                AlarmService.Cancel(doseKey);
                Reschedule();
                Refresh();
            });
        }

        // ================================================================== Medicines

        bool EditingLocked => D.settings.lockEditingWithPin && !pharmacistUnlocked;

        void BuildMedicines(RectTransform body)
        {
            SetHeader("Medicines", D.medications.Count + " in the regimen");
            UIKit.Button(body, "+ Add medicine", UIKit.Accent, () => WithEditPermission(() => EditMedication(null)));

            foreach (var med in D.medications)
            {
                var m = med;
                var card = UIKit.Card(body);
                var row = UIKit.HBox(card.transform, 16);
                var title = UIKit.Label(row.transform, "<b>" + m.name + "</b>  " + m.dose, 44);
                UIKit.Size(title, -1, -1, 1);
                if (m.observed) UIKit.Badge(row.transform, "Observed", UIKit.Primary);
                if (!m.Active) UIKit.Badge(row.transform, "Paused", UIKit.Muted);

                string freq = m.everyNDays == 1 ? "daily" : m.everyNDays == 7 ? "weekly" : "every " + m.everyNDays + " days";
                string course = m.durationDays > 0
                    ? string.Format("{0} days, {1} to {2}", m.durationDays, m.startDate, TimeUtil.Date(m.EndDate.Value))
                    : "ongoing from " + m.startDate;
                UIKit.Label(card.transform, m.TimesLabel + " " + freq + "\n" + course, 34, UIKit.Muted);
                if (!string.IsNullOrEmpty(m.instructions)) UIKit.Label(card.transform, m.instructions, 34, UIKit.Muted, FontStyle.Italic);
                if (m.TracksStock)
                {
                    bool low = Inventory.NeedsRefill(m, DateTime.Now);
                    UIKit.Label(card.transform, (low ? "Refill soon: " : "Stock: ") + Inventory.Label(m), 34,
                        low ? UIKit.Bad : UIKit.Muted, low ? FontStyle.Bold : FontStyle.Normal);
                }
                if (m.EndDate.HasValue && m.EndDate.Value < DateTime.Today)
                    UIKit.Label(card.transform, "Course completed", 34, UIKit.Good, FontStyle.Bold);

                var actions = UIKit.HBox(card.transform, 16);
                actions.childForceExpandWidth = true;
                UIKit.Button(actions.transform, "Edit", UIKit.Primary, () => WithEditPermission(() => EditMedication(m)), 100, 34);
                UIKit.Button(actions.transform, "Refill", UIKit.Accent, () => AskRefill(m), 100, 34);
                UIKit.Button(actions.transform, m.Active ? "Pause" : "Resume", UIKit.Warn, () => WithEditPermission(() =>
                {
                    if (m.Active) m.Pause(DateTime.Now); else m.Resume(DateTime.Now);
                    store.Save(); Reschedule(); Refresh();
                }), 100, 34);
                UIKit.Button(actions.transform, "Delete", UIKit.Bad, () => WithEditPermission(() =>
                    Confirm("Delete " + m.name + " and its dose history? To stop reminders but keep the history, use Pause.", () =>
                    {
                        store.Remove(m.id);
                        D.records.RemoveAll(r => r.medId == m.id);
                        store.Save(); Reschedule(); Refresh();
                    })), 100, 34);
            }
        }

        void WithEditPermission(Action action)
        {
            if (!EditingLocked) { action(); return; }
            AskPin(action);
        }

        /// <summary>Add / edit form. Presets make entry a few taps: pick frequency, pick duration, save.</summary>
        void EditMedication(Medication existing)
        {
            var m = existing != null ? JsonUtility.FromJson<Medication>(JsonUtility.ToJson(existing)) : new Medication
            {
                startDate = TimeUtil.Date(DateTime.Today),
                times = new List<string>(RegimenParser.FindPreset("BD").times),
                durationDays = 7,
                addedBy = pharmacistUnlocked ? "pharmacist" : "patient",
            };
            BuildForm(m, existing == null, null);
        }

        void BuildForm(Medication m, bool isNew, string error)
        {
            SetHeader(isNew ? "Add medicine" : "Edit medicine", "Name, dose, times and how long to take it");
            UIKit.Clear(content);
            var body = UIKit.Scroll(content);

            var card = UIKit.Card(body, null, 32, 16);
            var name = UIKit.Field(card.transform, "Medicine name *", "e.g. Metformin", m.name);
            var dose = UIKit.Field(card.transform, "Dose", "e.g. 500 mg, 1 tablet", m.dose);
            var instr = UIKit.Field(card.transform, "Instructions", "e.g. after food", m.instructions);

            UIKit.Label(card.transform, "How often", 34, UIKit.Muted, FontStyle.Bold);
            InputField times = null, start = null, days = null, stockIn = null, perDoseIn = null;
            Action capture = () =>
            {
                m.name = name.text.Trim(); m.dose = dose.text.Trim(); m.instructions = instr.text.Trim();
                if (RegimenParser.TryParseTimes(times.text, out var ts, out var every, out _)) { m.times = ts; if (every > 1) m.everyNDays = every; }
                m.startDate = start.text.Trim();
                if (int.TryParse(days.text, out var dd)) m.durationDays = Math.Max(0, dd);
                if (stockIn != null)
                {
                    if (stockIn.text.Trim().Length == 0) m.stock = -1;
                    else if (RegimenParser.TryParseNumber(stockIn.text, out var st) && st >= 0) m.stock = st;
                    if (RegimenParser.TryParseNumber(perDoseIn.text, out var pd) && pd > 0) m.unitsPerDose = pd;
                }
            };

            var presetRow1 = UIKit.HBox(card.transform, 12);
            var presetRow2 = UIKit.HBox(card.transform, 12);
            int idx = 0;
            foreach (var p in RegimenParser.Presets)
            {
                var preset = p;
                bool sel = m.everyNDays == p.everyNDays && m.times.SequenceEqual(p.times);
                UIKit.Chip((idx++ < 4 ? presetRow1 : presetRow2).transform, p.code, sel, () =>
                {
                    capture();
                    m.times = new List<string>(preset.times);
                    m.everyNDays = preset.everyNDays;
                    BuildForm(m, isNew, null);
                });
            }
            times = UIKit.Field(card.transform, "Times (24 h, edit freely)", "08:00 20:00", string.Join(" ", m.times.ToArray()));
            UIKit.Label(card.transform, m.everyNDays == 1 ? "Every day" : "Every " + m.everyNDays + " days", 32, UIKit.Muted);

            start = UIKit.Field(card.transform, "Start date (yyyy-MM-dd)", "yyyy-MM-dd", m.startDate);
            var startRow = UIKit.HBox(card.transform, 12);
            UIKit.Chip(startRow.transform, "Today", m.startDate == TimeUtil.Date(DateTime.Today), () => { capture(); m.startDate = TimeUtil.Date(DateTime.Today); BuildForm(m, isNew, null); });
            UIKit.Chip(startRow.transform, "Tomorrow", m.startDate == TimeUtil.Date(DateTime.Today.AddDays(1)), () => { capture(); m.startDate = TimeUtil.Date(DateTime.Today.AddDays(1)); BuildForm(m, isNew, null); });

            days = UIKit.Field(card.transform, "Duration in days (0 = ongoing)", "7", m.durationDays.ToString(), InputField.ContentType.IntegerNumber);
            var durRow = UIKit.HBox(card.transform, 12);
            foreach (var d in new[] { 3, 5, 7, 10, 14, 30, 0 })
            {
                int dd = d;
                UIKit.Chip(durRow.transform, d == 0 ? "Ongoing" : d.ToString(), m.durationDays == d, () => { capture(); m.durationDays = dd; BuildForm(m, isNew, null); });
            }

            UIKit.Toggle(card.transform, "Observed dose: patient takes it in front of the camera", m.observed, v => m.observed = v);

            var stockRow = UIKit.HBox(card.transform, 16);
            stockRow.childForceExpandWidth = true;
            var stockCol = UIKit.VBox(stockRow.transform, 8);
            stockIn = UIKit.Field(stockCol.transform, "Units in stock", "blank = don't track",
                m.TracksStock ? m.stock.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture) : "", InputField.ContentType.DecimalNumber);
            var perCol = UIKit.VBox(stockRow.transform, 8);
            perDoseIn = UIKit.Field(perCol.transform, "Units per dose", "1",
                m.unitsPerDose.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture), InputField.ContentType.DecimalNumber);
            UIKit.Label(card.transform, "With stock entered, the app counts down each dose taken and warns before it runs out.", 30, UIKit.Muted);

            if (error != null) UIKit.Label(body, error, 36, UIKit.Bad, FontStyle.Bold);

            var actions = UIKit.HBox(body, 16);
            actions.childForceExpandWidth = true;
            UIKit.Button(actions.transform, "Cancel", UIKit.Muted, () => Show(Tab.Medicines));
            UIKit.Button(actions.transform, "Save", UIKit.Good, () =>
            {
                capture();
                string err = null;
                if (m.name.Length == 0) err = "Please enter the medicine name.";
                else if (!RegimenParser.TryParseTimes(times.text, out _, out _, out var tErr)) err = tErr + ". Use times like 08:00 20:00.";
                else if (!DateTime.TryParseExact(m.startDate, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture,
                             System.Globalization.DateTimeStyles.None, out _)) err = "Start date must look like 2026-10-01.";
                else if (stockIn.text.Trim().Length > 0 && (!RegimenParser.TryParseNumber(stockIn.text, out var sv) || sv < 0))
                    err = "Stock must be a number of units, e.g. 30, or left blank.";
                else if (!RegimenParser.TryParseNumber(perDoseIn.text, out var pv) || pv <= 0)
                    err = "Units per dose must be more than 0, e.g. 1 or 0.5.";
                if (err != null) { BuildForm(m, isNew, err); return; }
                store.Upsert(m);
                Reschedule();
                Show(Tab.Medicines);
            });
        }

        // ================================================================== Adherence

        void BuildAdherence(RectTransform body)
        {
            var now = DateTime.Now;
            DateTime from = adherenceDays > 0 ? now.Date.AddDays(-(adherenceDays - 1)) : EarliestStart();
            var r = AdherenceCalculator.Compute(D, from, now);
            SetHeader("Adherence", adherenceDays > 0 ? "Last " + adherenceDays + " days" : "Since the first dose");

            var periods = UIKit.HBox(body, 12);
            foreach (var p in new[] { 7, 30, 90, 0 })
            {
                int pp = p;
                UIKit.Chip(periods.transform, p == 0 ? "All" : p + " days", adherenceDays == p, () => { adherenceDays = pp; Refresh(); });
            }

            var o = r.overall;
            var hero = UIKit.Card(body);
            UIKit.Label(hero.transform, string.Format("{0:0}%", o.TakingPercent), 150, UIKit.ColorFor(o.TakingPercent), FontStyle.Bold, TextAnchor.MiddleCenter);
            UIKit.Label(hero.transform, o.Category, 46, UIKit.Ink, FontStyle.Bold, TextAnchor.MiddleCenter);
            UIKit.Label(hero.transform, string.Format("{0} of {1} doses taken  |  {2} missed  |  {3} skipped  |  streak {4} day(s)",
                o.taken, o.due, o.missed, o.skipped, r.currentStreakDays), 32, UIKit.Muted, FontStyle.Normal, TextAnchor.MiddleCenter);

            var bars = UIKit.Card(body);
            UIKit.Bar(bars.transform, "Doses taken", o.TakingPercent);
            UIKit.Bar(bars.transform, "Taken on time (within " + D.settings.onTimeWindowMinutes + " min)", o.TimingPercent);
            UIKit.Bar(bars.transform, "Days fully covered", o.DaysCoveredPercent, string.Format("  ({0}/{1} days)", o.daysCovered, o.daysElapsed));
            if (o.observedDue > 0)
                UIKit.Bar(bars.transform, "Observed doses verified", o.VerifiedPercent, string.Format("  ({0}/{1})", o.observedVerified, o.observedDue));

            // Last 14 days strip
            var hist = UIKit.Card(body);
            UIKit.Label(hist.transform, "Last 14 days", 38, UIKit.Ink, FontStyle.Bold);
            var strip = UIKit.HBox(hist.transform, 6);
            strip.childForceExpandWidth = true;
            for (int i = 13; i >= 0; i--)
            {
                var day = now.Date.AddDays(-i);
                var ds = AdherenceCalculator.Compute(D, day, i == 0 ? now : day.AddDays(1).AddTicks(-1)).overall;
                var cell = UIKit.Panel(strip.transform, ds.due == 0 ? UIKit.Line : UIKit.ColorFor(ds.TakingPercent), true, "Day");
                UIKit.Size(cell, 110);
                var l = UIKit.Label(cell.transform, day.Day.ToString(), 28, ds.due == 0 ? UIKit.Muted : Color.white, FontStyle.Bold, TextAnchor.MiddleCenter);
                UIKit.Stretch(l.rectTransform);
            }
            UIKit.Label(hist.transform, "Green = all doses taken, amber = some, red = most missed, grey = nothing due.", 28, UIKit.Muted);

            foreach (var s in r.perMedication)
            {
                var c = UIKit.Card(body);
                UIKit.Label(c.transform, "<b>" + s.label + "</b>  " + s.Category, 40);
                UIKit.Bar(c.transform, "Doses taken", s.TakingPercent, string.Format("  ({0}/{1})", s.taken, s.due));
                UIKit.Label(c.transform, string.Format("On time {0:0}%  |  late {1}  |  missed {2}  |  skipped {3}", s.TimingPercent, s.late, s.missed, s.skipped), 32, UIKit.Muted);
            }

            var share = UIKit.HBox(body, 16);
            share.childForceExpandWidth = true;
            UIKit.Button(share.transform, "Share report", UIKit.Primary, () =>
                AlarmService.ShareText("Medication adherence report", AdherenceCalculator.ToText(D, r)), 120, 36);
            UIKit.Button(share.transform, "Export dose log", UIKit.Accent, () =>
            {
                string csv = AdherenceCalculator.DoseLogCsv(D, from, now);
                string path = store.WriteExport("dose_log_" + now.ToString("yyyyMMdd_HHmm") + ".csv", csv);
                AlarmService.ShareText("Dose log (CSV)", csv);
                Toast("Saved " + Path.GetFileName(path));
            }, 120, 36);
        }

        DateTime EarliestStart()
        {
            var d = DateTime.Today;
            foreach (var m in D.medications) if (m.StartDate < d) d = m.StartDate;
            return d;
        }

        // ================================================================== Pharmacist

        void BuildPharmacist(RectTransform body)
        {
            if (!pharmacistUnlocked)
            {
                SetHeader("Pharmacist", "Enter the PIN to continue");
                var c = UIKit.Card(body);
                UIKit.Label(c.transform, "Pharmacist tools: bulk regimen import, evidence review, settings. Default PIN is 0000 - change it after first use.", 36, UIKit.Muted);
                var pin = UIKit.Input(c.transform, "PIN", "", InputField.ContentType.Pin);
                var msg = UIKit.Label(c.transform, "", 34, UIKit.Bad);
                UIKit.Button(c.transform, "Unlock", UIKit.Primary, () =>
                {
                    if (pin.text == D.settings.pharmacistPin) { pharmacistUnlocked = true; Refresh(); }
                    else msg.text = "Wrong PIN";
                });
                return;
            }

            SetHeader("Pharmacist", "Regimen, review and settings");
            BuildReviewQueue(body);
            BuildImport(body);
            BuildSettings(body);
            BuildReliability(body);
            UIKit.Button(body, "Lock pharmacist mode", UIKit.Muted, () => { pharmacistUnlocked = false; Refresh(); });
        }

        void BuildReviewQueue(RectTransform body)
        {
            var queue = D.records.Where(r => r.Status == DoseStatus.Taken && r.Verification == VerificationStatus.NeedsReview)
                                 .OrderByDescending(r => r.scheduled).ToList();
            var c = UIKit.Card(body);
            UIKit.Label(c.transform, "Observed doses to review (" + queue.Count + ")", 42, UIKit.Ink, FontStyle.Bold);
            if (queue.Count == 0) UIKit.Label(c.transform, "Nothing waiting. Doses that pass every camera check are verified automatically.", 32, UIKit.Muted);

            foreach (var rec in queue.Take(10))
            {
                var r = rec;
                var med = D.FindMed(r.medId);
                UIKit.Label(c.transform, string.Format("<b>{0}</b>  scheduled {1}, taken {2}\n{3}  |  movement {4:0}%  |  person in view {5:0}%",
                    med != null ? med.name : "?", r.scheduled, r.actionAt, r.note, r.livenessScore * 100, r.presenceScore * 100), 32);
                var thumbs = UIKit.HBox(c.transform, 8);
                foreach (var file in r.evidence) Thumbnail(thumbs.transform, file);
                var row = UIKit.HBox(c.transform, 16);
                row.childForceExpandWidth = true;
                UIKit.Button(row.transform, "Approve", UIKit.Good, () => { r.Verification = VerificationStatus.PharmacistApproved; store.Save(); Refresh(); }, 100, 34);
                UIKit.Button(row.transform, "Reject", UIKit.Bad, () => { r.Verification = VerificationStatus.PharmacistRejected; store.Save(); Refresh(); }, 100, 34);
            }
        }

        void Thumbnail(Transform parent, string file)
        {
            var img = UIKit.Rect(parent, "Thumb").gameObject.AddComponent<RawImage>();
            UIKit.Size(img, 220, 160);
            try
            {
                if (!File.Exists(file)) return;
                var tex = new Texture2D(2, 2);
                tex.LoadImage(File.ReadAllBytes(file));
                img.texture = tex;
                UIKit.Size(img, 220, 220f * tex.width / tex.height);
                var b = img.gameObject.AddComponent<Button>();
                b.onClick.AddListener(() => ShowImage(tex));
            }
            catch (Exception e) { Debug.LogWarning(e.Message); }
        }

        void ShowImage(Texture tex)
        {
            OpenModal(m =>
            {
                var img = UIKit.Rect(m, "Image").gameObject.AddComponent<RawImage>();
                img.texture = tex;
                UIKit.Size(img, 1000f * tex.height / tex.width);
                UIKit.Button(m, "Close", UIKit.Primary, CloseModal);
            });
        }

        void BuildImport(RectTransform body)
        {
            var c = UIKit.Card(body);
            UIKit.Label(c.transform, "Load a full regimen", 42, UIKit.Ink, FontStyle.Bold);
            UIKit.Label(c.transform, "One medicine per line:  Name | Dose | Times or OD/BD/TDS/QID/HS/WEEKLY | Days (0 = ongoing) | Start | Observed yes/no | Instructions", 30, UIKit.Muted);
            var box = UIKit.Input(c.transform,
                "Metformin | 500 mg | BD | 30 | today | no | after food\nRifampicin | 600 mg | 07:00 | 180 | today | yes | empty stomach",
                "", InputField.ContentType.Standard, 420, true);
            bool replace = false;
            UIKit.Toggle(c.transform, "Replace the current regimen", false, v => replace = v);
            var msg = UIKit.Label(c.transform, "", 32, UIKit.Muted);
            var row = UIKit.HBox(c.transform, 16);
            row.childForceExpandWidth = true;
            UIKit.Button(row.transform, "Import", UIKit.Good, () =>
            {
                var res = RegimenParser.Import(box.text, DateTime.Today);
                if (res.medications.Count == 0 && res.errors.Count == 0) { msg.text = "Paste or type at least one line."; return; }
                if (res.medications.Count > 0)
                {
                    if (replace) D.medications.Clear();
                    D.medications.AddRange(res.medications);
                    store.Save();
                    Reschedule();
                }
                msg.text = "Imported " + res.medications.Count + " medicine(s)." +
                           (res.errors.Count > 0 ? "\n" + string.Join("\n", res.errors.ToArray()) : "");
                msg.color = res.errors.Count > 0 ? UIKit.Bad : UIKit.Good;
                if (res.errors.Count == 0) box.text = "";
            }, 110, 36);
            UIKit.Button(row.transform, "Paste", UIKit.Accent, () => box.text = GUIUtility.systemCopyBuffer, 110, 36);
            UIKit.Button(row.transform, "Share regimen", UIKit.Primary, () =>
                AlarmService.ShareText("Medication regimen", RegimenParser.Export(D.medications)), 110, 36);
        }

        void BuildSettings(RectTransform body)
        {
            var s = D.settings;
            var c = UIKit.Card(body, null, 32, 16);
            UIKit.Label(c.transform, "Settings", 42, UIKit.Ink, FontStyle.Bold);
            var name = UIKit.Field(c.transform, "Patient name", "optional", s.patientName);
            var grace = UIKit.Field(c.transform, "Minutes before a dose counts as missed", "120", s.graceMinutes.ToString(), InputField.ContentType.IntegerNumber);
            var window = UIKit.Field(c.transform, "On-time window, +/- minutes", "60", s.onTimeWindowMinutes.ToString(), InputField.ContentType.IntegerNumber);
            var snooze = UIKit.Field(c.transform, "Snooze minutes", "10", s.snoozeMinutes.ToString(), InputField.ContentType.IntegerNumber);
            var pin = UIKit.Field(c.transform, "New pharmacist PIN (leave empty to keep)", "", "", InputField.ContentType.Pin);
            bool locked = s.lockEditingWithPin;
            UIKit.Toggle(c.transform, "Patient needs the PIN to change medicines", locked, v => locked = v);
            UIKit.Button(c.transform, "Save settings", UIKit.Good, () =>
            {
                s.patientName = name.text.Trim();
                if (int.TryParse(grace.text, out var g)) s.graceMinutes = Mathf.Clamp(g, 15, 24 * 60);
                if (int.TryParse(window.text, out var w)) s.onTimeWindowMinutes = Mathf.Clamp(w, 5, 12 * 60);
                if (int.TryParse(snooze.text, out var sn)) s.snoozeMinutes = Mathf.Clamp(sn, 1, 120);
                if (pin.text.Length >= 4) s.pharmacistPin = pin.text;
                s.lockEditingWithPin = locked;
                store.Save();
                Reschedule();
                Toast("Settings saved");
                Refresh();
            });
        }

        void BuildReliability(RectTransform body)
        {
            var c = UIKit.Card(body);
            UIKit.Label(c.transform, "Alarm reliability", 42, UIKit.Ink, FontStyle.Bold);
            foreach (var check in AlarmService.ReliabilityChecks())
            {
                var row = UIKit.HBox(c.transform, 16);
                row.childAlignment = TextAnchor.MiddleLeft;
                var l = UIKit.Label(row.transform, (check.ok ? "<color=#2EA043><b>OK</b></color>  " : "<color=#D1453B><b>!</b></color>  ") + check.label, 34);
                UIKit.Size(l, -1, -1, 1);
                if (!check.ok && check.fix != null)
                {
                    var fix = check.fix;
                    UIKit.Size(UIKit.Button(row.transform, "Fix", UIKit.Accent, () => fix(), 90, 32), 90, 180);
                }
            }
            UIKit.Button(c.transform, "Test: ring in 1 minute", UIKit.Primary, () =>
            {
                testAlarmAt = DateTime.Now.AddMinutes(1);
                Reschedule();
                Toast("Lock the phone - it should ring in about a minute.");
            }, 110, 36);
        }

        // ================================================================== modals

        void OpenModal(Action<Transform> build)
        {
            CloseModal();
            var dim = UIKit.Panel(overlayLayer, new Color(0, 0, 0, 0.55f), false, "Modal");
            UIKit.Stretch(dim.rectTransform);
            modal = dim.gameObject;
            var card = UIKit.Card(dim.transform, null, 40, 24);
            var rt = (RectTransform)card.transform;
            rt.anchorMin = new Vector2(0.05f, 0.5f); rt.anchorMax = new Vector2(0.95f, 0.5f); rt.pivot = new Vector2(0.5f, 0.5f);
            card.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            build(card.transform);
        }

        void CloseModal()
        {
            if (modal != null) Destroy(modal);
            modal = null;
        }

        void Confirm(string message, Action onYes)
        {
            OpenModal(m =>
            {
                UIKit.Label(m, message, 40);
                var row = UIKit.HBox(m, 16);
                row.childForceExpandWidth = true;
                UIKit.Button(row.transform, "Cancel", UIKit.Muted, CloseModal);
                UIKit.Button(row.transform, "Yes", UIKit.Bad, () => { CloseModal(); onYes(); });
            });
        }

        void AskRefill(Medication med)
        {
            OpenModal(m =>
            {
                UIKit.Label(m, "Refill " + med.name, 44, UIKit.Ink, FontStyle.Bold);
                UIKit.Label(m, med.TracksStock ? "Now: " + Inventory.Label(med) : "Stock is not tracked yet. Enter what you have now.", 34, UIKit.Muted);
                var qty = UIKit.Input(m, "Units added, e.g. 30", "", InputField.ContentType.DecimalNumber);
                var msg = UIKit.Label(m, "", 34, UIKit.Bad);
                var row = UIKit.HBox(m, 16);
                row.childForceExpandWidth = true;
                UIKit.Button(row.transform, "Cancel", UIKit.Muted, CloseModal);
                UIKit.Button(row.transform, "Add", UIKit.Good, () =>
                {
                    if (!RegimenParser.TryParseNumber(qty.text, out var units) || units <= 0) { msg.text = "Enter how many units were added."; return; }
                    Inventory.Refill(med, units);
                    store.Save();
                    Reschedule();
                    CloseModal();
                    Toast(med.name + ": " + Inventory.Label(med));
                    Refresh();
                });
            });
        }

        void AskPin(Action onOk)
        {
            OpenModal(m =>
            {
                UIKit.Label(m, "Pharmacist PIN required to change the regimen", 40, UIKit.Ink, FontStyle.Bold);
                var pin = UIKit.Input(m, "PIN", "", InputField.ContentType.Pin);
                var msg = UIKit.Label(m, "", 34, UIKit.Bad);
                var row = UIKit.HBox(m, 16);
                row.childForceExpandWidth = true;
                UIKit.Button(row.transform, "Cancel", UIKit.Muted, CloseModal);
                UIKit.Button(row.transform, "OK", UIKit.Primary, () =>
                {
                    if (pin.text != D.settings.pharmacistPin) { msg.text = "Wrong PIN"; return; }
                    pharmacistUnlocked = true;
                    CloseModal();
                    onOk();
                });
            });
        }

        void Toast(string message)
        {
            var bg = UIKit.Panel(overlayLayer, new Color(0.1f, 0.12f, 0.14f, 0.92f), true, "Toast");
            var rt = bg.rectTransform;
            rt.anchorMin = new Vector2(0.08f, 0); rt.anchorMax = new Vector2(0.92f, 0); rt.pivot = new Vector2(0.5f, 0);
            rt.anchoredPosition = new Vector2(0, 220); rt.sizeDelta = new Vector2(0, 130);
            var l = UIKit.Label(bg.transform, message, 34, Color.white, FontStyle.Normal, TextAnchor.MiddleCenter);
            UIKit.Stretch(l.rectTransform, 24, 24, 0, 0);
            Destroy(bg.gameObject, 3f);
        }
    }
}
