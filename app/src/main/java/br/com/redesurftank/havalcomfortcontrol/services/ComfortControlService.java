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
import br.com.redesurftank.havalcomfortcontrol.utils.AawLink;
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
    /**
     * Receiver de Android Auto DA CENTRAL — o app que roda no head unit e termina a
     * sessao quando morre.
     *
     * Medido no carro (memoria do projeto, 2026-09-01):
     * com.ts.androidauto.app/.display.AapActivity, app de sistema VENDOR, Android 9.
     *
     * NAO confundir com com.google.android.projection.gearhead, que e o app do
     * CELULAR: era esse que estava aqui antes, nao existe na central, e por isso o
     * force-stop falhava em silencio — quem derrubava a sessao acabava sendo o
     * `svc wifi disable`.
     */
    private static final String ANDROID_AUTO_PACKAGE = "com.ts.androidauto.app";
    /**
     * Janela em que a interface do AAW e o Bluetooth ficam desligados na tranca.
     *
     * POR QUE PISCAR E NAO DESLIGAR — quatro palpites de force-stop morreram antes
     * disto:
     *
     * 1. com.google.android.projection.gearhead (v1.2.0): e o app do CELULAR, nem
     *    existe na central. O force-stop falhava em silencio.
     * 2. com.ts.androidauto.app (v1.5.0): existe e morre, e o telefone SEGUE conectado.
     *    E so a parte de tela (.display.AapActivity).
     * 3. com.ts.androidauto.projectionservice (v1.6.0): o log dizia "nao estava
     *    rodando", mas era artefato do `pidof`, que casa por NOME DE PROCESSO — o
     *    processo dele se chama com.ts.androidauto.
     * 4. Os OITO pacotes de projecao, sem gate de pidof (v1.8.0, medido em 09/09/2026):
     *    os quatro processos vivos morreram, nenhum voltou em 5 s, e a wlan2 seguiu com
     *    IP e o vizinho REACHABLE. Nenhum processo da central sustenta a sessao.
     *
     * O que funciona e derrubar o link: `ndc interface setcfg wlan2 down` — ver
     * {@link AawLink}. So que derrubar e DEIXAR derrubado custa a partida seguinte: o
     * log de 09/09 mostra ~2 s religando os radios na ignicao (07:18:26.886 Bluetooth,
     * 07:18:28.037 Wi-Fi) mais o fallback do BluetoothAdapter. E, medido pior: com a
     * wlan2 caida e o Bluetooth ligado, o AA nao funciona e o audio do telefone fica
     * preso no carro.
     *
     * ATE A v1.11.0 ISTO ERA UM "PISCA" — derrubava e religava depois de 1 minuto, para
     * que os radios voltassem quentes e a proxima partida fosse rapida. Duas medicoes de
     * 12 a 15/09/2026 derrubaram essa premissa e essa solucao:
     *
     * 1. NAO EXISTE PARTIDA QUENTE. O `uptime do device` zera a cada arranque (sempre
     *    11-14 s quando o servico sobe), e o heartbeat pos-tranca mostrou a ROM desligando
     *    a central entre 3 e 4 minutos depois da tranca — marcacoes de 1, 2 e 3 min e
     *    entao silencio ate o proximo boot, horas depois. Toda ignicao e boot frio, entao
     *    guardar radio ligado nao acelera nada.
     * 2. RELIGAR DEVOLVIA A SESSAO. Nas tres trancas com verificacao, o telefone
     *    reconectou em menos de 15 s em DUAS (IP novo na wlan2 aos 15 s, 30 s e 60 s).
     *
     * Entao agora derruba e SEGURA. Quem encerra e a propria ROM, desligando a central.
     * Isto aqui e so o teto de seguranca: se por algum motivo ela nao desligar, os radios
     * nao ficam fora para sempre.
     */
    private static final long HOLD_MAX_MS = 10 * 60_000;
    /**
     * Intervalo da guarda, re-agendada ate o fim do hold.
     *
     * A guarda NAO e precaucao: `ndc interface setcfg wlan2 down` nao e duravel. Numa
     * janela de 1 minuto do log de 12/09 o supplicant reassociou ONZE vezes seguidas, uma
     * a cada 5 s, e a guarda teve de derrubar todas. Sem ela o log diria "derrubado" com
     * a sessao de pe o tempo todo. O Bluetooth tem o mesmo problema pelo lado da ROM.
     */
    private static final long HOLD_GUARD_INTERVAL_MS = 5_000;
    /**
     * De quantas em quantas passagens da guarda sai uma linha de resumo.
     *
     * Uma linha por reassociacao encheria o log: 11 por minuto medidos, e o hold pode
     * durar minutos. A cada 12 passagens da um resumo por minuto — e a ULTIMA linha antes
     * do silencio passa a ser o registro de quando a ROM desligou a central, que e o que
     * o heartbeat da v1.11.0 media (e por isso ele saiu).
     */
    private static final int HOLD_LOG_EVERY = 12;
    /**
     * Conferencias depois de religar. Hoje o caminho normal e a ROM desligar a central e
     * ninguem religar nada — estas linhas so aparecem quando o hold termina por
     * destranque, ignicao ou pelo teto, que sao justamente os casos em que vale saber se
     * a sessao voltou.
     */
    private static final long[] RESTORE_VERIFY_DELAYS_MS = {15_000, 30_000, 60_000};
    /** Espera entre o disable e o enable no fallback de ciclo completo de Wi-Fi. */
    private static final long WIFI_CYCLE_GAP_MS = 2_000;
    /** Pistas para descobrir no log um receiver diferente destes, se houver. */
    private static final String[] PROJECTION_HINTS = {
            "androidauto", "gearhead", "carlife", "carplay", "hicar", "zlink", "easyconn"
    };
    /** Codigo do IVehicle no IBinderPool do VoiceAdapterService nesta ROM. */
    private static final int BINDER_POOL_VEHICLE = 6;
    /** IVehicle.setWindowStatus: 1 = vidro fechado. */
    private static final int WINDOW_CLOSED = 1;
    /** car.basic.gear_status: 3 = P. */
    private static final String GEAR_PARK = "3";
    /** car.basic.door_lock_status: 1 = trancado, 3 = destrancado. */
    private static final String DOOR_LOCKED   = "1";
    private static final String DOOR_UNLOCKED = "3";
    /**
     * car.basic.engine_state com motor desligado. Os dois valores vieram do app-tool,
     * que trata -1 e 15 como carro desligado em isMainScreenOn() e no ProjectorManager.
     */
    private static final String[] ENGINE_OFF_VALUES = {"-1", "15"};
    /** Janela para conferir se o pedido de Bluetooth realmente mudou o radio. */
    private static final long BT_VERIFY_DELAY_MS = 1_500;
    /** Idem para o Wi-Fi — svc wifi e assincrono. */
    private static final long WIFI_VERIFY_DELAY_MS = 3_000;
    /**
     * Reavaliacao da tranca quando ela chega antes das outras condicoes.
     *
     * A tranca e um evento unico: se door_lock_status=1 chegar antes de engine_state
     * virar desligado, a condicao falha e a funcionalidade nao acontece de novo naquele
     * uso do carro. Em vez de assinar engine_state (que num hibrido muda toda hora, por
     * start-stop), reavaliamos algumas vezes em intervalo curto.
     */
    private static final long LOCK_RECHECK_DELAY_MS = 3_000;
    private static final int  LOCK_RECHECK_MAX      = 4;

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
    /**
     * Ja agimos nesta trancada. A ROM repete door_lock_status=1, e sem isto cada
     * repeticao mandaria fechar vidros e desligar radios de novo. Zera ao destrancar.
     */
    private boolean lockActionDone = false;
    /**
     * Estamos segurando os radios derrubados desde a tranca.
     *
     * Serve para a MESMA guarda de radioGuardReceiver: o log de 08/09 mostra a ROM
     * religando o Bluetooth tres vezes em ~3 s depois de desligarmos, e sem guarda a
     * hold seria engolido. E limpo ANTES de religarmos, senao a guarda reverteria a nossa
     * propria restauracao.
     */
    private boolean holdInProgress = false;
    /** Passagens da guarda no hold atual, para espacar as linhas de resumo. */
    private int holdTicks = 0;
    /** Quantas vezes tivemos de derrubar de novo neste hold — o supplicant insiste. */
    private int holdReDrops = 0;
    /** Reavaliacoes restantes da tranca; ver LOCK_RECHECK_DELAY_MS. */
    private int lockRechecksLeft = 0;

    private BroadcastReceiver vehicleInitReceiver;
    private BroadcastReceiver radioGuardReceiver;

    private final Runnable uiPushRunnable = this::pushUiState;
    /**
     * Em campo, e nao `this::metodo` inline: cada referencia de metodo cria um objeto
     * novo, e o removeCallbacks do cancelamento antecipado nao casaria com o que foi
     * enfileirado.
     */
    private final Runnable holdCapRunnable   = this::endHoldByCap;
    private final Runnable holdGuardRunnable = this::holdGuardTick;

    /** elapsedRealtime da tranca, base do "N min apos a tranca" nos resumos. */
    private long lockedAtMs = 0;

    /**
     * Chaves que disparam acao. vehicle_speed, gear_status e engine_state ficam DE
     * FORA: existem apenas como guarda, lidos sob demanda em freshData().
     *
     * Isso e correcao de travamento, nao arrumacao. A velocidade muda varias vezes por
     * segundo andando, e antes cada mudanca virava um post na thread react MAIS um
     * pushUiState — que faz chamadas de binder para o estado dos radios e um post para
     * a main thread com recomposicao do Compose. Dava um punhado de ciclos desses por
     * segundo, sem parar, inclusive com o app em background.
     */
    private static boolean isActionableKey(String key) {
        return CarProps.DOOR_LOCK.equals(key)
                || CarProps.DRIVING_READY.equals(key)
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
                    + " receiverAA=" + (isPackageInstalled(ANDROID_AUTO_PACKAGE)
                                        ? "instalado" : "NAO INSTALADO"));
            // Estado da projecao no arranque. Via `ps` (AawLink), nunca `pidof`: ele casa
            // por nome de processo, e foi o que fez a v1.6.0 concluir que o
            // projectionservice "nunca tinha processo" quando ele estava ativo.
            react.post(() -> PersistentLog.w(TAG, "projecao no arranque: "
                    + AawLink.projectionProcessesLine()
                    + " | " + AawLink.IFACE + " " + AawLink.describe()));
            react.post(this::logProjectionPackages);
            PersistentLog.w(TAG, "conectado ao veiculo — ready="
                    + dataCache.get(CarProps.DRIVING_READY)
                    + " lock=" + dataCache.get(CarProps.DOOR_LOCK)
                    + " engine=" + dataCache.get(CarProps.ENGINE_STATE)
                    + " gear=" + dataCache.get(CarProps.GEAR_STATUS)
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

        // Antes de qualquer outra coisa, e independente de o carro estar ligado: se a ROM
        // nos matou no meio de um hold, os radios estao desligados e ninguem sabe disso.
        recoverFromInterruptedHold();

        if (prefs.getBoolean(Prefs.KEEP_DISTRACTION_DISABLED, Prefs.DEF_KEEP_DISTRACTION_DISABLED)
                && "1".equals(dataCache.get(CarProps.DISTRACTION))) {
            setDistractionEnabled(false);
        }

        if (isReady) {
            applyStartupVolumeIfPending();
            ensureAawInterfaceUp();
        } else {
            // Carro desligado no start NAO e mais motivo para desligar radio nenhum: o
            // gatilho agora e a tranca, e a central passa minutos ligada com o carro
            // desligado e o motorista ainda dentro. Aqui so liberamos o volume inicial
            // para a proxima partida.
            prefs.edit().putBoolean(Prefs.VOLUME_APPLIED_THIS_CYCLE, false).apply();
        }
    }

    private void handleVehicleData(String key, String value) {
        try {
            switch (key) {
                case CarProps.DRIVING_READY:
                    onDrivingReadyChanged(value);
                    break;
                case CarProps.DOOR_LOCK:
                    onDoorLockChanged(value);
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
            // Carro ligou: a proxima trancada volta a poder agir, mesmo que o destrancar
            // nao tenha sido observado.
            lockActionDone = false;
            endHold("ignicao");
            // ORDEM E LATENCIA: o volume e uma unica chamada de binder e resolve na
            // hora; Bluetooth e ancora precisam criar processos via Shizuku, o que
            // custa dezenas/centenas de ms. Volume primeiro, sempre.
            applyStartupVolumeIfPending();
            ensureAawInterfaceUp();
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

    /**
     * Gatilho unico das funcionalidades 1 e 2: o carro foi TRANCADO estando em P com o
     * motor desligado — ou seja, o motorista saiu e foi embora.
     *
     * Por que trocamos os gatilhos anteriores: o rebatimento dos retrovisores acontece
     * em outras situacoes (e nem sempre acontece), e a transicao de driving_ready
     * dispara com o carro apenas desligado, sem ninguem ter ido embora — a central fica
     * ligada minutos depois disso. "Trancou" e o unico evento que significa de fato
     * "acabou o uso do carro".
     */
    private void onDoorLockChanged(String value) {
        if (DOOR_UNLOCKED.equals(value)) {
            // Destrancou: libera para agir na proxima trancada e para de reverter os
            // radios, senao a guarda brigaria com o usuario que acabou de voltar.
            if (lockActionDone) log("carro destrancado");
            // Destrancou: o motorista voltou, e deixa-lo sem Bluetooth e sem Wi-Fi ate a
            // central desligar seria o contrario do objetivo.
            endHold("destranque");
            lockActionDone   = false;
            lockRechecksLeft = 0;
            return;
        }
        if (!DOOR_LOCKED.equals(value)) return;
        lockRechecksLeft = LOCK_RECHECK_MAX;
        evaluateLockTrigger();
    }

    /** Confere as tres condicoes e age; reagenda se a tranca chegou adiantada. */
    private void evaluateLockTrigger() {
        if (lockActionDone) return;
        if (!DOOR_LOCKED.equals(freshData(CarProps.DOOR_LOCK))) {
            lockRechecksLeft = 0;   // destrancou no meio das tentativas
            return;
        }

        float  speed  = parseFloat(freshData(CarProps.VEHICLE_SPEED), 0f);
        String gear   = freshData(CarProps.GEAR_STATUS);
        String engine = freshData(CarProps.ENGINE_STATE);
        if (speed > 0 || !GEAR_PARK.equals(gear) || !isEngineOff(engine)) {
            if (lockRechecksLeft-- > 0) {
                Log.w(TAG, "trancado mas condicoes nao batem, reavaliando em "
                        + LOCK_RECHECK_DELAY_MS + "ms (gear=" + gear + " engine=" + engine
                        + " speed=" + speed + ")");
                react.postDelayed(this::evaluateLockTrigger, LOCK_RECHECK_DELAY_MS);
            } else {
                log("trancado, mas as condicoes nao bateram — IGNORADO (gear=" + gear
                        + " engine=" + engine + " speed=" + speed + ")");
            }
            return;
        }

        lockActionDone   = true;
        lockRechecksLeft = 0;
        log("carro trancado em P com motor desligado (engine=" + engine + ")");

        if (prefs.getBoolean(Prefs.CLOSE_WINDOWS_ON_LOCK, Prefs.DEF_CLOSE_WINDOWS_ON_LOCK)) {
            if (closeAllWindows()) log("vidros fechados");
        }
        applyDisconnectOnLock();
    }

    private static boolean isEngineOff(String engineState) {
        if (engineState == null) return false;
        for (String off : ENGINE_OFF_VALUES) {
            if (off.equals(engineState)) return true;
        }
        return false;
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
    /**
     * Carro desligado: agora isto NAO toca nos radios. Desligar o carro nao significa
     * que o motorista foi embora — a central fica ligada minutos, e era ai que o
     * Android Auto continuava conectado com alguem ainda dentro do carro. Quem desliga
     * radio e a tranca, em applyDisconnectOnLock().
     */
    private void applyPowerOff() {
        prefs.edit().putBoolean(Prefs.VOLUME_APPLIED_THIS_CYCLE, false).apply();
        pushUiState();
    }

    /**
     * Metade "desconectar" do gatilho de tranca — funcionalidade 2.
     *
     * Havia aqui dois modos alternativos, "Bluetooth (invasivo)" e "Wi-Fi (invasivo)",
     * que desligavam o radio inteiro e SO religavam na ignicao. Sairam na v1.10.0: o
     * hold faz o mesmo servico com um teto de seguranca e religando no destranque, e
     * manter os tres era manter modos que brigam entre si.
     */
    private void applyDisconnectOnLock() {
        if (!prefs.getBoolean(Prefs.DISCONNECT_ON_LOCK, Prefs.DEF_DISCONNECT_ON_LOCK)) {
            log("desativar ao trancar esta desligado — nada a fazer");
            return;
        }
        dumpProjectionDiagnostics("antes de derrubar");
        dropAndHoldRadios();
        pushUiState();
    }

    /**
     * Guarda do hold: a central religa o Bluetooth por conta propria depois que
     * desligamos — tres vezes em ~3 s no log de 08/09 18:38. Sem este receiver o hold
     * seria engolido e nao surtiria efeito.
     *
     * O ramo do Wi-Fi saiu junto com o modo invasivo: o hold nao mexe no `svc wifi`, ele
     * derruba a interface pelo `ndc`, o que nao emite WIFI_STATE_CHANGED. Quem vigia a
     * interface e holdGuardTick(), por polling, porque nao existe broadcast para isso.
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
                        // Nao consulta pref nenhuma: quem decidiu desligar foi o hold, e
                        // enquanto ele esta de pe e ele quem manda.
                        if (holdInProgress) {
                            setBluetoothEnabled(false);
                            holdReDrops++;
                        }
                        pushUiState();
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, radioGuardReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
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
     * Derruba a interface do AAW e o Bluetooth na tranca — e SEGURA derrubados.
     *
     * Quem encerra o hold, no caminho normal, e a propria ROM desligando a central 3 a 4
     * minutos depois da tranca. Ver {@link #HOLD_MAX_MS} para por que isto deixou de ser
     * um "pisca" com religamento em 1 minuto.
     *
     * A ordem — interface primeiro, Bluetooth depois — nao e arbitraria: o link e o que
     * sustenta a sessao de projecao, e o Bluetooth e o que mantem o audio do telefone
     * preso no carro. No teste de 09/09 a wlan2 ficou caida com o Bluetooth ligado, e o
     * resultado foi o pior dos dois mundos: AA sem funcionar E audio capturado.
     *
     * Roda inteiro na thread react, com postDelayed em vez de sleep — a thread precisa
     * seguir atendendo onDataChanged durante todo o hold.
     */
    private void dropAndHoldRadios() {
        boolean btWasOn   = isBluetoothOn();
        boolean wifiWasOn = isWifiOn();
        // Persistido ANTES de tocar em radio: a ROM mata este processo com frequencia (24
        // criacoes contra 2 destruicoes no log de 08/09), e a rede de seguranca do
        // arranque depende desta flag ja estar em disco.
        prefs.edit()
                .putBoolean(Prefs.HOLD_PENDING, true)
                .putBoolean(Prefs.HOLD_BT_WAS_ON, btWasOn)
                .putBoolean(Prefs.HOLD_WIFI_WAS_ON, wifiWasOn)
                .apply();
        holdInProgress = true;
        holdTicks      = 0;
        holdReDrops    = 0;
        lockedAtMs     = SystemClock.elapsedRealtime();

        log("derrubando " + AawLink.IFACE + " e Bluetooth — segura ate a central desligar"
                + " (teto de " + (HOLD_MAX_MS / 60_000) + " min)");
        AawLink.setUp(false);
        if (btWasOn) setBluetoothEnabled(false);
        else log("Bluetooth ja estava desligado");

        react.postDelayed(holdGuardRunnable, HOLD_GUARD_INTERVAL_MS);
        react.postDelayed(holdCapRunnable,   HOLD_MAX_MS);
    }

    /**
     * Uma passagem da guarda: se o supplicant reassociou, derruba de novo.
     *
     * Silenciosa de proposito — quem fala e o resumo a cada {@link #HOLD_LOG_EVERY}
     * passagens. Uma linha por reassociacao seriam 11 por minuto, medidos em 12/09, e o
     * hold dura minutos: encheria o log rotativo de 192 KB e empurraria para fora a
     * evidencia que importa.
     */
    private void holdGuardTick() {
        if (!holdInProgress) return;
        if (AawLink.ipv4() != null) {
            AawLink.setUpQuietly(false);
            holdReDrops++;
        }
        if (++holdTicks % HOLD_LOG_EVERY == 0) {
            // Esta linha faz o servico do antigo heartbeat: a ultima que sair antes do
            // silencio e o registro de quando a ROM desligou a central.
            PersistentLog.w(TAG, "hold: " + minutosDesdeATranca() + " min apos a tranca, "
                    + AawLink.IFACE + " ip=" + (AawLink.ipv4() == null ? "(nenhum)" : "VOLTOU")
                    + ", bt=" + (isBluetoothOn() ? "ON (!)" : "off")
                    + ", re-derrubadas=" + holdReDrops);
        }
        react.postDelayed(holdGuardRunnable, HOLD_GUARD_INTERVAL_MS);
    }

    private long minutosDesdeATranca() {
        return (SystemClock.elapsedRealtime() - lockedAtMs) / 60_000;
    }

    /** Teto de seguranca: a ROM nao desligou a central, entao devolvemos os radios. */
    private void endHoldByCap() {
        endHold("teto de " + (HOLD_MAX_MS / 60_000) + " min");
    }

    /**
     * Encerra o hold e devolve os radios. Chamado pelo destranque, pela ignicao e pelo
     * teto — e por nada mais: no caminho normal a central desliga antes de qualquer um
     * dos tres, e nao ha o que restaurar.
     */
    private void endHold(String motivo) {
        if (!holdInProgress) return;
        boolean btWasOn   = prefs.getBoolean(Prefs.HOLD_BT_WAS_ON, false);
        boolean wifiWasOn = prefs.getBoolean(Prefs.HOLD_WIFI_WAS_ON, false);
        // Limpo ANTES de religar: com o flag de pe, radioGuardReceiver e a guarda
        // reverteriam a nossa propria restauracao.
        holdInProgress = false;
        react.removeCallbacks(holdGuardRunnable);
        react.removeCallbacks(holdCapRunnable);
        log("hold encerrado por " + motivo + " apos " + minutosDesdeATranca() + " min ("
                + holdReDrops + " re-derrubadas)");
        restoreRadios(btWasOn, wifiWasOn, motivo);
        scheduleRestoreVerification();
    }

    /**
     * Religa o que o hold desligou. Compartilhado com a recuperacao do arranque, para que
     * os dois caminhos religuem exatamente igual.
     */
    private void restoreRadios(boolean btWasOn, boolean wifiWasOn, String origem) {
        boolean subiu = AawLink.setUp(true);
        if (!subiu && wifiWasOn) {
            // Medido em 09/09: com a wlan2 caida o AA simplesmente nao funciona. Se o
            // `ndc up` nao recupera a interface, o ciclo completo de Wi-Fi a reconstroi.
            // Custa segundos e derruba o Wi-Fi de casa junto — mas a alternativa e o AAW
            // quebrado ate o proximo boot.
            log(origem + ": " + AawLink.IFACE
                    + " nao subiu pelo ndc — ciclo completo de Wi-Fi como ultimo recurso");
            setWifiEnabled(false);
            react.postDelayed(() -> setWifiEnabled(true), WIFI_CYCLE_GAP_MS);
        }
        if (btWasOn) setBluetoothEnabled(true);
        log(origem + ": religado (" + AawLink.IFACE + "=" + (subiu ? "up" : "AINDA DOWN")
                + ", bluetooth=" + (btWasOn ? "religado" : "seguiu desligado") + ")");
        prefs.edit().putBoolean(Prefs.HOLD_PENDING, false).apply();
        pushUiState();
    }

    /**
     * Depois de religar, a sessao volta? Medido em 12/09 com o antigo pisca: voltava em
     * menos de 15 s em 2 de 3 trancas. Foi essa medicao que trocou o pisca pelo hold, e
     * estas linhas seguem aqui para os casos em que ainda religamos.
     */
    private void scheduleRestoreVerification() {
        for (long delay : RESTORE_VERIFY_DELAYS_MS) {
            react.postDelayed(() -> PersistentLog.w(TAG,
                    "apos religar, " + (delay / 1000) + "s: " + AawLink.describe()
                            + " | projecao=" + AawLink.projectionProcessesLine()
                            + " | bt=" + (isBluetoothOn() ? "on" : "off")
                            + " " + AawLink.bluetoothConnectedLine()), delay);
        }
    }

    /**
     * Garante que a interface do AAW esta de pe quando o carro liga.
     *
     * A rede de seguranca do arranque cobre a central acordando com o hold
     * interrompido; esta cobre o resto — a interface ficar caida por qualquer motivo que
     * nao passou por nos (o teste manual do "Derrubar wlan2", por exemplo, que nao
     * levanta de volta). O requisito e "ao ligar o carro, conexao o mais rapido
     * possivel", e comecar a viagem com a interface DOWN e o oposto disso.
     *
     * Barato: um `ip link show` e, so se estiver caida, um `ndc`.
     */
    private void ensureAawInterfaceUp() {
        if (!AawLink.isDown()) return;
        log(AawLink.IFACE + " estava DOWN na partida — levantando");
        AawLink.setUp(true);
    }

    /**
     * Rede de seguranca do arranque.
     *
     * Se a ROM matou o processo durante o hold, a central acorda com a interface caida e
     * o Bluetooth desligado, e ninguem para religar. Nao e hipotese: e literalmente o
     * estado em que o teste manual de 09/09 deixou a central — AA sem funcionar e, por o
     * Bluetooth ter ficado conectado, o audio do telefone preso no carro.
     *
     * Atencao a diferenca em relacao ao fim normal do hold: aqui o carro pode estar
     * comecando um uso novo, entao religar e sempre certo.
     *
     * Roda em todo arranque do servico, com o carro ligado ou nao, porque a flag em
     * storage device-protected sobrevive a boot frio e e lida antes do unlock.
     */
    private void recoverFromInterruptedHold() {
        if (!prefs.getBoolean(Prefs.HOLD_PENDING, false)) return;
        boolean btWasOn   = prefs.getBoolean(Prefs.HOLD_BT_WAS_ON, false);
        boolean wifiWasOn = prefs.getBoolean(Prefs.HOLD_WIFI_WAS_ON, false);
        boolean precisaIface = AawLink.isDown();
        boolean precisaBt    = btWasOn && !isBluetoothOn();

        if (!precisaIface && !precisaBt) {
            // Ja normalizou sozinho. A flag e consumida de qualquer forma: deixa-la de
            // pe faria um desligamento manual de radio muito depois ser "restaurado" num
            // arranque futuro — o mesmo cuidado que restoreBluetoothIfPending() toma.
            prefs.edit().putBoolean(Prefs.HOLD_PENDING, false).apply();
            log("hold interrompido, mas os radios ja estavam normais — flag limpa");
            return;
        }
        log("hold interrompido detectado no arranque (" + AawLink.IFACE
                + (precisaIface ? "=DOWN" : "=up")
                + ", bluetooth=" + (isBluetoothOn() ? "on" : "off") + ")");
        restoreRadios(btWasOn, wifiWasOn, "recuperacao do arranque");
    }

    /**
     * Fotografia do estado da projecao, escrita no log.
     *
     * Existe porque tres palpites meus sobre quem sustenta a sessao do Android Auto sem
     * fio custaram tres rodadas de teste no carro: primeiro o pacote do celular
     * (gearhead), depois o app de tela (com.ts.androidauto.app), depois o
     * projectionservice — que o log de 04/09 mostrou nem ter processo. Em vez de um
     * quarto palpite, isto coleta os fatos: quais processos existem, quais interfaces
     * de rede estao de pe, quem detem o softAP e quais servicos de projecao rodam.
     *
     * Cada comando tem saida limitada — isto vai para um log rotativo de 192 KB.
     */
    private void dumpProjectionDiagnostics(String quando) {
        PersistentLog.w(TAG, "===== diagnostico de projecao (" + quando + ") =====");
        shDump("processos",
                "ps -A -o PID,ARGS | grep -iE 'androidauto|carplay|projection|aap|carlife' "
                        + "| grep -v grep | head -12");
        shDump("interfaces", "ip -o addr | sed 's/  */ /g' | cut -c1-100 | head -12");
        shDump("softap", "dumpsys wifi | grep -iE 'softap|local.?only|ap_state|apinterface' "
                + "| head -12");
        shDump("servicos de projecao",
                "dumpsys activity services | grep -iE 'androidauto|carplay|projection' "
                        + "| head -15");
        shDump("bluetooth conectado",
                "dumpsys bluetooth_manager | grep -iE 'connected|mconnection' | head -10");
        PersistentLog.w(TAG, "===== fim do diagnostico =====");
    }

    private void shDump(String rotulo, String cmd) {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c", cmd + " 2>&1"});
        String saida = r.stdout.trim();
        if (saida.isEmpty()) {
            PersistentLog.w(TAG, "[" + rotulo + "] (vazio) " + r.describeFailure());
            return;
        }
        for (String linha : saida.split("\n")) {
            PersistentLog.w(TAG, "[" + rotulo + "] " + linha.trim());
        }
    }

    /** Uma vez no arranque: registra qual receiver de projecao existe nesta central. */
    private void logProjectionPackages() {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(
                new String[]{"pm", "list", "packages"});
        if (!r.ran()) return;
        StringBuilder achados = new StringBuilder();
        for (String line : r.stdout.split("\n")) {
            String pkg = line.trim().replace("package:", "");
            String lower = pkg.toLowerCase();
            for (String hint : PROJECTION_HINTS) {
                if (lower.contains(hint)) {
                    achados.append(pkg).append(' ');
                    break;
                }
            }
        }
        PersistentLog.w(TAG, "pacotes de projecao instalados: "
                + (achados.length() == 0 ? "(nenhum)" : achados.toString().trim())
                + " | alvo=" + ANDROID_AUTO_PACKAGE);
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
        final String lock    = dataCache.get(CarProps.DOOR_LOCK);
        final String volume  = dataCache.get(CarProps.MEDIA_VOLUME);
        final boolean bt      = isBluetoothOn();
        final boolean wifi    = isWifiOn();
        mainHandler.post(() -> {
            ComfortStateHolder holder = ComfortStateHolder.INSTANCE;
            holder.setVehicleValue(CarProps.DRIVING_READY, ready);
            holder.setVehicleValue(CarProps.DOOR_LOCK, lock);
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
