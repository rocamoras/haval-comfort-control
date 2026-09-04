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

### O receiver é `com.ts.androidauto.app` — não o gearhead

`com.google.android.projection.gearhead` é o app do **celular**. Ele não está instalado
na central, e o `am force-stop` nele (v1.2.0–v1.4.0) falhava em silêncio — quem
derrubava a sessão era, na prática, só o `svc wifi disable`.

O receiver real do head unit está medido no carro:
`com.ts.androidauto.app/.display.AapActivity`, app de sistema VENDOR, Android 9. Ver a
memória `central-haval-fatos` e o `WindowModeUtils.kt` do haval-engine-reverse.

### O `.app` não basta — quem sustenta a sessão é o `projectionservice`

Medido em campo (log de 2026-09-04): `am force-stop com.ts.androidauto.app` voltou
`ok`, o `pidof` confirmou o processo morto, **e o telefone continuou conectado**. O
`.app` é só a parte de tela (`.display.AapActivity`).

A lista de pacotes do mesmo log revelou a resposta — a ROM tem oito pacotes de
projeção, entre eles:

```
com.ts.androidauto.projectionservice
com.autolink.androidauto.projectionservice
```

É o `projectionservice` que sustenta a sessão AAP e o AP do Wi-Fi. Desde a v1.6.0 o app
encerra as duas famílias (`ts` e `autolink` — não se sabe qual está ativa, e a inativa
não tem processo, então é barato). CarPlay fica de fora: não serve um telefone Android.

**O `pidof` agora é lido ANTES também.** Sem o valor anterior, "processo morto" não
distingue "matamos" de "nunca estava rodando" — foi essa ambiguidade que fez o log
dizer `encerrado` enquanto a sessão seguia viva em outro processo.

Ordem por invasividade:

| Ação | Default | Efeito colateral |
|---|---|---|
| `am force-stop` nos 4 pacotes de AA | **on** | nenhum |
| `svc bluetooth disable` | off | perde viva-voz |
| `svc wifi disable` | off | perde internet da central |

Os dois últimos ficaram como último recurso, para o caso de o receiver reiniciar
sozinho. Eram default `true` só enquanto o alvo do force-stop estava errado.

O `stopAndroidAuto()` **confere com `pidof`** depois do force-stop: recusar matar app de
sistema não necessariamente devolve exit != 0 no `am`.

`IConnectivityManager.stopTethering` (o que o app-tool usa) não serve para nada aqui —
desliga o AP do tethering, que é outro. Os `.aidl` de
`IConnectivityManager`/`ResultReceiver` foram removidos do projeto por isso.

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

## Atualização: instala pelo Shizuku, não pelo instalador do sistema

`REQUEST_INSTALL_PACKAGES` no manifest **não basta** desde o Android 8: existe um
appop por app ("Instalar apps desconhecidos") que o usuário precisa habilitar numa tela
do Settings — e esta central **não expõe essa tela**. O fluxo padrão
(FileProvider + `ACTION_VIEW`) morria num aviso sem saída.

`utils/ApkInstaller.kt` usa o Shizuku, que já é pré-requisito do app: copia o APK para
`/data/local/tmp` (o diretório externo do app fica sob `Android/data/<pkg>`, e a leitura
dele pelo uid de shell varia com o sdcardfs da ROM) e roda `pm install -r -d`. Com uid
de shell não há appop a pedir.

O `-d` aceita downgrade de `versionCode`, para voltar atrás numa release ruim sem
desinstalar. E o sucesso é decidido pelo **texto** da saída, não pelo exit code: o
`pm install` do Android 9 devolve 0 em alguns erros e escreve `Failure [MOTIVO]` na
saída.

O instalador do sistema ficou como fallback num diálogo que agora tem ação de verdade
— "Instalador do sistema" e "Permissões" (com `runCatching`, porque
`ACTION_MANAGE_UNKNOWN_APP_SOURCES` pode não existir na ROM).

## Diagnóstico de campo

Botão **Log** no cabeçalho abre o log persistente e oferece duas saídas:

- **Enviar** → sobe para o Firebase Storage do projeto `havalenginereverse` (mesmo
  bucket dos apps irmãos), em `logs/comfort_<timestamp>.txt`, e mostra a URL na tela
- **Salvar** → grava em `Android/data/<pkg>/files/diag.log`, para `adb pull`

`utils/LogUploader.kt` é uma **versão enxuta** do homônimo do climate-control (~530
linhas → ~170): só cabeçalho + `PersistentLog.dump()`. O que ficou de fora — logcat,
buffer de crash, eventos do ActivityManager, dumpsys — serve para investigar mortes de
processo causadas pela ROM; aqui o `PersistentLog` já registra cada decisão do gatilho
e cada comando de rádio com o resultado, que é o que responde às perguntas de campo.

Usa a API REST do Firebase, não o SDK: o `google-services.json` daquele projeto lista
apenas os applicationIds dele, e o plugin Gradle `google-services` falha com "No
matching client found" para um pacote não registrado. REST não precisa de arquivo nem
de dependência nova no Gradle.

O cabeçalho **não** inclui identificadores do device (serial, IMEI, Android ID) — o
link do bucket é público para quem o tem.

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
