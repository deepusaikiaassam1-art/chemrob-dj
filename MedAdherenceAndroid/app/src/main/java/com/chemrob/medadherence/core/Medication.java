package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One drug in the patient's regimen. */
public final class Medication {
    public String id = UUID.randomUUID().toString().replace("-", "");
    public String name = "";
    public String dose = "";           // free text, e.g. "500 mg, 1 tablet"
    public String instructions = "";   // e.g. "after food"
    public List<String> times = new ArrayList<>(); // local clock times "HH:mm"
    public String startDate = "";      // yyyy-MM-dd
    public int durationDays;           // 0 = ongoing
    public int everyNDays = 1;         // 1 = daily, 7 = weekly
    public boolean observed;           // must be taken in front of the camera
    public List<String> pauses = new ArrayList<>(); // "yyyy-MM-dd HH:mm|yyyy-MM-dd HH:mm", open-ended if empty after '|'
    public String addedBy = "patient";
    public double stock = -1;          // units on hand; -1 = not tracked
    public double unitsPerDose = 1;
    public int refillAlertDays = 5;
    public String photo = "";          // path of the drug's photo in app storage, "" if none

    public LocalDate start() {
        LocalDate d = TimeUtil.parseDate(startDate);
        return d != null ? d : LocalDate.now();
    }

    /** Last calendar day (inclusive) of the course, or null when ongoing. */
    public LocalDate end() { return durationDays > 0 ? start().plusDays(durationDays - 1) : null; }

    public boolean tracksStock() { return stock >= 0; }

    public boolean isActive() { return pauses.isEmpty() || !pauses.get(pauses.size() - 1).endsWith("|"); }

    public void pause(LocalDateTime now) { if (isActive()) pauses.add(TimeUtil.minute(now) + "|"); }

    public void resume(LocalDateTime now) {
        if (!isActive()) pauses.set(pauses.size() - 1, pauses.get(pauses.size() - 1) + TimeUtil.minute(now));
    }

    /** Doses scheduled while paused are neither due nor missed. */
    public boolean isPausedAt(LocalDateTime t) {
        for (String p : pauses) {
            int bar = p.indexOf('|');
            if (bar < 0) continue;
            LocalDateTime from = TimeUtil.parseMinute(p.substring(0, bar));
            String endS = p.substring(bar + 1);
            LocalDateTime to = endS.isEmpty() ? null : TimeUtil.parseMinute(endS);
            if (from != null && !t.isBefore(from) && (to == null || t.isBefore(to))) return true;
        }
        return false;
    }

    public String timesLabel() { return String.join(", ", times); }

    public String frequencyLabel() {
        return everyNDays == 1 ? "daily" : everyNDays == 7 ? "weekly" : "every " + everyNDays + " days";
    }

    public Medication copy() {
        Medication m = new Medication();
        m.id = id; m.name = name; m.dose = dose; m.instructions = instructions;
        m.times = new ArrayList<>(times); m.startDate = startDate; m.durationDays = durationDays;
        m.everyNDays = everyNDays; m.observed = observed; m.pauses = new ArrayList<>(pauses);
        m.addedBy = addedBy; m.stock = stock; m.unitsPerDose = unitsPerDose; m.refillAlertDays = refillAlertDays; m.photo = photo;
        return m;
    }
}
