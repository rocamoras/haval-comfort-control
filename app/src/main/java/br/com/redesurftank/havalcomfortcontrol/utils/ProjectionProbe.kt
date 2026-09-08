package br.com.redesurftank.havalcomfortcontrol.utils

/**
 * Dois testes de campo, disparados à mão pela UI, para descobrir o que derruba a sessão
 * do **Android Auto sem fio**.
 *
 * ## Por que testes manuais e não um quarto palpite no gatilho da tranca
 *
 * Três palpites sobre quem sustenta a sessão já custaram três rodadas de teste no carro
 * (ver o histórico em `ANDROID_AUTO_PACKAGES` no serviço). O log da v1.7.0, com
 * `dumpProjectionDiagnostics()`, finalmente trouxe fatos em vez de palpite — e eles
 * apontam para duas hipóteses concretas, uma por função aqui:
 *
 * ### 1. [dropAawInterface] — a central é **cliente STA**, não AP
 *
 * Nos 10 eventos de tranca do log de 08/09/2026, sem exceção: `wlan2` tem IP por DHCP
 * em `192.168.33.0/24`, com host diferente a cada sessão (.17 .40 .42 .82 .137 .163
 * .191 .199 .239 .245), e o `dumpsys wifi` mostra `mSoftApTetheredEvents:` e
 * `mSoftApLocalOnlyEvents:` **vazios**. Não existe softAP local — o `mApInterfaceName:
 * wlan2` do dump é config residual do `SoftApManager`, não AP ativo.
 *
 * Quem cria o hotspot é o **celular**. Isso explica por que `svc wifi disable` derrubava
 * a sessão e matar processo nunca derrubou: a sessão vive no link STA do `wlan2`. Se
 * derrubar só essa interface funcionar, a central fica com o Wi-Fi de casa (`wlan0`)
 * intacto — que é o que o `svc wifi disable` levava embora junto.
 *
 * ### 2. [forceStopProjection] — o `pidof` mentiu, e o alvo certo nunca foi testado
 *
 * `pidof <pacote>` casa por **nome de processo**. Os processos vivos em toda tranca são
 * `com.ts.carplay`, `com.ts.androidauto`, `com.ts.carplay.app` e
 * `com.ts.androidauto.app` — e `com.ts.androidauto` **não é pacote instalado** (não sai
 * no `pm list packages` do próprio log), é nome de processo de outro pacote.
 *
 * Logo, a conclusão da v1.6.0 de que `com.ts.androidauto.projectionservice` "nunca tem
 * processo" era **artefato do `pidof`**: no evento de 06/09 13:10:55 o
 * `dumpsys activity services` trouxe
 * `ServiceRecord{... com.ts.androidauto.projectionservice/.AndroidAutoService}` ativo no
 * mesmo instante em que o log dizia "nao estava rodando". E como `stopAndroidAuto()` faz
 * `continue` quando o `pidof` vem vazio, esse pacote **nunca foi force-stopado de
 * verdade**.
 *
 * Por isso aqui o force-stop é **incondicional** — o `am force-stop` recebe pacote e não
 * precisa de pid — e inclui os pacotes de CarPlay: o
 * `com.ts.carplay.app/.service.CarPlayRemoteService` é o serviço de projeção
 * *compartilhado*, com `com.ts.androidauto` e `com.ts.carplay` como clientes bindados
 * nele em todas as trancas.
 *
 * ## Como os dois testes provam algo
 *
 * O que decide não é o comando ter rodado, é o `wlan2` perder o IPv4. Por isso as duas
 * funções fotografam a interface **antes**, **depois** e **5 s depois** — a conferência
 * imediata só prova que o comando executou, não que a sessão caiu nem que ficou caída.
 *
 * Tudo aqui é bloqueante (shell via Shizuku, mais uma espera de 5 s) — chame de uma
 * thread de fundo. O relatório vai para o [PersistentLog] linha a linha, então sobrevive
 * a um restart do serviço e sai no próximo upload, e é devolvido como texto para a UI
 * mostrar na hora.
 */
object ProjectionProbe {

    private const val TAG = "ProjectionProbe"

    /** Interface do AAW nesta central — medida em campo, sempre `192.168.33.0/24`. */
    private const val AAW_IFACE = "wlan2"

    /** Espera antes da segunda conferência: o link pode voltar sozinho. */
    private const val RECHECK_DELAY_MS = 5_000L

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
     * Nenhuma é garantida com uid de `shell`: `ip link set ... down` exige
     * `CAP_NET_ADMIN`, que o shell não tem por padrão no Android 9, e o `ndc` fala com o
     * `netd`, que costuma exigir root. É exatamente por isso que isto é uma escada com
     * o resultado de cada degrau no log em vez de um comando só — o teste de campo diz
     * qual (se algum) esta ROM aceita.
     */
    private val IFACE_DOWN_COMMANDS = arrayOf(
        "ip link set $AAW_IFACE down",
        "ifconfig $AAW_IFACE down",
        "ndc interface setcfg $AAW_IFACE down",
    )

    // ─────────────────────────────────────────────────────────────
    // Teste 1 — derrubar só a interface do AAW
    // ─────────────────────────────────────────────────────────────

    /** Devolve o relatório do teste, já escrito também no [PersistentLog]. */
    fun dropAawInterface(): String {
        val r = Report()
        r.line("===== teste: derrubar so a $AAW_IFACE =====")
        if (!requireShizuku(r)) return r.finish()

        val ipAntes = ipv4Of(AAW_IFACE)
        snapshotIface(r, "antes")
        if (ipAntes == null) {
            r.line("AVISO: $AAW_IFACE sem IPv4 — a sessao do AAW parece nao estar de pe.")
            r.line("       O teste segue, mas so mede se o comando executa, nao se derruba.")
        }

        var venceu: String? = null
        for (cmd in IFACE_DOWN_COMMANDS) {
            val res = sh(cmd)
            val ipDepois = ipv4Of(AAW_IFACE)
            val caiu = ipDepois == null && ipAntes != null
            r.line("[$cmd] ${res.describeFailure()} | ip agora=${ipDepois ?: "(nenhum)"}"
                    + if (caiu) " -> CAIU" else "")
            if (caiu) { venceu = cmd; break }
        }

        if (venceu == null) {
            r.line("RESULTADO: nenhum comando derrubou a $AAW_IFACE com uid de shell.")
            r.line("           Resta `svc wifi disable` (o toggle invasivo no cartao),")
            r.line("           que leva o Wi-Fi de casa junto.")
        } else {
            r.line("RESULTADO: `$venceu` derrubou a $AAW_IFACE.")
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

        val antes = projectionProcesses()
        val ipAntes = ipv4Of(AAW_IFACE)
        snapshotIface(r, "antes")
        r.line("[processos antes] " + describe(antes))
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

        val depois = projectionProcesses()
        val mortos = antes.keys - depois.keys
        val vivos  = antes.keys intersect depois.keys
        r.line("[processos depois] " + describe(depois))
        r.line("MORTOS: " + if (mortos.isEmpty()) "(nenhum)"
                            else mortos.joinToString(" ") { "$it/${antes[it]}" })
        r.line("SOBREVIVERAM: " + if (vivos.isEmpty()) "(nenhum)"
                                  else vivos.joinToString(" ") { "$it/${antes[it]}" })
        val ipDepois = ipv4Of(AAW_IFACE)
        snapshotIface(r, "depois")
        r.line("RESULTADO: $AAW_IFACE ${
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
    // Coleta
    // ─────────────────────────────────────────────────────────────

    private fun requireShizuku(r: Report): Boolean {
        if (ShizukuUtils.isAvailable()) return true
        r.line("Shizuku indisponivel — o Climate Control subiu o server nesta central?")
        return false
    }

    /** IPv4 da interface, ou null se ela não tem endereço (ou não existe). */
    private fun ipv4Of(iface: String): String? {
        val out = sh("ip -o -4 addr show $iface").stdout
        return Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
    }

    /**
     * Estado da interface: flags do link, IP, e o vizinho — que é o **celular**, e é a
     * evidência de que o AP é dele e não da central.
     */
    private fun snapshotIface(r: Report, quando: String) {
        val link = sh("ip link show $AAW_IFACE | head -1").stdout
            .substringAfter(':').trim().take(90)
        val ip   = ipv4Of(AAW_IFACE) ?: "(nenhum)"
        val ap   = sh("ip neigh show dev $AAW_IFACE | head -2").stdout
            .replace('\n', ' ').trim().take(90)
        r.line("[$AAW_IFACE $quando] ip=$ip | link=$link | vizinho=${ap.ifEmpty { "(nenhum)" }}")
    }

    private fun snapshotProcesses(r: Report, quando: String) {
        r.line("[processos $quando] " + describe(projectionProcesses()))
    }

    private fun snapshotServices(r: Report, quando: String) {
        shDump(r, "servicos $quando",
            "dumpsys activity services | grep -iE 'ServiceRecord|app=' "
                    + "| grep -iE 'androidauto|carplay|projection' | head -12")
    }

    /**
     * pid → nome de processo dos processos de projeção vivos.
     *
     * Via `ps`, e não `pidof`: é o nome de processo que aparece aqui, e ele **não** casa
     * com o pacote (`com.ts.androidauto` é processo, não pacote). O diff de pids entre
     * antes e depois é o que dá a evidência causal, sem depender desse mapeamento.
     */
    private fun projectionProcesses(): Map<String, String> {
        val out = sh("ps -A -o PID,ARGS | grep -iE 'androidauto|carplay|projection|aap' "
                + "| grep -v grep | head -12").stdout
        val mapa = LinkedHashMap<String, String>()
        for (linha in out.lineSequence()) {
            val campos = linha.trim().split(Regex("""\s+"""), limit = 2)
            if (campos.size == 2 && campos[0].all { it.isDigit() }) {
                mapa[campos[0]] = campos[1].trim()
            }
        }
        return mapa
    }

    private fun describe(procs: Map<String, String>): String =
        if (procs.isEmpty()) "(nenhum)"
        else procs.entries.joinToString(" ") { "${it.key}/${it.value}" }

    private fun shDump(r: Report, rotulo: String, cmd: String) {
        val res = sh(cmd)
        val saida = res.stdout.trim()
        if (saida.isEmpty()) {
            r.line("[$rotulo] (vazio) ${res.describeFailure()}")
            return
        }
        for (linha in saida.split('\n')) r.line("[$rotulo] ${linha.trim().take(110)}")
    }

    /** `2>&1` para que a mensagem de permissão negada entre no relatório. */
    private fun sh(cmd: String): ShizukuUtils.ShellResult =
        ShizukuUtils.run(arrayOf("sh", "-c", "$cmd 2>&1"))

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
