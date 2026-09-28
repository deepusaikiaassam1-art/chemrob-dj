package com.chemrob.medalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Iterator;

/** AlarmManager forgets everything on reboot; re-arm every stored dose alarm. */
public class BootReceiver extends BroadcastReceiver {
    /** A dose that came due while the phone was off still rings if it is at most this old. */
    static final long CATCH_UP_MS = 60 * 60 * 1000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long now = System.currentTimeMillis();
        JSONObject all = MedAlarmPlugin.alarms(ctx);
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject a = all.optJSONObject(key);
            if (a == null) continue;
            long at = a.optLong("at", 0);
            if (at >= now) MedAlarmPlugin.setAlarm(ctx, key, 0, at);
            else if (now - at <= CATCH_UP_MS) MedAlarmPlugin.setAlarm(ctx, key, 0, now + 5_000L);
        }
    }
}
