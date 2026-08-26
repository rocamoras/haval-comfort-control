package br.com.redesurftank.havalcomfortcontrol;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import br.com.redesurftank.havalcomfortcontrol.services.ComfortControlService;
import br.com.redesurftank.havalcomfortcontrol.utils.PersistentLog;

public class App extends Application {

    private static Application sApplication;
    private static Context deviceProtectedContext;

    public static Application getApplication() {
        return sApplication;
    }

    public static Context getContext() {
        return getApplication().getApplicationContext();
    }

    /**
     * Storage device-protected: o serviço é directBootAware e lê as preferências no
     * LOCKED_BOOT_COMPLETED, antes do unlock. Em credential storage o volume inicial
     * leria o default em todo boot frio — exatamente o caso que precisa funcionar.
     */
    public synchronized static Context getDeviceProtectedContext() {
        if (deviceProtectedContext == null) {
            deviceProtectedContext = getApplication().createDeviceProtectedStorageContext();
        }
        return deviceProtectedContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApplication = this;

        // WifiManager.getWifiApState() é @hide — sem isso a leitura do estado da
        // âncora cai sempre no fallback do último estado visto por broadcast.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/net/wifi/WifiManager;");
            } catch (Throwable ignored) {}
        }

        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        PersistentLog.logProcessStart(version);

        startForegroundService(new Intent(getContext(), ComfortControlService.class));
    }
}
