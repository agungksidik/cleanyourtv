package io.github.agungksidik.football;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Makes sure the job exists after a reboot (it is persisted, so this is normally a no-op). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) MatchJob.schedule(context);
    }
}
