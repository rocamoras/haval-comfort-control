package br.com.redesurftank.havalcomfortcontrol.broadcastReceivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import br.com.redesurftank.havalcomfortcontrol.services.ComfortControlService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "boot (" + intent.getAction() + ") — subindo o ComfortControlService");
        context.startForegroundService(new Intent(context, ComfortControlService.class));
    }
}
