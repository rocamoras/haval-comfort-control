# Haval Comfort Control

App enxuto de conforto para a central do Haval. Nasceu como recorte do
`haval-app-tool-multimidia`, que ficou lento por carregar recursos que este app não
usa (Frida, cluster/instrumentos, projetores, Termux/SSH, iptables em timer, ~100 MB
de `res/`). Aqui são quatro funcionalidades e nada mais.

## Dependência de Shizuku — leia antes de mexer no serviço

Este app **não sobe o `shizuku_server`**. Quem sobe é o `haval-climate-control`
(futuramente um app Core que vai centralizar isso). O server é singleton: rodar o
`libshizuku.so` mata o processo que já estava de pé, então se este app subisse o
dele derrubaria o do outro — e os dois ficariam se matando em loop.

`ComfortControlService` apenas espera o binder existente aparecer
(`addBinderReceivedListenerSticky`, timeout de 30 s → `restart()`). Se o
climate-control não estiver instalado/rodando nesta central, este app fica em loop
de restart e o motivo aparece no log persistente.

## Latência das funcionalidades 2 e 4

O requisito é "sem delay" na partida. O que foi feito por isso:

- Reações rodam numa `HandlerThread` em `THREAD_PRIORITY_DISPLAY`, sem debounce.
  O debounce de 120 ms existe só para o push de estado da UI.
- Na transição de `car.basic.driving_ready_state` para ligado, a ordem é **volume →
  Bluetooth → Wi-Fi**. Volume é uma chamada de binder e resolve na hora; os rádios vão
  pelo `svc` via Shizuku, que cria processo e custa muito mais.
- Bluetooth usa `svc bluetooth` via Shizuku como caminho principal, com conferência
  1,5 s depois e o `BluetoothAdapter` como segunda tentativa. **Não inverter essa
  ordem**: `adapter.disable()` devolve `true` significando "pedido aceito", não "rádio
  mudou", e nesta ROM o pedido é engolido — foi exatamente esse `true` mentiroso que
  fez o Bluetooth não desligar na v1.0.0.
- Todas as preferências ficam em **storage device-protected** (`comfort_prefs`),
  porque o serviço é `directBootAware` e lê no `LOCKED_BOOT_COMPLETED`, antes do
  unlock. Em credential storage o volume inicial leria o default em todo boot frio.

O piso de latência num boot frio é o binder do Shizuku (4–6 s medidos em campo pelo
climate-control). Num ciclo de ignição em que a central não reinicia, a reação é
imediata — o serviço já está conectado ouvindo a propriedade.

## Regra de versionamento

Antes de cada commit+push, incremente em `app/build.gradle.kts`:
- `versionCode` → +1
- `versionName` → semver (patch para correção, minor para funcionalidade, major para
  quebra)

O `versionName` é o que o botão **Atualizar** em `MainActivity.kt` compara com a tag
da Release no GitHub. As tags precisam casar exatamente (`v1.0.1` para
`versionName = "1.0.1"`), e o repositório apontado é `rocamoras/haval-comfort-control`.

## Propriedades do veículo usadas

| Propriedade | Uso |
|---|---|
| `car.basic.door_lock_status` | **gatilho das funcionalidades 1 e 2**: `1` = trancado, `3` = destrancado |
| `car.basic.engine_state` | `-1` e `15` = motor desligado (valores tirados do `isMainScreenOn()` e do `ProjectorManager` do app-tool) |
| `car.basic.vehicle_speed` | guarda: nunca age com o carro em movimento |
| `car.basic.gear_status` | guarda: exige P (`3`) |
| `car.basic.driving_ready_state` | `-1`/`0` = desligado; outro = ligado |
| `car.frs_setting.distraction_detection_enable` | `1` = aviso ativo → desliga |
| `sys.settings.audio.media_volume` | volume inicial |

## Gatilho das funcionalidades 1 e 2 — a tranca

Fechar os vidros e desligar os rádios acontecem no **mesmo** evento: o carro foi
**trancado** estando **em P** com o **motor desligado**.

Os gatilhos anteriores foram descartados por serem imprecisos:

| Gatilho antigo | Por que saiu |
|---|---|
| retrovisores rebatidos (vidros) | rebate em outras situações, e nem sempre rebate |
| `driving_ready` → desligado (rádios) | desligar o carro não significa que alguém saiu — a central fica ligada minutos com o motorista dentro, e era aí que o Android Auto continuava conectado |

A tranca é um evento **único**: se `door_lock_status=1` chegar antes de `engine_state`
virar desligado, a condição falha e não haveria segunda chance naquele uso do carro.
Por isso a avaliação se reagenda até 4 vezes a cada 3 s (`LOCK_RECHECK_*`). Assinar
`engine_state` seria a alternativa, mas num híbrido ele muda toda hora por start-stop —
seria churn constante para cobrir uma corrida de segundos.

Dois flags de estado governam o ciclo:
- `lockActionDone` — a ROM repete `door_lock_status=1`; sem isso cada repetição
  refaria tudo. Zera ao destrancar e na partida.
- `radiosOffByLock` — só enquanto true a guarda reverte um religamento de
  Bluetooth/Wi-Fi. Impede de brigar com o usuário que destrancou e voltou.

## Android Auto sem fio (funcionalidade 2)

O objetivo real dessa funcionalidade é derrubar a sessão do **Android Auto sem fio**
quando o motorista sai e tranca o carro — a central fica ligada alguns minutos depois
disso e o telefone continuava conectado.

O link do AAW **não passa pelo tethering do Android**: é um AP próprio da central
(LocalOnlyHotspot / softAP do serviço de projeção). Por isso
`IConnectivityManager.stopTethering`, que é o que o app-tool usa, não resolve — ele
desliga outro AP. Os `.aidl` de `IConnectivityManager`/`ResultReceiver` foram removidos
do projeto por isso.

O caminho atual é: `am force-stop com.google.android.projection.gearhead`, depois
`svc wifi disable`, religando na partida. O Wi-Fi da central **inteiro** cai — decisão
do usuário, ciente de que a central fica sem Wi-Fi enquanto o carro está trancado. O estado do Wi-Fi tem API pública
(`WifiManager.isWifiEnabled()`), sem reflexão em `@hide`.

A ROM **não tem** nenhuma propriedade sobre projeção/Android Auto — varri as 800+
chaves do `CarConstants` do app-tool. `sys.network.hotspot_state` e
`car.configure.mobile_bluetooth_key` existem no catálogo mas são código morto lá, e
não servem para isso.

Vidros vão pelo `IVehicle` (`getWindowsStatus`/`setWindowStatus`, `1` = fechado),
obtido do `IBinderPool` do `VoiceAdapterService` em `queryBinder(6)`. Esse binder é
**re-adquirido sob demanda**: o `VoiceAdapterService` reinicia sozinho e leva o binder
com ele, sem que nada nos avise — guardar o do init fazia o fechamento falhar em
silêncio até o próximo boot.

**Não reordenar métodos nos `.aidl`**: a ordem define os códigos de transação e tem
que casar com o serviço do outro lado na ROM.

## Display do Multimídia Haval (medido em campo — 2026-05-10)

| Campo | Valor |
|---|---|
| Resolução usável (px) | 1792 × 720 |
| Resolução física real (px) | 1920 × 720 |
| Tamanho usável (dp) | 1792 × 720 dp |
| screenHeightDp | 660 dp ← área útil abaixo da status bar |
| Densidade lógica | 160 dpi (mdpi) — 1 dp = 1 px exato |
| Proporção W/H | ~2.49 (aprox. 12:5) |

**Regras para layouts nesta tela:**
- Use `dp` normalmente — o fator é 1.00 neste device.
- Área de trabalho real: **1792 × 660 dp**.
- A tela é muito mais larga que alta — prefira layouts horizontais, evite `Column`
  longas com scroll.

## Build local

```
JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
./gradlew assembleDebug
```

O `assembleRelease` precisa de `app/release.keystore` e das variáveis
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` — no CI vêm dos
secrets. Para validar o R8 sem keystore: `./gradlew :app:minifyReleaseWithR8`.
