package br.com.redesurftank.havalcomfortcontrol.utils

/**
 * Primitivas do link do **Android Auto sem fio**, num lugar só.
 *
 * Existe porque duas partes precisam das mesmas: o [ProjectionProbe] (testes de campo) e
 * o `ComfortControlService` (o hold dos rádios na tranca). Duplicar o comando do `ndc`
 * nos dois seria o mesmo tipo de erro que o `pidof` — um fato medido no carro escrito em
 * dois lugares, e um deles envelhecendo sozinho.
 *
 * ## O que está medido (log de 09/09/2026, central Haval, Android 9)
 *
 * A central é **cliente STA**, não AP: a [IFACE] pega IP por DHCP em `192.168.33.0/24`,
 * host diferente a cada sessão, e o `dumpsys wifi` mostra `mSoftApTetheredEvents:` e
 * `mSoftApLocalOnlyEvents:` vazios. Quem cria o hotspot é o celular.
 *
 * **Nenhum processo da central sustenta a sessão.** O force-stop dos 8 pacotes de
 * projeção matou os quatro processos vivos, nenhum voltou em 5 s, e a interface seguiu
 * com IP e o vizinho `REACHABLE`. Quem sustenta é o supplicant/framework de Wi-Fi.
 *
 * **Só o `ndc` derruba.** Dos três caminhos testados:
 * ```
 * [ip link set wlan2 down]            ok | ip agora=192.168.33.52
 * [ifconfig wlan2 down]               ok | ip agora=192.168.33.52
 * [ndc interface setcfg wlan2 down]   ok | ip agora=(nenhum) -> CAIU
 * ```
 * Os dois primeiros devolveram **exit 0 sem fazer nada** — o mesmo `true` mentiroso do
 * `BluetoothAdapter.disable()` que fez o Bluetooth não desligar na v1.0.0. Por isso
 * [setUp] confere o estado depois em vez de confiar no exit code.
 *
 * **`pidof <pacote>` não serve** para saber se um pacote de projeção roda: ele casa por
 * *nome de processo*, e `com.ts.androidauto` (o processo do
 * `com.ts.androidauto.projectionservice`, confirmado por
 * `app=ProcessRecord{... 3818:com.ts.androidauto/1000}`) não é pacote instalado. Daí
 * [projectionProcesses] usar `ps` e devolver pid→nome, sem tentar mapear para pacote.
 *
 * Tudo aqui é bloqueante (shell via Shizuku) — chame de uma thread de fundo.
 */
object AawLink {

    // @JvmStatic em tudo: quem mais chama isto e o ComfortControlService, que e Java —
    // sem a anotacao o acesso viraria AawLink.INSTANCE.metodo() do lado de la.

    private const val TAG = "AawLink"

    /** A interface do AAW nesta central. */
    const val IFACE = "wlan2"

    /** IPv4 da interface, ou null se ela está sem endereço (ou não existe). */
    @JvmStatic
    fun ipv4(): String? {
        val out = sh("ip -o -4 addr show $IFACE").stdout
        return Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
    }

    /** True se o link está `state DOWN` — o estado em que [setUp] `false` o deixa. */
    @JvmStatic
    fun isDown(): Boolean = linkLine().contains("state DOWN")

    /** Primeira linha do `ip link show`, cortada para caber num log rotativo. */
    @JvmStatic
    fun linkLine(): String =
        sh("ip link show $IFACE | head -1").stdout.substringAfter(':').trim().take(90)

    /**
     * O vizinho na interface — é o **celular**, e a evidência de que o AP é dele. O
     * `lladdr` vem com MAC localmente administrado, como todo hotspot de telefone
     * moderno.
     */
    @JvmStatic
    fun neighbour(): String =
        sh("ip neigh show dev $IFACE | head -2").stdout.replace('\n', ' ').trim().take(90)

    /** Uma linha com tudo que importa da interface, pronta para o log. */
    @JvmStatic
    fun describe(): String =
        "ip=${ipv4() ?: "(nenhum)"} | link=${linkLine()} | vizinho=${
            neighbour().ifEmpty { "(nenhum)" }
        }"

    /**
     * Levanta ou derruba a interface pelo `ndc`, o único caminho que funciona com uid de
     * shell nesta ROM.
     *
     * Devolve se o estado desejado foi de fato alcançado — não se o comando saiu com 0,
     * que aqui não quer dizer nada.
     */
    @JvmStatic
    fun setUp(up: Boolean): Boolean {
        val res = sh("ndc interface setcfg $IFACE ${if (up) "up" else "down"}")
        val chegou = if (up) !isDown() else isDown()
        PersistentLog.w(TAG, "ndc interface setcfg $IFACE ${if (up) "up" else "down"}"
                + " -> ${res.describeFailure()} | ${describe()}"
                + if (chegou) "" else " | NAO PEGOU")
        return chegou
    }

    /**
     * Igual a [setUp], mas sem escrever nada e sem o [describe] — que custa três shells.
     *
     * Existe para a guarda do hold, que derruba a interface de novo a cada 5 s enquanto o
     * supplicant insiste em reassociar: onze vezes por minuto, medidas em 12/09. Uma
     * linha e quatro shells por reassociação encheriam o log e a fila do Shizuku; quem
     * conta a história é o resumo periódico do serviço.
     */
    @JvmStatic
    fun setUpQuietly(up: Boolean): Boolean {
        sh("ndc interface setcfg $IFACE ${if (up) "up" else "down"}")
        return if (up) !isDown() else isDown()
    }

    /**
     * pid → nome dos processos de projeção vivos. Via `ps`, nunca `pidof` — ver o
     * cabeçalho desta classe.
     */
    @JvmStatic
    fun projectionProcesses(): Map<String, String> {
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

    /** Os processos de projeção numa linha, ou `(nenhum)`. */
    @JvmStatic
    fun projectionProcessesLine(): String = describe(projectionProcesses())

    @JvmStatic
    fun describe(procs: Map<String, String>): String =
        if (procs.isEmpty()) "(nenhum)"
        else procs.entries.joinToString(" ") { "${it.key}/${it.value}" }

    /** Aparelho Bluetooth conectado, se houver — o telefone com o áudio do carro. */
    @JvmStatic
    fun bluetoothConnectedLine(): String {
        val out = sh("dumpsys bluetooth_manager | grep -iE 'mCurrentDevice' | head -2")
            .stdout.replace('\n', ' ').trim().take(110)
        return out.ifEmpty { "(nenhum aparelho)" }
    }

    /** `2>&1` para que "permission denied" entre no log em vez de sumir no stderr. */
    internal fun sh(cmd: String): ShizukuUtils.ShellResult =
        ShizukuUtils.run(arrayOf("sh", "-c", "$cmd 2>&1"))
}
