package br.com.redesurftank.havalcomfortcontrol.broadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalcomfortcontrol.services.ComfortControlService;

public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, ComfortControlService.class));
    }
}
