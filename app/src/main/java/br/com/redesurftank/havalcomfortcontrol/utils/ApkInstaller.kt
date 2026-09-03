package br.com.redesurftank.havalcomfortcontrol.utils

import java.io.File

/**
 * Instala o APK de atualização usando o Shizuku, sem depender de permissão de UI.
 *
 * Por que não o caminho normal: `REQUEST_INSTALL_PACKAGES` no manifest não basta desde
 * o Android 8 — há um **appop por app** que o usuário precisa habilitar em
 * Settings → "Instalar apps desconhecidos". Nesta central aquela tela não é alcançável
 * (ROMs de carro costumam removê-la), então o fluxo padrão morre num aviso sem saída.
 *
 * O Shizuku já é pré-requisito deste app, e com uid de shell o `pm install` não pede
 * appop nenhum. Fica em uma etapa em vez de uma peregrinação por menus.
 *
 * Tudo aqui é bloqueante — chame de uma thread de fundo.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** Onde o shell consegue ler sem depender das permissões do sdcard. */
    private const val STAGING = "/data/local/tmp/comfort-update.apk"

    /** null = instalado; caso contrário, a mensagem de erro para mostrar na tela. */
    fun install(apk: File): String? {
        if (!apk.isFile || apk.length() == 0L) return "APK baixado está vazio"
        if (!ShizukuUtils.isAvailable()) return "Shizuku indisponível"

        // Copia para /data/local/tmp antes: o diretório externo do app fica sob
        // Android/data/<pkg>, e a leitura dele pelo uid de shell varia com o sdcardfs
        // da ROM. /data/local/tmp é do shell por definição.
        val cp = ShizukuUtils.run(arrayOf("cp", apk.absolutePath, STAGING))
        if (!cp.ok()) {
            PersistentLog.e(TAG, "cp para o staging falhou: " + cp.describeFailure())
            return "falha copiando o APK: ${cp.describeFailure()}"
        }
        ShizukuUtils.run(arrayOf("chmod", "644", STAGING))

        // -r reinstala mantendo os dados; -d aceita downgrade de versionCode, útil
        // para voltar atrás numa release ruim sem desinstalar.
        val install = ShizukuUtils.run(arrayOf("pm", "install", "-r", "-d", STAGING))
        val saida = (install.stdout + " " + install.stderr).trim()
        PersistentLog.w(TAG, "pm install -> " + install.describeFailure() + " | " + saida)

        ShizukuUtils.run(arrayOf("rm", "-f", STAGING))

        // O `pm install` do Android 9 devolve exit 0 mesmo em alguns erros, e escreve
        // "Failure [MOTIVO]" na saída — por isso o texto decide, não só o exit code.
        if (saida.contains("Success", ignoreCase = true)) return null
        if (!install.ok()) return install.describeFailure()
        return if (saida.isEmpty()) "pm install não confirmou sucesso" else saida
    }
}
