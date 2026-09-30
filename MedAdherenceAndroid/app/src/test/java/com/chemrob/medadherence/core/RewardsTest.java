package com.chemrob.medadherence.core;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.Assert.*;

public class RewardsTest {
    private static void take(AppData d, Medication m, LocalDateTime t, int minutesLate) {
        ScheduleEngine.record(d, new ScheduledDose(m, t).key(), DoseStatus.TAKEN, t.plusMinutes(minutesLate));
    }

    @Test public void pointsStreaksLevelsAndBadges() {
        Medication m = CoreTest.med("08:00, 20:00", 0, "2026-09-20", 1);
        AppData d = CoreTest.with(m);
        LocalDate day = LocalDate.of(2026, 9, 20);
        for (int i = 0; i < 8; i++, day = day.plusDays(1)) {
            if (i != 5) take(d, m, day.atTime(8, 0), 5); // 25 Sept morning is missed
            take(d, m, day.atTime(20, 0), 5);
        }
        take(d, m, LocalDateTime.of(2026, 9, 28, 8, 0), 5);
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 10, 0); // evening dose still to come

        Rewards.State s = Rewards.compute(d, now);
        assertEquals(16, s.taken);
        assertEquals(16, s.onTime);
        // 16 on-time doses + 7 full days (20-24, 26, 27); 28 Sept is not finished yet.
        assertEquals(16 * Rewards.ON_TIME + 7 * Rewards.FULL_DAY, s.points);
        assertEquals(7, s.fullDays);
        assertEquals(5, s.bestStreak);
        assertEquals(2, s.currentStreak);
        assertEquals(3, s.level);
        assertEquals("Steady", s.levelName);
        assertEquals(600, s.nextLevelAt);
        assertEquals(0.0, s.levelProgress(), 1e-9);

        assertTrue(badge(s, "first").earned());
        assertTrue(badge(s, "streak3").earned());
        assertFalse(badge(s, "streak7").earned());
        assertEquals(5, badge(s, "streak7").progress);
        assertEquals("ontime", s.nextBadge().id); // 16 of 20 on time is closer than 5 of 7 days

        // The evening dose, taken late: +5 points and the day counts.
        take(d, m, LocalDateTime.of(2026, 9, 28, 20, 0), 180);
        Rewards.State after = Rewards.compute(d, LocalDateTime.of(2026, 9, 28, 23, 30));
        assertEquals(s.points + Rewards.LATE + Rewards.FULL_DAY, after.points);
        assertEquals(3, after.currentStreak);
        assertTrue(Rewards.newlyEarned(s, after).isEmpty());
    }

    @Test public void courseAndCameraBadges() {
        Medication m = CoreTest.med("09:00", 3, "2026-09-01", 1);
        m.observed = true;
        AppData d = CoreTest.with(m);
        Rewards.State before = Rewards.compute(d, LocalDateTime.of(2026, 9, 10, 12, 0));
        assertEquals(0, before.points);
        assertEquals(1, before.level);
        for (int i = 1; i <= 3; i++) {
            LocalDateTime t = LocalDateTime.of(2026, 9, i, 9, 0);
            take(d, m, t, 0);
            d.findRecord(new ScheduledDose(m, t).key()).verification = Verification.AUTO_VERIFIED;
        }
        Rewards.State s = Rewards.compute(d, LocalDateTime.of(2026, 9, 10, 12, 0));
        assertTrue(badge(s, "course").earned());
        assertEquals(3, s.verified);
        assertEquals(3 * (Rewards.ON_TIME + Rewards.VERIFIED_BONUS + Rewards.FULL_DAY), s.points);
        assertEquals(3, Rewards.newlyEarned(before, s).size()); // first dose, 3-day streak, course
    }

    private static Rewards.Badge badge(Rewards.State s, String id) {
        for (Rewards.Badge b : s.badges) if (b.id.equals(id)) return b;
        throw new AssertionError(id);
    }
}
