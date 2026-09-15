package br.com.redesurftank.havalcomfortcontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferências do app, todas num único arquivo em storage device-protected.
 *
 * Motivo de não haver um arquivo "de UI" separado: o serviço lê estas chaves no
 * LOCKED_BOOT_COMPLETED, antes do unlock. Um arquivo em credential storage leria
 * o default em todo boot frio — e é justamente no boot que o volume inicial e a
 * recuperação de um hold interrompido ([HOLD_PENDING]) precisam estar corretos.
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
     * "Desativar ao trancar": derruba a interface do AAW e o Bluetooth e SEGURA
     * derrubados ate a ROM desligar a central (3 a 4 min, medidos).
     *
     * O force-stop foi medido em campo (09/09/2026) e nao serve: os quatro processos de
     * projecao morreram, nenhum voltou em 5 s, e a wlan2 seguiu com IP e o vizinho
     * REACHABLE. Nenhum processo da central sustenta a sessao. Ja
     * `ndc interface setcfg wlan2 down` derrubou o link de verdade.
     *
     * E o "pisca" que veio depois (derruba e religa em 1 min) tambem nao serve: a sessao
     * voltava em menos de 15 s em 2 de 3 trancas, e o motivo de religar — deixar os radios
     * quentes para a partida seguinte — nao existe, porque a central sempre cold-boota.
     *
     * A chave mantem o nome antigo de proposito: renomear reiniciaria a preferencia de
     * quem ja tem o app instalado.
     */
    public static final String DISCONNECT_ON_LOCK = "bounce_radios_on_lock";
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
     * Um hold comecou e ainda nao foi encerrado.
     *
     * Existe para a rede de seguranca do arranque. No caminho NORMAL esta flag fica de pe
     * ate a ROM desligar a central, e quem a consome e o arranque seguinte — que vai
     * encontrar os radios ja normais, porque o boot os traz ligados, e so limpa a flag. O
     * caso que ela realmente salva e a ROM matar o processo com os radios derrubados e a
     * central seguir viva: sem a flag ninguem religaria. Fica em storage device-protected
     * como todo o resto, entao sobrevive a um boot frio.
     */
    public static final String HOLD_PENDING     = "bounce_pending";
    /** Estado dos radios ANTES do hold, para restaurar so o que estava ligado. */
    public static final String HOLD_BT_WAS_ON   = "bounce_bt_was_on";
    public static final String HOLD_WIFI_WAS_ON = "bounce_wifi_was_on";

    // ── Defaults ──────────────────────────────────────────────────────
    public static final boolean DEF_CLOSE_WINDOWS_ON_LOCK        = true;
    public static final boolean DEF_DISCONNECT_ON_LOCK           = true;
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
