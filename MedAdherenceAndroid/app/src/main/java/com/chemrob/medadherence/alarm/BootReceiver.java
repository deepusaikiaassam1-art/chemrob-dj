package com.chemrob.medadherence.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager forgets everything on reboot, update or clock change; plan again from the regimen. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        AlarmScheduler.syncAll(ctx);
    }
}
