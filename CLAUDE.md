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
- `holdInProgress` — os rádios estão derrubados desde a tranca, e a guarda reverte
  qualquer religamento de Bluetooth enquanto isso durar. Limpo **antes** de religarmos,
  senão a guarda reverteria a própria restauração.

## Android Auto sem fio (funcionalidade 2)

O objetivo real dessa funcionalidade é derrubar a sessão do **Android Auto sem fio**
quando o motorista sai e tranca o carro — a central fica ligada alguns minutos depois
disso e o telefone continuava conectado.

### Quem sustenta a sessão: respondido — é o link STA, não um processo

| Versão | Alvo | O que o log de campo mostrou |
|---|---|---|
| v1.2.0–1.4.0 | `com.google.android.projection.gearhead` | é o app do **celular**, nem existe na central — force-stop falhava em silêncio, e quem derrubava a sessão era o `svc wifi disable` |
| v1.5.0–1.5.1 | `com.ts.androidauto.app` | existe e **morre**, telefone **segue conectado**. É só a tela (`.display.AapActivity`) |
| v1.6.0 | `com.ts.androidauto.projectionservice` | "nunca teve processo" — **artefato do `pidof`** |
| v1.7.0 | ninguém (só diagnóstico) | 10 trancas de fatos: o transporte é STA e o `pidof` é inconfiável |
| v1.8.0 | os 8 pacotes, sem gate de `pidof` | **todos morreram e a sessão não caiu** — encerra a linha do force-stop |

**A central é cliente STA, não AP.** A `wlan2` pega IP por DHCP em `192.168.33.0/24`,
host diferente a cada sessão, e `mSoftApTetheredEvents:`/`mSoftApLocalOnlyEvents:` vêm
**vazios** — não existe softAP local (o `mApInterfaceName: wlan2` do dump é config
residual do `SoftApManager`). Quem cria o hotspot é o **celular**.

**Nenhum processo da central sustenta a sessão** (medido 09/09/2026):

```
MORTOS: 3546/com.ts.carplay 3754/com.ts.carplay.app 3785/com.ts.androidauto.app 3818/com.ts.androidauto
SOBREVIVERAM: (nenhum)
RESULTADO: wlan2 seguiu com 192.168.33.52 -> matar processo NAO derruba a sessao
```

**Só o `ndc` derruba o link**, e os outros dois caminhos mentem:

```
[ip link set wlan2 down]            ok | ip agora=192.168.33.52
[ifconfig wlan2 down]               ok | ip agora=192.168.33.52
[ndc interface setcfg wlan2 down]   ok | ip agora=(nenhum) -> CAIU
```

Exit 0 sem efeito nos dois primeiros — o mesmo `true` mentiroso do
`BluetoothAdapter.disable()`. Por isso `AawLink.setUp()` **confere o estado depois** em
vez de acreditar no exit code.

**`pidof <pacote>` não serve** para saber se um pacote de projeção roda: casa por *nome
de processo*. `app=ProcessRecord{... 3818:com.ts.androidauto/1000}` no `ServiceRecord` do
`com.ts.androidauto.projectionservice` prova que o processo dele se chama
`com.ts.androidauto`, que não é pacote instalado. Use `ps` — é o que
`AawLink.projectionProcesses()` faz.

### Derruba e segura — a ação da funcionalidade 2 (v1.12.0)

Toggle **Desativar**. Ao trancar: derruba `wlan2` e Bluetooth **nessa ordem** e os mantém
derrubados. Quem encerra, no caminho normal, é a própria ROM desligando a central.

A ordem não é arbitrária: o link sustenta a sessão de projeção, e o Bluetooth é o que
mantém o áudio do telefone preso no carro. Com `wlan2` caída e Bluetooth ligado — medido
em 09/09 — dá o pior dos dois mundos: AA sem funcionar **e** áudio capturado.

#### Por que deixou de ser um "pisca"

A v1.9.0–1.11.0 derrubava e religava (10 s, depois 1 min), para que os rádios voltassem
quentes e a partida seguinte fosse rápida. Duas medições de 12→15/09 derrubaram a
premissa **e** a solução:

| Medição | O que mostrou |
|---|---|
| `uptime do device` em todo arranque (11–14 s) + heartbeat pós-tranca parando sempre em 3 min | a ROM desliga a central 3–4 min depois da tranca, e toda ignição é **boot frio**. Não existe partida quente, então guardar rádio ligado não acelera nada |
| verificação aos 15/30/60 s em 3 trancas | o telefone **reconectou em menos de 15 s em 2 delas** (IP novo na `wlan2`). Religar era o que devolvia a sessão a um carro vazio |

Ou seja: a metade "religar" custava a solução e não comprava nada.

#### Três saídas, e só três

| Saída | O que faz |
|---|---|
| a ROM desliga a central (normal) | nada — não há o que restaurar, e o boot seguinte traz tudo ligado |
| destranque ou ignição | religa na hora: o motorista voltou |
| teto de `HOLD_MAX_MS` (10 min) | proteção para o caso de a central não desligar; na prática quase nunca dispara |

#### Proteções, cada uma por um motivo observado

| Proteção | Por quê |
|---|---|
| `holdGuardTick()` a cada 5 s | `ndc ... down` **não é durável**: numa janela de 1 min o supplicant reassociou **11 vezes**, uma a cada 5 s. Sem a guarda o log diria "derrubado" com a sessão de pé o tempo todo |
| `holdInProgress` + a guarda de broadcast | a ROM religou o Bluetooth 3× em ~3 s no log de 08/09. Limpo **antes** de religar, senão reverte a própria restauração |
| `HOLD_PENDING` em disco + `recoverFromInterruptedHold()` | a ROM mata este processo com frequência (24 criações × 2 destruições). Sem isso a central acorda com os rádios derrubados e ninguém para religar |
| `ensureAawInterfaceUp()` na ignição | cobre a interface ficar caída por fora do hold — o teste manual "Derrubar wlan2" não a levanta |
| ciclo completo de `svc wifi` se o `ndc up` não pegar | com a `wlan2` caída o AA não funciona; custa segundos e leva o Wi-Fi de casa, mas o alternativo é AAW quebrado até o boot |

O resumo da guarda sai a cada `HOLD_LOG_EVERY` passagens (~1 min). Uma linha por
reassociação seriam 11 por minuto e encheriam o log rotativo de 192 KB. **A última dessas
linhas antes do silêncio é o registro de quando a ROM desligou a central** — foi por isso
que o heartbeat dedicado da v1.11.0 saiu.

### Desligar a central não existe (medido 14–15/09)

`svc power shutdown` **reinicia**, não desliga. Três rodadas idênticas: o processo morre
no comando e ~21–25 s depois volta um processo novo com `uptime do device 11s`. O comando
funciona — a central desliga de verdade — mas o power manager a traz de volta, porque ela
é alimentada pelo carro. Os outros três degraus da escada nunca rodaram: o primeiro matou
o processo e a escada parou ali.

Sem cortar a alimentação, não há desligar. O que essa medição rendeu de útil foi o número
de 3–4 min, que é o que hoje encerra o hold.

### Testes manuais — botão Teste AAW

`utils/ProjectionProbe.kt`, três botões separados. Os três já responderam (acima) e ficam
como instrumento para medir de novo se a ROM ou o telefone mudarem.

**Desligar a central** já rodou e respondeu (acima): reinicia. A escada
(`svc power shutdown` → `reboot -p` → `ACTION_REQUEST_SHUTDOWN` →
`setprop sys.powerctl shutdown`) parou no primeiro degrau, então os outros três seguem
sem medição.

**Cuidados.** "Derrubar wlan2" derruba e **não levanta de volta** — quem restaura é o
hold ou o `ensureAawInterfaceUp()` da ignição. E "Desligar a central" é o único teste do
qual o app **não tem como se recuperar**: todos os outros têm rede de segurança no
arranque do serviço, e aqui o serviço deixa de existir. Cada tentativa é precedida de
`PersistentLog.flush()`, senão a linha que diz qual comando estava sendo tentado morreria
na fila junto com o processo.

O receiver está medido como `com.ts.androidauto.app/.display.AapActivity` (app de
sistema VENDOR, Android 9) — ver a memória `central-haval-fatos`. A ROM tem **oito**
pacotes de projeção, entre eles duas famílias (`ts` e `autolink`) de androidauto e
carplay.

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
