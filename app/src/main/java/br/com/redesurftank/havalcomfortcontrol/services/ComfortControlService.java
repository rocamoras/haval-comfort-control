package br.com.redesurftank.havalcomfortcontrol.services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.IConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService;
import com.beantechs.intelligentvehiclecontrol.sdk.IListener;
import com.beantechs.voice.adapter.IBinderPool;
import com.beantechs.voice.adapter.IVehicle;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

import br.com.redesurftank.havalcomfortcontrol.CarProps;
import br.com.redesurftank.havalcomfortcontrol.ComfortStateHolder;
import br.com.redesurftank.havalcomfortcontrol.Prefs;
import br.com.redesurftank.havalcomfortcontrol.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalcomfortcontrol.utils.IPTablesUtils;
import br.com.redesurftank.havalcomfortcontrol.utils.PersistentLog;
import br.com.redesurftank.havalcomfortcontrol.utils.ShizukuUtils;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * Servico unico do app: assina as propriedades do veiculo e reage a elas.
 *
 * Este app NAO sobe o shizuku_server. Quem sobe e o haval-climate-control (e, no
 * futuro, um app Core) — o server e singleton e quem sobe depois mata quem estava
 * de pe, entao subir aqui derrubaria o do outro app. Aqui a gente so espera o
 * binder existente aparecer, o que tambem e o que mantem este app leve.
 */
@SuppressLint("PrivateApi")
public class ComfortControlService extends Service implements Shizuku.OnBinderDeadListener {

    private static final String TAG = "ComfortControlService";

    private static final String CHANNEL_ID      = "ComfortControlChannel";
    private static final int    NOTIFICATION_ID = 1;

    /**
     * Medido em campo pelo climate-control: em boot frio o binder do Shizuku leva de
     * 4s a 6s. 30s da margem para um boot lento — e esperar e de graca, ja que
     * seguimos no instante em que o binder chega.
     */
    private static final long SHIZUKU_BINDER_TIMEOUT_MS = 30_000;
    /** Coalesce de bursts de onDataChanged para o push de estado — so afeta a UI. */
    private static final long UI_PUSH_DEBOUNCE_MS = 120;
    // WifiManager.WIFI_AP_STATE_* e as constantes da broadcast da ancora sao @hide:
    // nao existem no SDK compilado, so no runtime da ROM. Copiadas como literais.
    private static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String EXTRA_WIFI_AP_STATE          = "wifi_state";
    private static final int WIFI_AP_STATE_ENABLED  = 13;
    private static final int WIFI_AP_STATE_ENABLING = 12;
    /** ConnectivityManager.TETHERING_WIFI. */
    private static final int TETHERING_WIFI = 0;
    /** Codigo do IVehicle no IBinderPool do VoiceAdapterService nesta ROM. */
    private static final int BINDER_POOL_VEHICLE = 6;
    /** IVehicle.setWindowStatus: 1 = vidro fechado. */
    private static final int WINDOW_CLOSED = 1;
    /** car.basic.gear_status: 3 = P. */
    private static final String GEAR_PARK = "3";

    private static Method getServiceMethod;

    static {
        try {
            getServiceMethod = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, "android.os.ServiceManager.getService indisponivel", e);
        }
    }

    private static IBinder getServiceBinder(String name) {
        try {
            return (IBinder) Objects.requireNonNull(getServiceMethod.invoke(null, name));
        } catch (Exception e) {
            throw new RuntimeException("falha obtendo o system service " + name, e);
        }
    }

    /**
     * Thread das reacoes ao veiculo, em prioridade de display: os itens sensiveis a
     * atraso (volume inicial, religar Bluetooth/ancora) correm aqui, e numa partida
     * o sistema inteiro esta subindo ao mesmo tempo disputando CPU.
     */
    private HandlerThread reactThread;
    private Handler       react;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isServiceRunning     = false;
    private boolean isShizukuInitialized = false;
    private volatile long binderWaitStartedMs = 0;

    // Listeners em campo: `this::metodo` cria um lambda novo a cada chamada, entao
    // removeXListener(this::metodo) nunca removeria o que foi registrado.
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            this::onShizukuBinderReceived;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            this::onShizukuPermissionResult;

    private IIntelligentVehicleControlService controlService;
    private IVehicle             vehicle;
    private IConnectivityManager connectivityManager;
    private SharedPreferences    prefs;

    /**
     * Escrito na thread do binder (onDataChanged) e lido na thread react — por isso
     * concorrente, e nao um HashMap.
     */
    private final Map<String, String> dataCache = new ConcurrentHashMap<>();

    /** null = ainda nao sabemos; evita tratar o primeiro valor como uma transicao. */
    private Boolean lastReady = null;

    private BroadcastReceiver vehicleInitReceiver;
    private BroadcastReceiver radioGuardReceiver;

    private final Runnable uiPushRunnable = this::pushUiState;

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            // Sem debounce nas reacoes: o que atrasa a partida e justamente esperar.
            react.post(() -> handleVehicleData(key, value));
            react.removeCallbacks(uiPushRunnable);
            react.postDelayed(uiPushRunnable, UI_PUSH_DEBOUNCE_MS);
        }
    };

    // ─────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        reactThread = new HandlerThread("comfort-react", Process.THREAD_PRIORITY_DISPLAY);
        reactThread.start();
        react = new Handler(reactThread.getLooper());
        prefs = Prefs.get(this);

        // Registrado UMA vez por instancia, no contexto do servico: no climate-control
        // registrar isso dentro do init acumulava um receiver por ciclo, e no
        // INIT_COMPLETED seguinte todos pediam restart ao mesmo tempo.
        vehicleInitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isServiceRunning) return;
                restart("intelligentvehiclecontrol reinicializou (INIT_COMPLETED)");
            }
        };
        ContextCompat.registerReceiver(this, vehicleInitReceiver,
                new IntentFilter("com.beantechs.intelligentvehiclecontrol.INIT_COMPLETED"),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        registerRadioGuard();
        PersistentLog.w(TAG, "servico criado");
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) {
            Log.w(TAG, "servico ja rodando, ignorando start");
            return START_STICKY;
        }
        try {
            isServiceRunning = true;
            PersistentLog.w(TAG, "servico iniciado (flags=" + flags + " startId=" + startId
                    + " intent=" + (intent == null ? "null (recriado pelo sistema)" : "ok") + ")");

            startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Haval Comfort Control")
                    .setContentText("Monitorando o veiculo")
                    .setSmallIcon(android.R.drawable.ic_notification_overlay)
                    .build());

            binderWaitStartedMs = SystemClock.elapsedRealtime();
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            react.postDelayed(() -> {
                if (!isShizukuInitialized) {
                    restart("timeout de " + waitedForBinderMs() + "ms esperando o binder do"
                            + " Shizuku — o climate-control (ou o app-tool) subiu o server?");
                }
            }, SHIZUKU_BINDER_TIMEOUT_MS);
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro no onStartCommand, encerrando: " + e);
            isServiceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private synchronized void onShizukuBinderReceived() {
        if (!isServiceRunning) return;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        PersistentLog.w(TAG, "binder do Shizuku recebido apos " + waitedForBinderMs() + "ms");
        isShizukuInitialized = true;
        react.removeCallbacksAndMessages(null);
        checkAndInitialize();
    }

    private long waitedForBinderMs() {
        long started = binderWaitStartedMs;
        return started == 0 ? -1 : SystemClock.elapsedRealtime() - started;
    }

    private void checkAndInitialize() {
        if (!isShizukuInitialized) return;

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "pedindo permissao do Shizuku");
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            Shizuku.requestPermission(0);
            return;
        }

        if (!connectToVehicleService()) {
            restart("falha conectando ao servico do veiculo");
            return;
        }

        // Depois do que importa: libera o firewall por uid para o botao de update
        // conseguir falar com a api.github.com. Idempotente e sem timer de re-assert —
        // o climate-control ja mantem a regra de pe nesta central.
        react.post(() -> {
            try {
                IPTablesUtils.unlockInputOutputAll();
            } catch (Exception e) {
                Log.w(TAG, "falha liberando iptables: " + e.getMessage());
            }
        });
    }

    private synchronized void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != 0) return;
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            PersistentLog.w(TAG, "permissao do Shizuku concedida");
            checkAndInitialize();
        } else {
            PersistentLog.e(TAG, "permissao do Shizuku negada");
        }
    }

    private boolean connectToVehicleService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku indisponivel");
                return false;
            }

            IBinder controlBinder = new ShizukuBinderWrapper(
                    getServiceBinder("com.beantechs.intelligentvehiclecontrol"));
            if (!controlBinder.pingBinder()) {
                Log.e(TAG, "binder do IntelligentVehicleControlService morto");
                return false;
            }
            controlService = IIntelligentVehicleControlService.Stub.asInterface(controlBinder);
            controlService.addListenerKey(getPackageName(), CarProps.WATCHED);
            controlService.registerDataChangedListener(getPackageName(), vehicleDataListener);

            String[] values = controlService.fetchDatas(CarProps.WATCHED);
            if (values != null) {
                for (int i = 0; i < CarProps.WATCHED.length && i < values.length; i++) {
                    if (values[i] != null) dataCache.put(CarProps.WATCHED[i], values[i]);
                }
            }

            // IVehicle vem do pool do VoiceAdapterService — e quem tem o controle dos
            // vidros. Sem ele SO o fechamento dos vidros para de funcionar, entao a
            // falha aqui e registrada mas nao derruba o servico.
            try {
                IBinder poolBinder = new ShizukuBinderWrapper(
                        getServiceBinder("com.beantechs.voice.adapter.VoiceAdapterService"));
                IBinderPool pool = IBinderPool.Stub.asInterface(poolBinder);
                vehicle = IVehicle.Stub.asInterface(
                        new ShizukuBinderWrapper(pool.queryBinder(BINDER_POOL_VEHICLE)));
            } catch (Exception e) {
                PersistentLog.e(TAG, "falha obtendo o IVehicle — fechar vidros ficara"
                        + " indisponivel: " + e);
            }

            try {
                connectivityManager = IConnectivityManager.Stub.asInterface(
                        new ShizukuBinderWrapper(getServiceBinder(Context.CONNECTIVITY_SERVICE)));
            } catch (Exception e) {
                PersistentLog.e(TAG, "falha obtendo o IConnectivityManager — ancora ficara"
                        + " indisponivel: " + e);
            }

            Shizuku.addBinderDeadListener(this);
            PersistentLog.w(TAG, "conectado ao veiculo — ready="
                    + dataCache.get(CarProps.DRIVING_READY)
                    + " mirror=" + dataCache.get(CarProps.MIRROR_FOLD)
                    + " volume=" + dataCache.get(CarProps.MEDIA_VOLUME));

            mainHandler.post(() -> ComfortStateHolder.INSTANCE.updateConnected(true));
            react.post(this::applyStateOnStartup);
            react.post(uiPushRunnable);
            return true;
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro conectando ao servico do veiculo: " + e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Reacoes
    // ─────────────────────────────────────────────────────────────

    /**
     * Reconciliacao no start: o servico pode subir com o carro ja ligado (boot frio
     * na partida) ou no meio da viagem (restart). Nos dois casos aplicamos o que
     * estiver pendente — e e isso que faz o volume inicial valer num boot frio, onde
     * nao existe transicao de driving_ready para observar.
     */
    private void applyStateOnStartup() {
        boolean isReady = isReady(dataCache.get(CarProps.DRIVING_READY));
        lastReady = isReady;

        if (prefs.getBoolean(Prefs.KEEP_DISTRACTION_DISABLED, Prefs.DEF_KEEP_DISTRACTION_DISABLED)
                && "1".equals(dataCache.get(CarProps.DISTRACTION))) {
            setDistractionEnabled(false);
        }

        if (isReady) {
            applyStartupVolumeIfPending();
            restoreBluetoothIfPending();
            restoreHotspotIfPending();
        } else {
            // Central ligada com o carro desligado: garante os radios desligados.
            applyPowerOff();
        }
    }

    private void handleVehicleData(String key, String value) {
        try {
            switch (key) {
                case CarProps.DRIVING_READY:
                    onDrivingReadyChanged(value);
                    break;
                case CarProps.MIRROR_FOLD:
                    if ("0".equals(value)) onMirrorFolded();
                    break;
                case CarProps.DISTRACTION:
                    if ("1".equals(value) && prefs.getBoolean(Prefs.KEEP_DISTRACTION_DISABLED,
                            Prefs.DEF_KEEP_DISTRACTION_DISABLED)) {
                        setDistractionEnabled(false);
                        log("aviso de distracoes religou sozinho — desligado de novo");
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro tratando " + key + "=" + value + ": " + e);
        }
    }

    private void onDrivingReadyChanged(String value) {
        boolean isReady = isReady(value);
        if (lastReady != null && lastReady == isReady) return;   // repeticao, nao transicao
        lastReady = isReady;

        if (isReady) {
            PersistentLog.w(TAG, "veiculo ligado (driving_ready=" + value + ")");
            // ORDEM E LATENCIA: o volume e uma unica chamada de binder e resolve na
            // hora; Bluetooth e ancora precisam criar processos via Shizuku, o que
            // custa dezenas/centenas de ms. Volume primeiro, sempre.
            applyStartupVolumeIfPending();
            restoreBluetoothIfPending();
            restoreHotspotIfPending();
        } else {
            PersistentLog.w(TAG, "veiculo desligado (driving_ready=" + value + ")");
            applyPowerOff();
        }
    }

    /** Aviso de distracoes — funcionalidade 3. */
    private void setDistractionEnabled(boolean enabled) {
        updateData(CarProps.DISTRACTION, enabled ? "1" : "0");
        PersistentLog.w(TAG, "aviso de distracoes -> " + (enabled ? "ligado" : "desligado"));
    }

    /** Vidros ao rebater os retrovisores — funcionalidade 1. */
    private void onMirrorFolded() {
        if (!prefs.getBoolean(Prefs.CLOSE_WINDOWS_ON_FOLD_MIRROR,
                Prefs.DEF_CLOSE_WINDOWS_ON_FOLD_MIRROR)) return;

        // Os retrovisores tambem recolhem em movimento em algumas configuracoes; sem
        // esta guarda o app subiria os vidros com o carro andando.
        float speed = parseFloat(freshData(CarProps.VEHICLE_SPEED), 0f);
        String gear = freshData(CarProps.GEAR_STATUS);
        if (speed > 0 || !GEAR_PARK.equals(gear)) {
            Log.w(TAG, "retrovisores rebatidos ignorados (speed=" + speed + " gear=" + gear + ")");
            return;
        }
        if (closeAllWindows()) log("retrovisores rebatidos, vidros fechados");
    }

    /** Volume inicial — funcionalidade 4. */
    private void applyStartupVolumeIfPending() {
        if (!prefs.getBoolean(Prefs.SET_STARTUP_VOLUME, Prefs.DEF_SET_STARTUP_VOLUME)) return;
        if (prefs.getBoolean(Prefs.VOLUME_APPLIED_THIS_CYCLE, false)) return;

        int volume = prefs.getInt(Prefs.STARTUP_VOLUME, Prefs.DEF_STARTUP_VOLUME);
        updateData(CarProps.MEDIA_VOLUME, String.valueOf(volume));
        prefs.edit().putBoolean(Prefs.VOLUME_APPLIED_THIS_CYCLE, true).apply();
        log("volume inicial aplicado: " + volume);
    }

    /** Desligar Bluetooth e ancora com o carro — funcionalidade 2, metade do desligar. */
    private void applyPowerOff() {
        // Libera o volume inicial para a proxima partida ANTES dos radios: se algo
        // abaixo estourar, o volume da proxima partida nao e a vitima.
        prefs.edit().putBoolean(Prefs.VOLUME_APPLIED_THIS_CYCLE, false).apply();

        if (prefs.getBoolean(Prefs.DISABLE_BLUETOOTH_ON_POWER_OFF, Prefs.DEF_DISABLE_BLUETOOTH)
                && isBluetoothOn()) {
            prefs.edit().putBoolean(Prefs.BT_RESTORE_PENDING, true).apply();
            setBluetoothEnabled(false);
            log("carro desligado, Bluetooth desligado");
        }
        if (prefs.getBoolean(Prefs.DISABLE_HOTSPOT_ON_POWER_OFF, Prefs.DEF_DISABLE_HOTSPOT)) {
            // stopTethering incondicional (e idempotente): isHotspotOn() depende de uma
            // API @hide e do ultimo broadcast visto, e um "nao sei" nao pode virar
            // "deixa ligado". O estado lido decide apenas se religamos na partida.
            boolean wasOn = isHotspotOn();
            if (wasOn) prefs.edit().putBoolean(Prefs.HOTSPOT_RESTORE_PENDING, true).apply();
            stopHotspot();
            log(wasOn ? "carro desligado, ancora de Wi-Fi desligada"
                      : "carro desligado, ancora de Wi-Fi conferida como desligada");
        }
        pushUiState();
    }

    private void restoreBluetoothIfPending() {
        if (!prefs.getBoolean(Prefs.DISABLE_BLUETOOTH_ON_POWER_OFF,
                Prefs.DEF_DISABLE_BLUETOOTH)) return;
        if (!prefs.getBoolean(Prefs.BT_RESTORE_PENDING, false)) return;
        // O pendente e consumido mesmo se ja estiver ligado: sem isso um Bluetooth
        // religado pela central antes de nos deixaria a flag para sempre, e um
        // desligamento manual mais tarde acabaria "restaurado" numa partida futura.
        prefs.edit().putBoolean(Prefs.BT_RESTORE_PENDING, false).apply();
        if (isBluetoothOn()) return;
        setBluetoothEnabled(true);
        log("carro ligado, Bluetooth religado");
    }

    private void restoreHotspotIfPending() {
        if (!prefs.getBoolean(Prefs.DISABLE_HOTSPOT_ON_POWER_OFF,
                Prefs.DEF_DISABLE_HOTSPOT)) return;
        if (!prefs.getBoolean(Prefs.HOTSPOT_RESTORE_PENDING, false)) return;
        prefs.edit().putBoolean(Prefs.HOTSPOT_RESTORE_PENDING, false).apply();
        if (isHotspotOn()) return;
        startHotspot();
        log("carro ligado, ancora de Wi-Fi religada");
    }

    /**
     * Guarda dos radios: a central religa o Bluetooth e a ancora por conta propria
     * depois que desligamos. Sem este receiver a funcionalidade 2 nao pega —
     * desligavamos e a ROM ligava de volta segundos depois.
     */
    private void registerRadioGuard() {
        radioGuardReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state != BluetoothAdapter.STATE_ON) return;
                    react.post(() -> {
                        if (isCarOff() && prefs.getBoolean(Prefs.DISABLE_BLUETOOTH_ON_POWER_OFF,
                                Prefs.DEF_DISABLE_BLUETOOTH)) {
                            setBluetoothEnabled(false);
                            log("Bluetooth religou com o carro desligado, desligado de novo");
                        }
                        pushUiState();
                    });
                } else if (ACTION_WIFI_AP_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, 0);
                    boolean on = state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING;
                    prefs.edit().putBoolean(Prefs.HOTSPOT_LAST_KNOWN_ON, on).apply();
                    if (!on) return;
                    react.post(() -> {
                        if (isCarOff() && prefs.getBoolean(Prefs.DISABLE_HOTSPOT_ON_POWER_OFF,
                                Prefs.DEF_DISABLE_HOTSPOT)) {
                            stopHotspot();
                            log("ancora religou com o carro desligado, desligada de novo");
                        }
                        pushUiState();
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ACTION_WIFI_AP_STATE_CHANGED);
        ContextCompat.registerReceiver(this, radioGuardReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /** So considera "desligado" o que sabemos ser desligado — na duvida, nao age. */
    private boolean isCarOff() {
        return lastReady != null && !lastReady;
    }

    private static boolean isReady(String drivingReady) {
        return drivingReady != null && !drivingReady.equals("-1") && !drivingReady.equals("0");
    }

    // ─────────────────────────────────────────────────────────────
    // Atuadores
    // ─────────────────────────────────────────────────────────────

    private boolean closeAllWindows() {
        if (vehicle == null) {
            PersistentLog.e(TAG, "IVehicle indisponivel — nao foi possivel fechar os vidros");
            return false;
        }
        try {
            int[] status = vehicle.getWindowsStatus(0);
            for (int i = 0; i < status.length; i++) {
                if (status[i] != WINDOW_CLOSED) vehicle.setWindowStatus(i, WINDOW_CLOSED);
            }
            return true;
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro fechando os vidros: " + e);
            return false;
        }
    }

    private boolean isBluetoothOn() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * BluetoothAdapter.enable()/disable() e o caminho rapido: resolve dentro do
     * processo, sem criar um processo de shell. Foi por isso que ele vem primeiro —
     * o requisito da funcionalidade 2 e religar o Bluetooth com o minimo de atraso
     * na partida, e o `svc` via Shizuku custa dezenas/centenas de ms.
     *
     * O caminho lento fica como fallback porque enable()/disable() sao depreciados
     * e viram no-op para apps nao privilegiados em ROMs mais novas (Android 13+).
     */
    private void setBluetoothEnabled(boolean enabled) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && (enabled ? adapter.enable() : adapter.disable())) return;
        } catch (Throwable t) {
            Log.w(TAG, "BluetoothAdapter." + (enabled ? "enable" : "disable")
                    + "() indisponivel (" + t + "), caindo para o svc via Shizuku");
        }
        ShizukuUtils.run(new String[]{"svc", "bluetooth", enabled ? "enable" : "disable"});
    }

    /**
     * getWifiApState() e @hide (liberado pelo HiddenApiBypass em App.onCreate). Se a
     * reflexao falhar, cai no ultimo estado visto pelo broadcast — persistido, para
     * sobreviver a um restart do servico.
     */
    private boolean isHotspotOn() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int state = (int) WifiManager.class.getMethod("getWifiApState").invoke(wm);
                return state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getWifiApState indisponivel (" + t + "), usando o ultimo estado visto");
        }
        return prefs.getBoolean(Prefs.HOTSPOT_LAST_KNOWN_ON, false);
    }

    private void stopHotspot() {
        if (connectivityManager == null) return;
        try {
            connectivityManager.stopTethering(TETHERING_WIFI, getPackageName());
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro desligando a ancora: " + e);
        }
    }

    private void startHotspot() {
        if (connectivityManager == null) return;
        try {
            ResultReceiver receiver = new ResultReceiver(mainHandler) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode != 0) {
                        PersistentLog.e(TAG, "startTethering falhou com codigo " + resultCode);
                    }
                }
            };
            connectivityManager.startTethering(TETHERING_WIFI, receiver, false, getPackageName());
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro ligando a ancora: " + e);
        }
    }

    private void updateData(String key, String value) {
        if (controlService == null) {
            PersistentLog.e(TAG, "controlService nulo — " + key + " nao foi escrito");
            return;
        }
        try {
            controlService.request("cmd.common.request.set", key, value);
            dataCache.put(key, value);
        } catch (Exception e) {
            PersistentLog.e(TAG, "erro escrevendo " + key + "=" + value + ": " + e);
        }
    }

    /** Le do servico do veiculo, nao do cache — usado nas guardas de seguranca. */
    private String freshData(String key) {
        if (controlService == null) return dataCache.get(key);
        try {
            String value = controlService.fetchData(key);
            if (value != null) {
                dataCache.put(key, value);
                return value;
            }
        } catch (Exception e) {
            Log.w(TAG, "falha lendo " + key + ": " + e.getMessage());
        }
        return dataCache.get(key);
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UI e infra
    // ─────────────────────────────────────────────────────────────

    private void pushUiState() {
        final String ready   = dataCache.get(CarProps.DRIVING_READY);
        final String mirror  = dataCache.get(CarProps.MIRROR_FOLD);
        final String volume  = dataCache.get(CarProps.MEDIA_VOLUME);
        final boolean bt      = isBluetoothOn();
        final boolean hotspot = isHotspotOn();
        mainHandler.post(() -> {
            ComfortStateHolder holder = ComfortStateHolder.INSTANCE;
            holder.setVehicleValue(CarProps.DRIVING_READY, ready);
            holder.setVehicleValue(CarProps.MIRROR_FOLD, mirror);
            holder.setVehicleValue(CarProps.MEDIA_VOLUME, volume);
            holder.setRadios(bt, hotspot);
        });
    }

    private void log(String message) {
        PersistentLog.w(TAG, message);
        mainHandler.post(() -> ComfortStateHolder.INSTANCE.log(message));
    }

    private void createNotificationChannel() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Comfort Control",
                        NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        Shizuku.removeBinderDeadListener(this);
        if (vehicleInitReceiver != null) {
            try { unregisterReceiver(vehicleInitReceiver); } catch (Exception ignored) {}
            vehicleInitReceiver = null;
        }
        if (radioGuardReceiver != null) {
            try { unregisterReceiver(radioGuardReceiver); } catch (Exception ignored) {}
            radioGuardReceiver = null;
        }
        try {
            if (controlService != null) {
                controlService.unRegisterDataChangedListener(getPackageName(), vehicleDataListener);
            }
        } catch (Exception ignored) {}
        if (reactThread != null) reactThread.quitSafely();
        mainHandler.post(() -> ComfortStateHolder.INSTANCE.updateConnected(false));
        PersistentLog.w(TAG, "servico destruido");
        super.onDestroy();
    }

    @Override
    public void onBinderDead() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(this);
        restart("binder do Shizuku morreu");
    }

    /** @param reason vai para o log persistente — e o que responde "por que reiniciou?". */
    private synchronized void restart(String reason) {
        isShizukuInitialized = false;
        isServiceRunning     = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        Shizuku.removeBinderDeadListener(this);
        mainHandler.post(() -> ComfortStateHolder.INSTANCE.updateConnected(false));
        PersistentLog.w(TAG, "REINICIO agendado (+1s) — motivo: " + reason);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(this, RestartReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pendingIntent);
        stopSelf();
    }
}
