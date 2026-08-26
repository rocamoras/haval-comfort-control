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
    /** 0 = retrovisores recolhidos (rebatidos). */
    public static final String MIRROR_FOLD    = "car.drive.setting.outside_view_mirror_fold_state";
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
            MIRROR_FOLD,
            VEHICLE_SPEED,
            GEAR_STATUS,
            WINDOW_STATUS,
            DISTRACTION,
            MEDIA_VOLUME,
    };

    private CarProps() {}
}
