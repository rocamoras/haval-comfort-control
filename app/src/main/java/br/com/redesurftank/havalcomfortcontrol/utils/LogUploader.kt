package br.com.redesurftank.havalcomfortcontrol.utils

import android.content.Context
import android.os.Build
import android.util.Log
import br.com.redesurftank.havalcomfortcontrol.ComfortStateHolder
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sobe o log persistente para o Firebase Storage do projeto `havalenginereverse`
 * (o mesmo bucket dos apps irmãos), em `logs/`.
 *
 * **Versão enxuta de propósito.** O `LogUploader` do haval-climate-control tem ~530
 * linhas porque também coleta logcat, buffer de crash, eventos do ActivityManager e
 * dumpsys — coisas que servem para investigar mortes de processo causadas pela ROM.
 * Aqui o alvo é outro: o [PersistentLog] deste app já registra cada decisão do
 * gatilho e cada comando de rádio com o resultado, que é o que responde às perguntas
 * de campo. Então este arquivo é só cabeçalho + `diag.log` + upload.
 *
 * Por que REST e não o SDK do Firebase: o `google-services.json` daquele projeto
 * registra apenas os applicationIds dele, e o plugin Gradle `google-services` falha o
 * build com "No matching client found" para um pacote não listado. A API REST não
 * precisa de arquivo nenhum — autentica anonimamente pelo Identity Toolkit e sobe por
 * HTTP, sem dependência nova no Gradle.
 *
 * Tudo aqui é bloqueante — chame de uma thread de fundo.
 */
object LogUploader {

    private const val TAG = "LogUploader"

    // Mesmo projeto/bucket do haval-engine-reverse.
    private const val API_KEY = "AIzaSyDZB2Uwb3ZRVteDX-LN0lKrdk2LR8qVRws"
    private const val BUCKET  = "havalenginereverse.firebasestorage.app"

    private const val SIGNUP_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY"

    sealed class Result {
        data class Ok(val url: String, val sizeBytes: Int) : Result()
        data class Err(val message: String) : Result()
    }

    /** Monta o pacote e sobe. `onProgress` é chamado na thread do chamador. */
    fun collectAndUpload(context: Context, onProgress: (String) -> Unit): Result {
        return try {
            onProgress("Coletando…")
            val bytes = collect(context).toByteArray(Charsets.UTF_8)

            onProgress("Autenticando…")
            val token = signInAnonymously()
                ?: return Result.Err("falha na autenticação anônima")

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val name  = "comfort_$stamp.txt"
            onProgress("Enviando $name (${bytes.size / 1024} KB)…")

            val url = upload(name, bytes, token)
                ?: return Result.Err("falha no upload")
            Result.Ok(url, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "upload falhou", e)
            Result.Err(e.message ?: "erro desconhecido")
        }
    }

    /**
     * Cabeçalho + log persistente.
     *
     * O cabeçalho traz só o que ajuda a interpretar o log: versão do app, versão do
     * Android e o estado do veículo no momento do envio. Sem identificadores do
     * device (serial, IMEI, Android ID) — o link do bucket é público para quem o tem,
     * então o conteúdo fica no mínimo necessário para diagnosticar.
     */
    private fun collect(context: Context): String {
        val sb = StringBuilder(4096)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        sb.append("=== haval-comfort-control — diagnóstico ===\n")
        sb.append("enviado em      : ").append(now).append('\n')
        sb.append("versão do app   : ").append(version).append('\n')
        sb.append("Android         : ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("modelo          : ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append('\n')

        val state = ComfortStateHolder
        sb.append("\n--- estado no envio ---\n")
        sb.append("serviço conectado : ").append(state.connected).append('\n')
        sb.append("driving_ready     : ").append(state.drivingReady ?: "—").append('\n')
        sb.append("door_lock_status  : ").append(state.doorLock ?: "—").append('\n')
        sb.append("volume da mídia   : ").append(state.mediaVolume ?: "—").append('\n')
        sb.append("Bluetooth         : ").append(if (state.bluetoothOn) "on" else "off").append('\n')
        sb.append("Wi-Fi             : ").append(if (state.wifiOn) "on" else "off").append('\n')

        sb.append("\n--- log persistente (mais antigo → mais novo) ---\n")
        sb.append(PersistentLog.dump(PersistentLog.DUMP_MAX_CHARS))
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────
    // Firebase por REST
    // ─────────────────────────────────────────────────────────────

    /** idToken de um usuário anônimo, ou null. As regras do bucket exigem auth. */
    private fun signInAnonymously(): String? {
        val conn = (URL(SIGNUP_URL).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            doOutput       = true
            connectTimeout = 15_000
            readTimeout    = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write("{\"returnSecureToken\":true}")
            }
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "signUp HTTP ${conn.responseCode}: ${errorBody(conn)}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("idToken")
                .takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    /** Sobe os bytes e devolve a URL de download, ou null. */
    private fun upload(name: String, bytes: ByteArray, idToken: String): String? {
        val objectPath = URLEncoder.encode("logs/$name", "UTF-8")
        val url = "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o?name=$objectPath"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            doOutput       = true
            connectTimeout = 20_000
            readTimeout    = 60_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Authorization", "Firebase $idToken")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "upload HTTP ${conn.responseCode}: ${errorBody(conn)}")
                return null
            }
            val token = JSONObject(conn.inputStream.bufferedReader().readText())
                .optString("downloadTokens")
            "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o/$objectPath?alt=media" +
                if (token.isNotEmpty()) "&token=$token" else ""
        } finally {
            conn.disconnect()
        }
    }

    private fun errorBody(conn: HttpURLConnection): String =
        try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
}
