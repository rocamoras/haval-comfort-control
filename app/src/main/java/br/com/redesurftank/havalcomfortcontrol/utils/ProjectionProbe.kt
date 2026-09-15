package br.com.redesurftank.havalcomfortcontrol.utils

/**
 * Testes de campo do Android Auto sem fio, disparados à mão pela UI. **Os três já rodaram
 * no carro e deram resposta** — ficam porque são o instrumento que responde de novo se a
 * ROM ou o telefone mudarem, e porque o relatório deles é o que documenta o comportamento
 * atual.
 *
 * ### [forceStopProjection] — respondido: NÃO derruba a sessão
 *
 * Os oito pacotes force-stopados sem gate de `pidof`, os quatro processos vivos mortos,
 * nenhum de volta em 5 s — e a interface seguiu com IP e o vizinho `REACHABLE`:
 * ```
 * MORTOS: 3546/com.ts.carplay 3754/com.ts.carplay.app 3785/com.ts.androidauto.app 3818/com.ts.androidauto
 * SOBREVIVERAM: (nenhum)
 * RESULTADO: wlan2 seguiu com 192.168.33.52 -> matar processo NAO derruba a sessao
 * ```
 * Nenhum processo da central sustenta a sessão. Isso encerrou quatro versões de palpite
 * de force-stop (v1.2.0 a v1.8.0) e é por isso que o serviço não force-stopa mais nada.
 *
 * O teste também fechou o enigma do `pidof`, que casa por *nome de processo*:
 * `app=ProcessRecord{... 3818:com.ts.androidauto/1000}` no `ServiceRecord` do
 * `com.ts.androidauto.projectionservice` prova que o processo dele se chama
 * `com.ts.androidauto` — que não é pacote instalado. A conclusão da v1.6.0 de que esse
 * pacote "nunca tinha processo" era artefato de medição, e o gate de `pidof` que existia
 * no serviço fazia com que ele nunca fosse realmente encerrado.
 *
 * ### [dropAawInterface] — respondido: só o `ndc`
 *
 * ```
 * [ip link set wlan2 down]            ok | ip agora=192.168.33.52
 * [ifconfig wlan2 down]               ok | ip agora=192.168.33.52
 * [ndc interface setcfg wlan2 down]   ok | ip agora=(nenhum) -> CAIU
 * ```
 * Os dois primeiros devolveram **exit 0 sem fazer nada**. É esse comando que virou
 * produção em [AawLink.setUp], usado pelo hold dos rádios na tranca.
 *
 * Cuidado ao rodar este teste: ele derruba a interface e **não a levanta de volta**. Foi
 * assim que a central ficou sem Android Auto (e com o áudio do telefone preso, porque o
 * Bluetooth seguiu conectado) depois da rodada de 09/09. Quem restaura é o hold do
 * serviço ou o `ensureAawInterfaceUp()` da ignição, não o teste.
 *
 * ## Como os dois provam algo
 *
 * O que decide não é o comando ter rodado, é a interface perder o IPv4. Por isso as duas
 * funções fotografam o link **antes**, **depois** e **5 s depois** — a conferência
 * imediata só prova que o comando executou, não que a sessão caiu nem que ficou caída.
 *
 * Tudo aqui é bloqueante (shell via Shizuku, mais uma espera de 5 s) — chame de uma
 * thread de fundo. O relatório vai para o [PersistentLog] linha a linha, então sobrevive
 * a um restart do serviço e sai no próximo upload, e é devolvido como texto para a UI
 * mostrar na hora.
 */
object ProjectionProbe {

    private const val TAG = "ProjectionProbe"

    /** Espera antes da segunda conferência: o link pode voltar sozinho. */
    private const val RECHECK_DELAY_MS = 5_000L

    /**
     * Quanto esperar por um desligamento antes de declarar que o comando não pegou.
     *
     * Um shutdown ordenado não é instantâneo: o `PowerManager` avisa os serviços, espera
     * o broadcast de ACTION_SHUTDOWN e só então derruba. Declarar falha em 200 ms faria a
     * escada correr inteira enquanto o primeiro degrau ainda estava funcionando.
     */
    private const val POWEROFF_SETTLE_MS = 6_000L

    /**
     * Caminhos para desligar a central, do mais provável para o mais exótico — mesma
     * forma de escada que resolveu a [dropAawInterface], e pelo mesmo motivo: nenhum é
     * garantido com uid de `shell` e só o carro diz qual esta ROM aceita.
     *
     * - `svc power shutdown` chama `IPowerManager.shutdown()` e exige a permissão REBOOT,
     *   que o shell costuma ter;
     * - `reboot -p` é o toybox, normalmente root;
     * - a activity de ACTION_REQUEST_SHUTDOWN é o caminho da UI do sistema;
     * - `setprop sys.powerctl shutdown` fala com o init, e o SELinux costuma barrar.
     */
    private val SHUTDOWN_COMMANDS = arrayOf(
        "svc power shutdown",
        "reboot -p",
        "am start -a android.intent.action.ACTION_REQUEST_SHUTDOWN "
                + "--ez android.intent.extra.KEY_CONFIRM false "
                + "--ez android.intent.extra.USER_REQUESTED_SHUTDOWN true",
        "setprop sys.powerctl shutdown",
    )

    /**
     * Alvos do force-stop. Os oito pacotes de projeção instalados nesta ROM, conforme o
     * `pm list packages` do log — inclusive os de CarPlay, por causa do
     * `CarPlayRemoteService` compartilhado.
     *
     * Ordem: `projectionservice` primeiro (o suspeito que nunca foi realmente testado),
     * depois os apps de tela, depois a família `autolink`.
     */
    private val PROJECTION_PACKAGES = arrayOf(
        "com.ts.androidauto.projectionservice",
        "com.ts.carplay.app",
        "com.ts.androidauto.app",
        "com.ts.carplay",
        "com.autolink.androidauto.projectionservice",
        "com.autolink.carplay.app",
        "com.autolink.androidauto.app",
        "com.autolink.carplay",
    )

    /**
     * Tentativas de derrubar a interface, da menos para a mais exótica.
     *
     * Nenhuma era garantida com uid de `shell`: `ip link set ... down` exige
     * `CAP_NET_ADMIN`, que o shell não tem no Android 9, e o `ndc` fala com o `netd`,
     * que costuma exigir root. O teste de 09/09 respondeu: só o `ndc` funciona, e os
     * outros dois devolvem exit 0 sem fazer nada. A escada fica porque o valor dela é
     * justamente medir isso de novo se a ROM mudar — o `ndc` é o último degrau, e
     * [AawLink.setUp] é quem o executa em produção.
     */
    private val IFACE_DOWN_COMMANDS = arrayOf(
        "ip link set ${AawLink.IFACE} down",
        "ifconfig ${AawLink.IFACE} down",
        "ndc interface setcfg ${AawLink.IFACE} down",
    )

    // ─────────────────────────────────────────────────────────────
    // Teste 1 — derrubar só a interface do AAW
    // ─────────────────────────────────────────────────────────────

    /** Devolve o relatório do teste, já escrito também no [PersistentLog]. */
    fun dropAawInterface(): String {
        val r = Report()
        r.line("===== teste: derrubar so a ${AawLink.IFACE} =====")
        if (!requireShizuku(r)) return r.finish()

        val ipAntes = AawLink.ipv4()
        snapshotIface(r, "antes")
        if (ipAntes == null) {
            r.line("AVISO: ${AawLink.IFACE} sem IPv4 — a sessao do AAW parece nao estar de pe.")
            r.line("       O teste segue, mas so mede se o comando executa, nao se derruba.")
        }

        var venceu: String? = null
        for (cmd in IFACE_DOWN_COMMANDS) {
            val res = sh(cmd)
            val ipDepois = AawLink.ipv4()
            val caiu = ipDepois == null && ipAntes != null
            r.line("[$cmd] ${res.describeFailure()} | ip agora=${ipDepois ?: "(nenhum)"}"
                    + if (caiu) " -> CAIU" else "")
            if (caiu) { venceu = cmd; break }
        }

        if (venceu == null) {
            r.line("RESULTADO: nenhum comando derrubou a ${AawLink.IFACE} com uid de shell.")
            r.line("           Resta `svc wifi disable` (o toggle invasivo no cartao),")
            r.line("           que leva o Wi-Fi de casa junto.")
        } else {
            r.line("RESULTADO: `$venceu` derrubou a ${AawLink.IFACE}.")
        }

        sleep(RECHECK_DELAY_MS)
        snapshotIface(r, "${RECHECK_DELAY_MS / 1000}s depois")
        snapshotProcesses(r, "${RECHECK_DELAY_MS / 1000}s depois")
        r.line("CONFIRA NO CELULAR: o Android Auto caiu de verdade?")
        return r.finish()
    }

    // ─────────────────────────────────────────────────────────────
    // Teste 2 — force-stop incondicional dos pacotes de projeção
    // ─────────────────────────────────────────────────────────────

    /** Devolve o relatório do teste, já escrito também no [PersistentLog]. */
    fun forceStopProjection(): String {
        val r = Report()
        r.line("===== teste: force-stop de todos os pacotes de projecao =====")
        if (!requireShizuku(r)) return r.finish()

        val antes = AawLink.projectionProcesses()
        val ipAntes = AawLink.ipv4()
        snapshotIface(r, "antes")
        r.line("[processos antes] " + AawLink.describe(antes))
        snapshotServices(r, "antes")
        // O que resolve o enigma do `pidof`: qual PACOTE e dono do processo chamado
        // `com.ts.androidauto`, que nao existe como pacote instalado.
        shDump(r, "proc->pkg",
            "dumpsys activity processes | grep -iE 'androidauto|carplay' | head -20")

        for (pkg in PROJECTION_PACKAGES) {
            // Incondicional de proposito: sem consultar `pidof` antes. Era o gate do
            // pidof que impedia o projectionservice de ser force-stopado.
            val res = sh("am force-stop $pkg")
            r.line("[force-stop $pkg] ${res.describeFailure()}")
        }

        val depois = AawLink.projectionProcesses()
        val mortos = antes.keys - depois.keys
        val vivos  = antes.keys intersect depois.keys
        r.line("[processos depois] " + AawLink.describe(depois))
        r.line("MORTOS: " + if (mortos.isEmpty()) "(nenhum)"
                            else mortos.joinToString(" ") { "$it/${antes[it]}" })
        r.line("SOBREVIVERAM: " + if (vivos.isEmpty()) "(nenhum)"
                                  else vivos.joinToString(" ") { "$it/${antes[it]}" })
        val ipDepois = AawLink.ipv4()
        snapshotIface(r, "depois")
        r.line("RESULTADO: ${AawLink.IFACE} ${
            when {
                ipAntes == null  -> "nao tinha IPv4 antes — teste inconclusivo, conecte o AAW primeiro"
                ipDepois == null -> "PERDEU o IPv4 -> matar processo DERRUBA a sessao"
                else             -> "seguiu com $ipDepois -> matar processo NAO derruba a sessao"
            }
        }")

        sleep(RECHECK_DELAY_MS)
        snapshotIface(r, "${RECHECK_DELAY_MS / 1000}s depois")
        snapshotProcesses(r, "${RECHECK_DELAY_MS / 1000}s depois")
        snapshotServices(r, "${RECHECK_DELAY_MS / 1000}s depois")
        r.line("CONFIRA NO CELULAR: o Android Auto caiu de verdade?")
        return r.finish()
    }

    // ─────────────────────────────────────────────────────────────
    // Teste 3 — desligar a central
    // ─────────────────────────────────────────────────────────────

    /**
     * Desliga a central, se a ROM deixar.
     *
     * ## Por que isto vale um teste
     *
     * O `uptime do device` que o [PersistentLog] grava a cada arranque diz que **a
     * central já faz um boot frio a cada uso do carro**: em 15 sessões do log de
     * 05→08/09, o serviço sempre sobe com o device entre 11 s e 14 s de uptime. O uptime
     * zerar (e não acumular, como aconteceria num sleep) significa kernel reiniciado.
     *
     * Isso derruba a objeção óbvia. Desligar a central **não custa nada na partida
     * seguinte**, porque ela ia bootar do zero de qualquer forma — o requisito de
     * "conectar o mais rápido possível" fica intacto.
     *
     * ## RESPONDIDO (14 e 15/09/2026): reinicia, não desliga
     *
     * Três rodadas, idênticas. O processo morre em `tentando: svc power shutdown` e ~21 a
     * 25 s depois volta um processo novo com `uptime do device 11s`. O comando **funciona**
     * — a central desliga de verdade — mas o power manager a traz de volta, porque ela é
     * alimentada pelo carro e a energia continua lá. Os outros três degraus da escada
     * nunca chegaram a rodar: o primeiro matou o processo e a escada parou ali.
     *
     * Desligar sem cortar a alimentação não existe nesta central. O que a medição deu de
     * útil foi outra coisa: a ROM desliga a central sozinha 3 a 4 min depois da tranca, e
     * é isso que hoje encerra o hold dos rádios.
     *
     * ## Cuidados
     *
     * Este é o único teste do qual **o app não tem como se recuperar** — todos os outros
     * têm rede de segurança no arranque do serviço, e aqui o serviço deixa de existir. Se
     * a central não voltar sozinha na ignição, não há nada no app que conserte.
     *
     * Cada tentativa é precedida de um [PersistentLog.flush]: a escrita é assíncrona, e
     * sem isso a linha que diz qual comando estava sendo tentado morreria na fila junto
     * com o processo — o teste não deixaria evidência nenhuma do que fez.
     */
    fun powerOffHeadUnit(): String {
        val r = Report()
        r.line("===== teste: desligar a central =====")
        if (!requireShizuku(r)) return r.finish()

        r.line("[antes] ${AawLink.describe()}")
        r.line("[antes] projecao=${AawLink.projectionProcessesLine()}")
        r.line("AVISO: se algum comando pegar, o processo morre no meio deste relatorio —")
        r.line("       a ULTIMA linha de tentativa e a que desligou. O log e descarregado")
        r.line("       em disco antes de cada tentativa justamente para isso.")

        for (cmd in SHUTDOWN_COMMANDS) {
            r.line("tentando: $cmd")
            PersistentLog.flush(2_000)
            val res = sh(cmd)
            // Se a execucao chegou aqui, ou o comando falhou ou o desligamento e lento.
            sleep(POWEROFF_SETTLE_MS)
            r.line("[$cmd] seguimos vivos apos ${POWEROFF_SETTLE_MS / 1000}s — ${res.describeFailure()}")
        }

        r.line("RESULTADO: nenhum comando desligou a central com uid de shell.")
        return r.finish()
    }

    // ─────────────────────────────────────────────────────────────
    // Coleta
    // ─────────────────────────────────────────────────────────────

    private fun requireShizuku(r: Report): Boolean {
        if (ShizukuUtils.isAvailable()) return true
        r.line("Shizuku indisponivel — o Climate Control subiu o server nesta central?")
        return false
    }

    /**
     * Estado da interface: flags do link, IP, e o vizinho — que é o **celular**, e é a
     * evidência de que o AP é dele e não da central.
     */
    private fun snapshotIface(r: Report, quando: String) {
        val link = sh("ip link show ${AawLink.IFACE} | head -1").stdout
            .substringAfter(':').trim().take(90)
        val ip   = AawLink.ipv4() ?: "(nenhum)"
        val ap   = sh("ip neigh show dev ${AawLink.IFACE} | head -2").stdout
            .replace('\n', ' ').trim().take(90)
        r.line("[${AawLink.IFACE} $quando] ip=$ip | link=$link | vizinho=${ap.ifEmpty { "(nenhum)" }}")
    }

    private fun snapshotProcesses(r: Report, quando: String) {
        r.line("[processos $quando] " + AawLink.projectionProcessesLine())
    }

    private fun snapshotServices(r: Report, quando: String) {
        shDump(r, "servicos $quando",
            "dumpsys activity services | grep -iE 'ServiceRecord|app=' "
                    + "| grep -iE 'androidauto|carplay|projection' | head -12")
    }

    private fun shDump(r: Report, rotulo: String, cmd: String) {
        val res = sh(cmd)
        val saida = res.stdout.trim()
        if (saida.isEmpty()) {
            r.line("[$rotulo] (vazio) ${res.describeFailure()}")
            return
        }
        for (linha in saida.split('\n')) r.line("[$rotulo] ${linha.trim().take(110)}")
    }

    private fun sh(cmd: String): ShizukuUtils.ShellResult = AawLink.sh(cmd)

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    /**
     * Acumula o relatório e escreve cada linha no log persistente na hora — se a ROM
     * matar o processo no meio do teste, o que já foi medido sobrevive.
     */
    private class Report {
        private val sb = StringBuilder(4096)

        fun line(texto: String) {
            PersistentLog.w(TAG, texto)
            sb.append(texto).append('\n')
        }

        fun finish(): String {
            line("===== fim do teste =====")
            return sb.toString()
        }
    }
}
