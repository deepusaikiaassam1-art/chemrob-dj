package com.chemrob.medadherence.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import com.chemrob.medadherence.R;
import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Rewards;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;

import java.time.LocalDateTime;
import java.util.List;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * Home-screen widget: the dose that is due or comes next, today's progress and the streak.
 * Refreshed whenever the alarms are re-planned (every dose, change, boot) and every 30 minutes.
 */
public class NextDoseWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) { update(ctx, mgr, ids); }

    /** Refreshes every widget on the home screen. */
    public static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NextDoseWidget.class));
            if (ids != null && ids.length > 0) update(ctx, mgr, ids);
        } catch (Exception e) {
            Log.w("MedAdherence", "Widget update failed", e);
        }
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        AppData d = Store.get(ctx);
        LocalDateTime now = LocalDateTime.now();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_dose);

        ScheduledDose due = null, next = null;
        for (ScheduledDose x : ScheduleEngine.doses(d, now.minusMinutes(d.settings.graceMinutes), now.plusNanos(1)))
            if (ScheduleEngine.isDueNow(d, x, now)) { due = x; break; }
        if (due == null)
            for (ScheduledDose x : ScheduleEngine.doses(d, now, now.plusDays(7)))
                if (ScheduleEngine.statusOf(d, x, now) == DoseStatus.PENDING) { next = x; break; }
        ScheduledDose show = due != null ? due : next;
        if (show == null) {
            v.setTextViewText(R.id.widget_label, t("MedAdherence"));
            v.setTextViewText(R.id.widget_med, t("No doses planned"));
            v.setTextViewText(R.id.widget_time, "");
        } else {
            v.setTextViewText(R.id.widget_label, t(due != null ? "Due now" : "Next"));
            v.setTextViewText(R.id.widget_med, show.med.name);
            String when = show.time.toLocalDate().equals(now.toLocalDate()) ? TimeUtil.clock(show.time)
                    : show.time.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", com.chemrob.medadherence.core.I18n.locale()));
            v.setTextViewText(R.id.widget_time, when + (show.med.dose.isEmpty() ? "" : "  ·  " + show.med.dose));
        }

        List<ScheduledDose> today = ScheduleEngine.doses(d, now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
        int taken = 0;
        for (ScheduledDose x : today) if (ScheduleEngine.statusOf(d, x, now) == DoseStatus.TAKEN) taken++;
        int streak = Rewards.compute(d, now).currentStreak;
        v.setTextViewText(R.id.widget_progress, tf("%d of %d today", taken, today.size())
                + (streak > 0 ? "  ·  " + tf("%d-day streak", streak) : ""));

        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 77, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(ids, v);
    }
}
