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
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
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
    /** App de projecao do Android Auto sem fio, encerrado ao desligar o carro. */
    private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";
    /** Codigo do IVehicle no IBinderPool do VoiceAdapterService nesta ROM. */
    private static final int BINDER_POOL_VEHICLE = 6;
    /** IVehicle.setWindowStatus: 1 = vidro fechado. */
    private static final int WINDOW_CLOSED = 1;
    /** car.basic.gear_status: 3 = P. */
    private static final String GEAR_PARK = "3";
    /** Janela para conferir se o pedido de Bluetooth realmente mudou o radio. */
    private static final long BT_VERIFY_DELAY_MS = 1_500;
    /** Idem para o Wi-Fi — svc wifi e assincrono. */
    private static final long WIFI_VERIFY_DELAY_MS = 3_000;

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

    /**
     * Chaves que disparam acao. car.basic.vehicle_speed e car.basic.gear_status ficam
     * DE FORA: eles existem apenas como guarda, lidos sob demanda em freshData().
     *
     * Isso e correcao de travamento, nao arrumacao. A velocidade muda varias vezes por
     * segundo andando, e antes cada mudanca virava um post na thread react MAIS um
     * pushUiState — que faz duas chamadas de binder (estado do Bluetooth e da ancora)
     * e um post para a main thread com recomposicao do Compose. Dava um punhado de
     * ciclos desses por segundo, sem parar, inclusive com o app em background.
     */
    private static boolean isActionableKey(String key) {
        return CarProps.DRIVING_READY.equals(key)
                || CarProps.MIRROR_FOLD.equals(key)
                || CarProps.DISTRACTION.equals(key);
    }

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            // Sem debounce nas reacoes: o que atrasa a partida e justamente esperar.
            if (isActionableKey(key)) react.post(() -> handleVehicleData(key, value));
            // Espelhar na tela so vale se alguem estiver olhando.
            if (ComfortStateHolder.INSTANCE.getUiVisible()) {
                react.removeCallbacks(uiPushRunnable);
                react.postDelayed(uiPushRunnable, UI_PUSH_DEBOUNCE_MS);
            }
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

            Shizuku.addBinderDeadListener(this);
            PersistentLog.w(TAG, "SDK_INT=" + android.os.Build.VERSION.SDK_INT
                    + " wifi=" + (isWifiOn() ? "on" : "off")
                    + " androidAuto=" + (isPackageInstalled(ANDROID_AUTO_PACKAGE)
                                         ? "instalado" : "NAO INSTALADO"));
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
            restoreWifiIfPending();
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
            restoreWifiIfPending();
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
        if (speed > 0) {
            log("retrovisores rebatidos IGNORADOS: carro em movimento (" + speed + ")");
            return;
        }
        // A marcha so e criterio com o carro LIGADO. No caso normal — rebater ao
        // travar e sair — o carro ja esta desligado e o gear_status devolve valor
        // parado/indefinido; exigir P ali era o que fazia a funcionalidade falhar "em
        // alguns casos". Carro desligado nao anda, e a guarda de velocidade acima ja
        // cobre o risco.
        if (!isCarOff()) {
            String gear = freshData(CarProps.GEAR_STATUS);
            if (!GEAR_PARK.equals(gear)) {
                log("retrovisores rebatidos IGNORADOS: carro ligado fora de P (gear="
                        + gear + ")");
                return;
            }
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
        if (prefs.getBoolean(Prefs.DISABLE_WIFI_ON_POWER_OFF, Prefs.DEF_DISABLE_WIFI)) {
            // Encerra a projecao ANTES de cortar o transporte, para a sessao terminar
            // limpa em vez de o telefone ficar tentando reconectar num AP que caiu.
            stopAndroidAuto();
            if (isWifiOn()) {
                prefs.edit().putBoolean(Prefs.WIFI_RESTORE_PENDING, true).apply();
                setWifiEnabled(false);
                log("carro desligado, Wi-Fi da central desligado");
            } else {
                log("carro desligado, Wi-Fi ja estava desligado");
            }
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

    private void restoreWifiIfPending() {
        if (!prefs.getBoolean(Prefs.DISABLE_WIFI_ON_POWER_OFF, Prefs.DEF_DISABLE_WIFI)) return;
        if (!prefs.getBoolean(Prefs.WIFI_RESTORE_PENDING, false)) return;
        prefs.edit().putBoolean(Prefs.WIFI_RESTORE_PENDING, false).apply();
        if (isWifiOn()) return;
        setWifiEnabled(true);
        log("carro ligado, Wi-Fi da central religado");
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
                } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    if (state != WifiManager.WIFI_STATE_ENABLED) return;
                    react.post(() -> {
                        if (isCarOff() && prefs.getBoolean(Prefs.DISABLE_WIFI_ON_POWER_OFF,
                                Prefs.DEF_DISABLE_WIFI)) {
                            stopAndroidAuto();
                            setWifiEnabled(false);
                            log("Wi-Fi religou com o carro desligado, desligado de novo");
                        }
                        pushUiState();
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
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

    /**
     * IVehicle sob demanda, re-adquirindo se o binder morreu.
     *
     * O VoiceAdapterService reinicia por conta propria e leva o binder com ele. Nada
     * nos avisa: o INIT_COMPLETED que escutamos e do intelligentvehiclecontrol, outro
     * servico. A versao anterior guardava o binder do init e seguia usando o cadaver,
     * entao o fechamento dos vidros passava a falhar em silencio ate o proximo boot —
     * que e o "nao funciona em todos os casos" visto no carro.
     */
    private IVehicle vehicle() {
        try {
            if (vehicle != null && vehicle.asBinder().isBinderAlive()) return vehicle;
            IBinderPool pool = IBinderPool.Stub.asInterface(new ShizukuBinderWrapper(
                    getServiceBinder("com.beantechs.voice.adapter.VoiceAdapterService")));
            vehicle = IVehicle.Stub.asInterface(
                    new ShizukuBinderWrapper(pool.queryBinder(BINDER_POOL_VEHICLE)));
            PersistentLog.w(TAG, "IVehicle (re)adquirido");
            return vehicle;
        } catch (Exception e) {
            PersistentLog.e(TAG, "falha obtendo o IVehicle: " + e);
            return null;
        }
    }

    private boolean closeAllWindows() {
        IVehicle v = vehicle();
        if (v == null) {
            PersistentLog.e(TAG, "IVehicle indisponivel — nao foi possivel fechar os vidros");
            return false;
        }
        try {
            int[] status = v.getWindowsStatus(0);
            StringBuilder antes = new StringBuilder();
            for (int st : status) antes.append(st).append(' ');
            for (int i = 0; i < status.length; i++) {
                if (status[i] != WINDOW_CLOSED) v.setWindowStatus(i, WINDOW_CLOSED);
            }
            PersistentLog.w(TAG, "vidros: estado antes = [" + antes.toString().trim() + "]");
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
     * Liga/desliga o Bluetooth. O `svc` via Shizuku e o caminho PRINCIPAL.
     *
     * Historia disso, porque a ordem aqui e o bug que foi para o campo: o app-tool
     * (referencia comprovada nesta central) usa exclusivamente
     * `svc bluetooth enable|disable`, e nem declara BLUETOOTH_ADMIN — ou seja,
     * BluetoothAdapter.enable()/disable() nunca foi opcao lá. Eu inverti a ordem para
     * ganhar latencia, e o adapter devolve true significando "pedido aceito", nao
     * "radio mudou": nesta ROM o pedido e engolido, o true satisfazia o if, e o
     * fallback nunca acontecia. O Bluetooth simplesmente nao desligava.
     *
     * Agora o comprovado vem primeiro e sem espera. O adapter ficou como segunda
     * tentativa, exercitada apenas se a conferencia mostrar que o svc nao pegou.
     */
    private void setBluetoothEnabled(boolean enabled) {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(
                new String[]{"svc", "bluetooth", enabled ? "enable" : "disable"});
        if (!r.ok()) {
            PersistentLog.e(TAG, "svc bluetooth " + (enabled ? "enable" : "disable")
                    + " falhou: " + r.describeFailure());
        }
        react.postDelayed(() -> {
            if (isBluetoothOn() == enabled) return;
            PersistentLog.w(TAG, "Bluetooth nao " + (enabled ? "ligou" : "desligou")
                    + " pelo svc — tentando pelo BluetoothAdapter");
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    if (enabled) adapter.enable(); else adapter.disable();
                }
            } catch (Throwable t) {
                PersistentLog.e(TAG, "BluetoothAdapter tambem indisponivel: " + t);
            }
        }, BT_VERIFY_DELAY_MS);
    }

    /** Diz se o pacote existe — usado para logar se o Android Auto esta instalado. */
    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Liga/desliga o Wi-Fi DA CENTRAL, com conferencia e fallback.
     *
     * Por que Wi-Fi e nao tethering: o objetivo e derrubar a sessao do Android Auto
     * sem fio quando o carro desliga (a central fica ligada alguns minutos e o
     * telefone continuava conectado). O link do AAW e um AP proprio da central —
     * LocalOnlyHotspot / softAP do servico de projecao — que NAO passa pelo
     * stopTethering do IConnectivityManager. Era por isso que a versao anterior nao
     * surtia efeito: desligava um AP que nao era o que sustentava a conexao.
     *
     * Aqui o estado tem API publica de verdade (WifiManager.isWifiEnabled()), sem
     * reflexao em metodo @hide e sem codigo de transacao AIDL fixo.
     */
    private void setWifiEnabled(boolean enabled) {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(
                new String[]{"svc", "wifi", enabled ? "enable" : "disable"});
        if (!r.ok()) {
            PersistentLog.e(TAG, "svc wifi " + (enabled ? "enable" : "disable")
                    + " falhou: " + r.describeFailure());
        }
        react.postDelayed(() -> {
            if (isWifiOn() == enabled) return;
            PersistentLog.w(TAG, "Wi-Fi nao " + (enabled ? "ligou" : "desligou")
                    + " pelo svc — tentando cmd wifi");
            ShizukuUtils.ShellResult r2 = ShizukuUtils.run(new String[]{
                    "cmd", "wifi", "set-wifi-enabled", enabled ? "enabled" : "disabled"});
            PersistentLog.w(TAG, "cmd wifi set-wifi-enabled -> " + r2.describeFailure());
        }, WIFI_VERIFY_DELAY_MS);
    }

    /** WifiManager.isWifiEnabled() — API publica, ao contrario do getWifiApState(). */
    private boolean isWifiOn() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Encerra o app de projecao antes de tirar o Wi-Fi debaixo dele.
     *
     * Sem isso o telefone perde o transporte com a sessao ativa e fica tentando
     * reconectar; com o force-stop a sessao termina de forma limpa. O pacote e o
     * Android Auto do Google, confirmado pelo usuario — se nao estiver instalado, o
     * am reclama e o log registra, sem afetar o resto.
     */
    private void stopAndroidAuto() {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(
                new String[]{"am", "force-stop", ANDROID_AUTO_PACKAGE});
        PersistentLog.w(TAG, "am force-stop " + ANDROID_AUTO_PACKAGE + " -> "
                + r.describeFailure());
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
        if (!ComfortStateHolder.INSTANCE.getUiVisible()) return;
        final String ready   = dataCache.get(CarProps.DRIVING_READY);
        final String mirror  = dataCache.get(CarProps.MIRROR_FOLD);
        final String volume  = dataCache.get(CarProps.MEDIA_VOLUME);
        final boolean bt      = isBluetoothOn();
        final boolean wifi    = isWifiOn();
        mainHandler.post(() -> {
            ComfortStateHolder holder = ComfortStateHolder.INSTANCE;
            holder.setVehicleValue(CarProps.DRIVING_READY, ready);
            holder.setVehicleValue(CarProps.MIRROR_FOLD, mirror);
            holder.setVehicleValue(CarProps.MEDIA_VOLUME, volume);
            holder.setRadios(bt, wifi);
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
