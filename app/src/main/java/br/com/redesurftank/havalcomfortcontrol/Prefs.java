package br.com.redesurftank.havalcomfortcontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferências do app, todas num único arquivo em storage device-protected.
 *
 * Motivo de não haver um arquivo "de UI" separado: o serviço lê estas chaves no
 * LOCKED_BOOT_COMPLETED, antes do unlock. Um arquivo em credential storage leria
 * o default em todo boot frio — e é justamente no boot que o volume inicial e a
 * recuperação de um pisca interrompido ([BOUNCE_PENDING]) precisam estar corretos.
 */
public final class Prefs {

    public static final String FILE = "comfort_prefs";

    // ── Funcionalidades ────────────────────────────────────────────────
    /**
     * Gatilho das funcionalidades 1 e 2: carro TRANCADO, em P, com motor desligado.
     *
     * Antes o gatilho dos vidros era o rebatimento dos retrovisores e o dos rádios era
     * a transição de driving_ready. Os dois foram trocados por "trancou o carro", que é
     * o momento em que o motorista de fato saiu — retrovisor rebate em outras
     * situações, e a central desliga o carro sem que ninguém tenha ido embora.
     *
     * Chaves novas de propósito: as antigas controlavam gatilhos diferentes.
     */
    public static final String CLOSE_WINDOWS_ON_LOCK = "close_windows_on_lock";
    /**
     * "Desativar ao trancar": pisca a interface do AAW e o Bluetooth. Desde a v1.10.0 e o
     * UNICO modo — os alternativos "Bluetooth (invasivo)" e "Wi-Fi (invasivo)", que
     * desligavam o radio inteiro e so religavam na ignicao, sairam.
     *
     * A chave mantem o nome antigo de proposito: renomear reiniciaria a preferencia de
     * quem ja tem o app instalado.
     *
     * O force-stop foi medido em campo (09/09/2026) e nao serve: os quatro processos de
     * projecao morreram, nenhum voltou em 5 s, e a wlan2 seguiu com IP e o vizinho
     * REACHABLE. Nenhum processo da central sustenta a sessao. Ja
     * `ndc interface setcfg wlan2 down` derrubou o link de verdade — enquanto
     * `ip link set ... down` e `ifconfig ... down` devolveram exit 0 sem fazer nada.
     *
     * "Piscar" e nao "desligar" porque o objetivo e duplo: a sessao cai na hora em que
     * o carro e trancado, e os radios voltam em seguida para que a proxima partida nao
     * pague o custo de religar (~2 s medidos, mais o fallback do BluetoothAdapter). A
     * janela e de 1 minuto — 10 s derrubavam a sessao mas eram curtos demais para o
     * telefone desistir dela.
     */
    public static final String BOUNCE_RADIOS_ON_LOCK = "bounce_radios_on_lock";
    public static final String KEEP_DISTRACTION_DISABLED      = "keep_distraction_disabled";
    public static final String SET_STARTUP_VOLUME             = "set_startup_volume";
    public static final String STARTUP_VOLUME                 = "startup_volume";

    // ── Estado interno (não aparece na UI) ─────────────────────────────
    /**
     * Volume inicial já aplicado neste ciclo de ignição. Sem isso, um restart do
     * serviço no meio da viagem jogaria o volume de volta para o configurado.
     */
    public static final String VOLUME_APPLIED_THIS_CYCLE = "volume_applied_this_cycle";
    public static final String LAST_UPDATE_CHECK_MS      = "last_update_check_ms";
    /**
     * Um "pisca" comecou e ainda nao terminou de religar.
     *
     * Existe para a rede de seguranca do arranque: com a janela de 1 minuto, ser morto
     * no meio dela deixou de ser azar e passou a ser esperado de vez em quando (o log de
     * 08/09 traz 24 criacoes do servico contra 2 destruicoes). Sem esta flag a central
     * acordaria com a wlan2 caida e o Bluetooth desligado, sem ninguem para religar —
     * exatamente o estado em que o teste manual de 09/09 deixou o carro. Fica em storage
     * device-protected como todo o resto, entao sobrevive a um boot frio.
     */
    public static final String BOUNCE_PENDING     = "bounce_pending";
    /** Estado dos radios ANTES do pisca, para restaurar so o que estava ligado. */
    public static final String BOUNCE_BT_WAS_ON   = "bounce_bt_was_on";
    public static final String BOUNCE_WIFI_WAS_ON = "bounce_wifi_was_on";

    // ── Defaults ──────────────────────────────────────────────────────
    public static final boolean DEF_CLOSE_WINDOWS_ON_LOCK        = true;
    public static final boolean DEF_BOUNCE_RADIOS                = true;
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
