package com.chemrob.medadherence.core;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Points, levels, streaks and badges that reward taking medicines. Everything is worked out from
 * the dose history, so nothing extra is stored and the numbers always agree with the adherence
 * report.
 *
 * Points: 10 for a dose taken on time, 5 for a late one, +5 when an observed dose is verified,
 * +20 for every day on which all doses were taken.
 */
public final class Rewards {
    private Rewards() {}

    public static final int ON_TIME = 10, LATE = 5, VERIFIED_BONUS = 5, FULL_DAY = 20;

    /** Points needed to reach each level; level 1 starts at 0. */
    static final int[] LEVEL_AT = {0, 100, 300, 600, 1000, 1500, 2200, 3000, 4000, 5500};
    static final String[] LEVEL_NAMES = {"Starter", "Regular", "Steady", "Reliable", "Committed",
            "Dedicated", "Champion", "Hero", "Master", "Legend"};

    public static final class Badge {
        public final String id, title, description;
        public final int target, progress;

        Badge(String id, String title, String description, int target, int progress) {
            this.id = id; this.title = title; this.description = description;
            this.target = target; this.progress = Math.min(progress, target);
        }

        public boolean earned() { return progress >= target; }
    }

    public static final class State {
        public int points, level, levelFloor, nextLevelAt; // nextLevelAt = -1 at the top level
        public String levelName;
        public int taken, onTime, verified, currentStreak, bestStreak, fullDays;
        public List<Badge> badges = new ArrayList<>();

        /** Progress towards the next level, 0-1. */
        public double levelProgress() {
            return nextLevelAt < 0 ? 1 : (double) (points - levelFloor) / (nextLevelAt - levelFloor);
        }

        public int earnedCount() {
            int n = 0;
            for (Badge b : badges) if (b.earned()) n++;
            return n;
        }

        /** The unearned badge closest to being earned, or null when all are earned. */
        public Badge nextBadge() {
            Badge best = null;
            for (Badge b : badges) {
                if (b.earned()) continue;
                if (best == null || (double) b.progress / b.target > (double) best.progress / best.target) best = b;
            }
            return best;
        }
    }

    public static State compute(AppData d, LocalDateTime now) {
        State s = new State();
        LocalDate first = now.toLocalDate();
        for (Medication m : d.medications) if (m.start().isBefore(first)) first = m.start();

        Map<LocalDate, Boolean> days = new TreeMap<>();
        Set<LocalDate> open = new HashSet<>(); // days with doses still to come
        Map<String, int[]> perMed = new HashMap<>(); // medId -> {due, taken}
        int window = d.settings.onTimeWindowMinutes;
        for (ScheduledDose x : ScheduleEngine.doses(d, first.atStartOfDay(), now.plusNanos(1))) {
            DoseStatus st = ScheduleEngine.statusOf(d, x, now);
            if (st == DoseStatus.PENDING || st == DoseStatus.SNOOZED) { open.add(x.time.toLocalDate()); continue; }
            boolean took = st == DoseStatus.TAKEN;
            days.merge(x.time.toLocalDate(), took, Boolean::logicalAnd);
            int[] pm = perMed.computeIfAbsent(x.med.id, k -> new int[2]);
            pm[0]++;
            if (!took) continue;
            pm[1]++;
            s.taken++;
            DoseRecord rec = d.findRecord(x.key());
            LocalDateTime at = rec == null ? null : rec.actionTime();
            boolean onTime = at == null || Math.abs(Duration.between(x.time, at).toMinutes()) <= window;
            if (onTime) { s.onTime++; s.points += ON_TIME; } else s.points += LATE;
            if (rec != null && (rec.verification == Verification.AUTO_VERIFIED || rec.verification == Verification.PHARMACIST_APPROVED)) {
                s.verified++;
                s.points += VERIFIED_BONUS;
            }
        }

        LocalDate today = now.toLocalDate();
        if (!ScheduleEngine.doses(d, now.plusNanos(1), today.plusDays(1).atStartOfDay()).isEmpty()) open.add(today);
        // A day earns its bonus only once every dose is settled; a miss still counts at once.
        for (LocalDate o : open) if (Boolean.TRUE.equals(days.get(o))) days.remove(o);
        // Days with nothing due are not in the map, so they neither count nor break a streak.
        int run = 0;
        for (boolean fullDay : days.values()) {
            if (fullDay) {
                s.fullDays++;
                s.points += FULL_DAY;
                s.bestStreak = Math.max(s.bestStreak, ++run);
            } else run = 0;
        }
        List<Boolean> order = new ArrayList<>(days.values());
        for (int i = order.size() - 1; i >= 0 && order.get(i); i--) s.currentStreak++;

        s.level = 1;
        for (int i = 0; i < LEVEL_AT.length; i++) if (s.points >= LEVEL_AT[i]) s.level = i + 1;
        s.levelName = LEVEL_NAMES[s.level - 1];
        s.levelFloor = LEVEL_AT[s.level - 1];
        s.nextLevelAt = s.level < LEVEL_AT.length ? LEVEL_AT[s.level] : -1;

        int courses = 0;
        for (Medication m : d.medications) {
            int[] pm = perMed.get(m.id);
            if (m.end() != null && m.end().isBefore(today) && pm != null && pm[0] > 0 && pm[0] == pm[1]) courses++;
        }

        s.badges.add(new Badge("first", "First dose", "Take your first dose", 1, s.taken));
        s.badges.add(new Badge("streak3", "3-day streak", "Take every dose 3 days in a row", 3, s.bestStreak));
        s.badges.add(new Badge("streak7", "Perfect week", "Take every dose 7 days in a row", 7, s.bestStreak));
        s.badges.add(new Badge("ontime", "On the dot", "Take 20 doses on time", 20, s.onTime));
        s.badges.add(new Badge("course", "Course complete", "Finish a whole course without missing a dose", 1, courses));
        s.badges.add(new Badge("camera", "Camera star", "5 doses verified on camera", 5, s.verified));
        s.badges.add(new Badge("streak30", "30-day streak", "Take every dose 30 days in a row", 30, s.bestStreak));
        s.badges.add(new Badge("hundred", "100 doses", "Take 100 doses", 100, s.taken));
        return s;
    }

    /** Badges earned in {@code after} that were not earned in {@code before}. */
    public static List<Badge> newlyEarned(State before, State after) {
        List<Badge> out = new ArrayList<>();
        for (Badge b : after.badges) {
            if (!b.earned()) continue;
            boolean had = false;
            for (Badge o : before.badges) if (o.id.equals(b.id) && o.earned()) had = true;
            if (!had) out.add(b);
        }
        return out;
    }
}
