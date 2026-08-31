package br.com.redesurftank.havalcomfortcontrol;

/**
 * Propriedades do IIntelligentVehicleControlService usadas por este app.
 *
 * Os nomes vieram do app-tool-multimidia (CarConstants) — são as chaves reais do
 * serviço do veículo, não um enum nosso; não renomear.
 */
public final class CarProps {

    /** -1/0 = veículo desligado; 1/2 = pronto (central ligada com o carro). */
    public static final String DRIVING_READY  = "car.basic.driving_ready_state";
    /** 1 = trancado, 3 = destrancado. Gatilho das funcionalidades 1 e 2. */
    public static final String DOOR_LOCK      = "car.basic.door_lock_status";
    /**
     * -1 e 15 = motor desligado. Os dois valores vieram do app-tool, que os usa em
     * {@code isMainScreenOn()} e no ProjectorManager como "carro desligado".
     */
    public static final String ENGINE_STATE   = "car.basic.engine_state";
    public static final String VEHICLE_SPEED  = "car.basic.vehicle_speed";
    /** 3 = P (estacionado). */
    public static final String GEAR_STATUS    = "car.basic.gear_status";
    public static final String WINDOW_STATUS  = "car.basic.window_status";
    /** 1 = aviso de distrações ativo. */
    public static final String DISTRACTION    = "car.frs_setting.distraction_detection_enable";
    public static final String MEDIA_VOLUME   = "sys.settings.audio.media_volume";

    /** Tudo que assinamos no serviço do veículo. */
    public static final String[] WATCHED = {
            DRIVING_READY,
            DOOR_LOCK,
            ENGINE_STATE,
            VEHICLE_SPEED,
            GEAR_STATUS,
            WINDOW_STATUS,
            DISTRACTION,
            MEDIA_VOLUME,
    };

    private CarProps() {}
}
