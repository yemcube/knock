package com.littyl.knock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Test trigger for devices without a desk to knock on (emulators, store videos):
 * {@code adb shell am broadcast -a com.littyl.knock.DEMO -n com.littyl.knock/.DemoKnock --ei spot 0 --ei count 2}
 * runs that command exactly as a recognised knock would. The manifest protects it with
 * android.permission.DUMP, which only the shell and the system hold, so no app can send it.
 */
public class DemoKnock extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        KnockService s = KnockService.running;
        if (s == null) return;
        int spot = Math.max(0, Math.min(1, i.getIntExtra("spot", 0)));
        int count = i.getIntExtra("count", 2) == 3 ? 3 : 2;
        s.simulate(spot, count);
    }
}
