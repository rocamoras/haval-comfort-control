package br.com.redesurftank.havalcomfortcontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferências do app, todas num único arquivo em storage device-protected.
 *
 * Motivo de não haver um arquivo "de UI" separado: o serviço lê estas chaves no
 * LOCKED_BOOT_COMPLETED, antes do unlock. Um arquivo em credential storage leria
 * o default em todo boot frio — e é justamente no boot que o volume inicial e a
 * restauração de Bluetooth/âncora precisam estar corretos.
 */
public final class Prefs {

    public static final String FILE = "comfort_prefs";

    // ── Funcionalidades ────────────────────────────────────────────────
    public static final String CLOSE_WINDOWS_ON_FOLD_MIRROR = "close_windows_on_fold_mirror";
    public static final String DISABLE_BLUETOOTH_ON_POWER_OFF = "disable_bluetooth_on_power_off";
    public static final String DISABLE_HOTSPOT_ON_POWER_OFF   = "disable_hotspot_on_power_off";
    public static final String KEEP_DISTRACTION_DISABLED      = "keep_distraction_disabled";
    public static final String SET_STARTUP_VOLUME             = "set_startup_volume";
    public static final String STARTUP_VOLUME                 = "startup_volume";

    // ── Estado interno (não aparece na UI) ─────────────────────────────
    /** Bluetooth estava ligado quando desligamos o carro → restaurar na próxima partida. */
    public static final String BT_RESTORE_PENDING      = "bt_restore_pending";
    /** Idem para a âncora de Wi-Fi. */
    public static final String HOTSPOT_RESTORE_PENDING = "hotspot_restore_pending";
    /** Último estado da âncora visto por broadcast — fallback do getWifiApState(). */
    public static final String HOTSPOT_LAST_KNOWN_ON   = "hotspot_last_known_on";
    /**
     * Volume inicial já aplicado neste ciclo de ignição. Sem isso, um restart do
     * serviço no meio da viagem jogaria o volume de volta para o configurado.
     */
    public static final String VOLUME_APPLIED_THIS_CYCLE = "volume_applied_this_cycle";
    public static final String LAST_UPDATE_CHECK_MS      = "last_update_check_ms";

    // ── Defaults ──────────────────────────────────────────────────────
    public static final boolean DEF_CLOSE_WINDOWS_ON_FOLD_MIRROR = true;
    public static final boolean DEF_DISABLE_BLUETOOTH            = true;
    public static final boolean DEF_DISABLE_HOTSPOT              = true;
    public static final boolean DEF_KEEP_DISTRACTION_DISABLED    = true;
    public static final boolean DEF_SET_STARTUP_VOLUME           = true;
    public static final int     DEF_STARTUP_VOLUME               = 10;
    public static final int     VOLUME_MIN                       = 0;
    public static final int     VOLUME_MAX                       = 40;

    public static SharedPreferences get(Context anyContext) {
        Context ctx;
        try {
            ctx = App.getDeviceProtectedContext();
        } catch (Exception e) {
            ctx = anyContext.createDeviceProtectedStorageContext();
        }
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private Prefs() {}
}
