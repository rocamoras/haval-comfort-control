package br.com.redesurftank.havalcomfortcontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.com.redesurftank.havalcomfortcontrol.services.ComfortControlService
import br.com.redesurftank.havalcomfortcontrol.ui.theme.HavalComfortControlTheme
import br.com.redesurftank.havalcomfortcontrol.utils.PersistentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-comfort-control/releases/latest"

// ── Paleta HMI: escuro monocromático, acento só no que está ativo ──
private val HmiBg        = Color(0xFF000000)
private val HmiSurface   = Color(0xFF141414)
private val HmiSurface2  = Color(0xFF1C1C1C)
private val HmiFg        = Color(0xFFFAFAFA)
private val HmiFgMuted   = Color(0xFFA3A3A3)
private val HmiFgDim     = Color(0xFF6B6B6B)
private val HmiAccent    = Color(0xFF22C55E)
private val HmiAccentEdge = Color(0x6622C55E)
private val HmiBorder    = Color(0x12FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A UI é acessório: se o usuário abriu o app é porque o serviço deveria estar
        // de pé, então garantimos isso aqui também (e não só no boot).
        startForegroundService(Intent(this, ComfortControlService::class.java))
        setContent {
            HavalComfortControlTheme { ComfortScreen() }
        }
    }
}

@Composable
private fun ComfortScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = ComfortStateHolder
    val prefs   = remember { Prefs.get(context) }

    var closeWindows by remember {
        mutableStateOf(prefs.getBoolean(
            Prefs.CLOSE_WINDOWS_ON_FOLD_MIRROR, Prefs.DEF_CLOSE_WINDOWS_ON_FOLD_MIRROR))
    }
    var disableBluetooth by remember {
        mutableStateOf(prefs.getBoolean(
            Prefs.DISABLE_BLUETOOTH_ON_POWER_OFF, Prefs.DEF_DISABLE_BLUETOOTH))
    }
    var disableHotspot by remember {
        mutableStateOf(prefs.getBoolean(
            Prefs.DISABLE_HOTSPOT_ON_POWER_OFF, Prefs.DEF_DISABLE_HOTSPOT))
    }
    var keepDistractionOff by remember {
        mutableStateOf(prefs.getBoolean(
            Prefs.KEEP_DISTRACTION_DISABLED, Prefs.DEF_KEEP_DISTRACTION_DISABLED))
    }
    var setStartupVolume by remember {
        mutableStateOf(prefs.getBoolean(Prefs.SET_STARTUP_VOLUME, Prefs.DEF_SET_STARTUP_VOLUME))
    }
    var startupVolume by remember {
        mutableIntStateOf(prefs.getInt(Prefs.STARTUP_VOLUME, Prefs.DEF_STARTUP_VOLUME))
    }

    // ── Atualização via GitHub Releases ──
    var currentVersion   by remember { mutableStateOf("--") }
    var isChecking       by remember { mutableStateOf(false) }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var dialogMessage    by remember { mutableStateOf("") }
    var showMsgDialog    by remember { mutableStateOf(false) }
    var showPermDialog   by remember { mutableStateOf(false) }
    var downloadJob      by remember { mutableStateOf<Job?>(null) }

    // Diagnóstico: o log persistente é a única evidência que sobrevive a um restart do
    // serviço, e sem uma forma de lê-lo na central o diagnóstico de campo é às cegas.
    var showLogDialog by remember { mutableStateOf(false) }
    var logText       by remember { mutableStateOf("") }
    var savedPath     by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    // Avisa o serviço quando vale espelhar estado na tela. Fora daqui ele não empurra
    // nada — é o que impede o trabalho por segundo com o app em background.
    DisposableEffect(Unit) {
        ComfortStateHolder.uiVisible = true
        onDispose { ComfortStateHolder.uiVisible = false }
    }

    fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermDialog = true
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun startDownload() {
        isDownloading = true
        downloadProgress = 0f
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val conn  = URL(downloadUrl).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val buf   = ByteArray(8192)
                var bytes = 0
                var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(conn.inputStream).use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            bytes += read
                            if (total > 0) downloadProgress = bytes.toFloat() / total
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    updateAvailable = false
                    installApk(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "download falhou", e)
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    dialogMessage = "Erro no download: ${e.message}"
                    showMsgDialog = true
                }
            }
        }
    }

    fun checkForUpdates() {
        isChecking = true
        scope.launch(Dispatchers.IO) {
            try {
                val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                val json   = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag    = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                var dlUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        dlUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                prefs.edit()
                    .putLong(Prefs.LAST_UPDATE_CHECK_MS, System.currentTimeMillis()).apply()
                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (dlUrl != null && compareVersions(tag, currentVersion) > 0) {
                        latestVersion = tag
                        downloadUrl = dlUrl
                        updateAvailable = true
                    } else {
                        dialogMessage = "Você já está na versão mais recente ($currentVersion)"
                        showMsgDialog = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "verificação de update falhou", e)
                withContext(Dispatchers.Main) {
                    isChecking = false
                    dialogMessage = "Erro ao verificar atualizações: ${e.message}"
                    showMsgDialog = true
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(HmiBg).padding(20.dp)
    ) {
        // ── Cabeçalho ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Comfort Control", fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, color = HmiFg)
                Text("v$currentVersion", fontSize = 15.sp, color = HmiFgDim)
            }
            StatusChip("Serviço", state.connected)
            Spacer(Modifier.width(10.dp))
            StatusChip("Carro ligado", state.drivingReady?.let { it != "-1" && it != "0" } == true)
            Spacer(Modifier.width(10.dp))
            StatusChip("Bluetooth", state.bluetoothOn)
            Spacer(Modifier.width(10.dp))
            StatusChip("Âncora", state.hotspotOn)
            Spacer(Modifier.width(20.dp))
            Button(
                onClick = {
                    showLogDialog = true
                    savedPath = ""
                    logText = "carregando…"
                    scope.launch(Dispatchers.IO) {
                        val dump = PersistentLog.dump(PersistentLog.DUMP_MAX_CHARS)
                        withContext(Dispatchers.Main) {
                            logText = dump.ifBlank { "(log vazio)" }
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Log", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { checkForUpdates() },
                enabled = !isChecking && !isDownloading,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp), color = HmiFg, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Verificando…", fontSize = 16.sp)
                } else {
                    Text("Atualizar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── Cartões das funcionalidades ──
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureCard(
                modifier = Modifier.weight(1f),
                title = "Vidros ao rebater",
                description = "Fecha todos os vidros quando os retrovisores são "
                        + "rebatidos. Ignorado se o carro estiver em movimento ou "
                        + "fora de P.",
                checked = closeWindows,
                onCheckedChange = {
                    closeWindows = it
                    prefs.edit().putBoolean(Prefs.CLOSE_WINDOWS_ON_FOLD_MIRROR, it).apply()
                },
                footer = "retrovisores: " + when (state.mirrorFold) {
                    null -> "—"
                    "0"  -> "rebatidos"
                    else -> "abertos"
                }
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                title = "Rádios ao desligar",
                description = "Desliga Bluetooth e âncora de Wi-Fi quando o carro é "
                        + "desligado, e religa o que estava ligado assim que a "
                        + "central volta.",
                checked = disableBluetooth,
                onCheckedChange = {
                    disableBluetooth = it
                    prefs.edit().putBoolean(Prefs.DISABLE_BLUETOOTH_ON_POWER_OFF, it).apply()
                },
                checkedLabel = "Bluetooth",
                secondChecked = disableHotspot,
                onSecondCheckedChange = {
                    disableHotspot = it
                    prefs.edit().putBoolean(Prefs.DISABLE_HOTSPOT_ON_POWER_OFF, it).apply()
                },
                secondCheckedLabel = "Âncora de Wi-Fi"
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                title = "Aviso de distrações",
                description = "Mantém o aviso de distrações desligado — se a central "
                        + "religar sozinha, o app desliga de novo.",
                checked = keepDistractionOff,
                onCheckedChange = {
                    keepDistractionOff = it
                    prefs.edit().putBoolean(Prefs.KEEP_DISTRACTION_DISABLED, it).apply()
                },
                checkedLabel = "Manter desligado"
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                title = "Volume inicial",
                description = "Define o volume da multimídia a cada partida. "
                        + "Aplicado uma vez por ciclo de ignição.",
                checked = setStartupVolume,
                onCheckedChange = {
                    setStartupVolume = it
                    prefs.edit().putBoolean(Prefs.SET_STARTUP_VOLUME, it).apply()
                },
                footer = "volume atual no carro: ${state.mediaVolume ?: "—"}"
            ) {
                Text("Volume: $startupVolume", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (setStartupVolume) HmiAccent else HmiFgDim)
                Slider(
                    value = startupVolume.toFloat(),
                    // Só o estado em memória durante o arraste. Gravar a pref a cada
                    // evento enfileirava uma escrita em disco por pixel arrastado —
                    // centenas por segundo nesta central, e a UI travava junto.
                    onValueChange = { value -> startupVolume = value.toInt() },
                    onValueChangeFinished = {
                        prefs.edit().putInt(Prefs.STARTUP_VOLUME, startupVolume).apply()
                    },
                    valueRange = Prefs.VOLUME_MIN.toFloat()..Prefs.VOLUME_MAX.toFloat(),
                    steps = Prefs.VOLUME_MAX - Prefs.VOLUME_MIN - 1,
                    enabled = setStartupVolume,
                    colors = SliderDefaults.colors(
                        thumbColor = HmiAccent,
                        activeTrackColor = HmiAccent,
                        inactiveTrackColor = HmiSurface2
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Últimas ações do serviço ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(HmiSurface, RoundedCornerShape(12.dp))
                .border(1.dp, HmiBorder, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Text("Últimas ações", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = HmiFgMuted)
            Spacer(Modifier.height(6.dp))
            if (state.actionLog.isEmpty()) {
                Text(
                    if (state.connected) "Nenhuma ação ainda nesta sessão."
                    else "Aguardando o Shizuku — o servidor é subido pelo Climate Control.",
                    fontSize = 14.sp, color = HmiFgDim
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.actionLog) { line ->
                        Text(line, fontSize = 14.sp, color = HmiFgMuted,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    // ── Diálogos ──
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Log de diagnóstico") },
            text = {
                Column {
                    if (savedPath.isNotEmpty()) {
                        Text("Salvo em: $savedPath", fontSize = 13.sp, color = HmiAccent)
                        Spacer(Modifier.height(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            logText,
                            fontSize = 12.sp,
                            color = HmiFgMuted,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Cópia no diretório externo do app: é de onde dá para puxar por
                    // gerenciador de arquivos ou adb pull, sem precisar de root.
                    scope.launch(Dispatchers.IO) {
                        val out = File(context.getExternalFilesDir(null), "diag.log")
                        val ok = runCatching {
                            out.writeText(PersistentLog.dump(PersistentLog.DUMP_MAX_CHARS))
                        }.isSuccess
                        withContext(Dispatchers.Main) {
                            savedPath = if (ok) out.absolutePath else "falha ao salvar"
                        }
                    }
                }) { Text("Salvar em arquivo") }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Fechar") }
            }
        )
    }

    if (showMsgDialog) {
        AlertDialog(
            onDismissRequest = { showMsgDialog = false },
            title = { Text("Atualização") },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = { showMsgDialog = false }) { Text("OK") }
            }
        )
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Permissão necessária") },
            text = {
                Text("Autorize a instalação de apps desta fonte para aplicar a "
                        + "atualização, depois toque em Atualizar novamente.")
            },
            confirmButton = {
                TextButton(onClick = { showPermDialog = false }) { Text("OK") }
            }
        )
    }

    if (updateAvailable) {
        AlertDialog(
            onDismissRequest = { if (!isDownloading) updateAvailable = false },
            title = { Text("Nova versão disponível") },
            text = {
                Column {
                    Text("Disponível: $latestVersion\nAtual: $currentVersion")
                    if (isDownloading) {
                        Spacer(Modifier.height(12.dp))
                        Text("Baixando… ${(downloadProgress * 100).toInt()}%")
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { startDownload() }, enabled = !isDownloading) {
                    Text("Baixar e instalar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    updateAvailable = false
                    downloadJob?.cancel()
                    isDownloading = false
                }) { Text("Cancelar") }
            }
        )
    }
}

/**
 * Cartão de uma funcionalidade. O segundo switch existe para "Rádios ao desligar",
 * onde Bluetooth e âncora são a mesma funcionalidade com dois interruptores.
 */
@Composable
private fun FeatureCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedLabel: String = "Ativado",
    secondChecked: Boolean? = null,
    onSecondCheckedChange: ((Boolean) -> Unit)? = null,
    secondCheckedLabel: String = "",
    footer: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val active = checked || (secondChecked == true)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(HmiSurface, RoundedCornerShape(14.dp))
            .border(1.dp, if (active) HmiAccentEdge else HmiBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = HmiFg)
        Spacer(Modifier.height(8.dp))
        Text(description, fontSize = 14.sp, color = HmiFgMuted)

        Spacer(Modifier.weight(1f))

        if (extra != null) {
            extra()
            Spacer(Modifier.height(8.dp))
        }

        ToggleRow(checkedLabel, checked, onCheckedChange)
        if (secondChecked != null && onSecondCheckedChange != null) {
            Spacer(Modifier.height(4.dp))
            ToggleRow(secondCheckedLabel, secondChecked, onSecondCheckedChange)
        }

        if (footer != null) {
            Spacer(Modifier.height(10.dp))
            Text(footer, fontSize = 13.sp, color = HmiFgDim)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, color = if (checked) HmiFg else HmiFgMuted,
            modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HmiFg,
                checkedTrackColor = HmiAccent,
                uncheckedThumbColor = HmiFgDim,
                uncheckedTrackColor = HmiSurface2
            )
        )
    }
}

@Composable
private fun StatusChip(label: String, on: Boolean) {
    Row(
        modifier = Modifier
            .background(HmiSurface, RoundedCornerShape(20.dp))
            .border(1.dp, if (on) HmiAccentEdge else HmiBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(if (on) HmiAccent else HmiFgDim, CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 14.sp, color = if (on) HmiFg else HmiFgDim)
    }
}

/** Compara "v1.2.3" com "1.2.10" ignorando o prefixo v. >0 se v1 for mais novo. */
private fun compareVersions(v1: String, v2: String): Int {
    val p1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val p2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until minOf(p1.size, p2.size)) {
        if (p1[i] > p2[i]) return 1
        if (p1[i] < p2[i]) return -1
    }
    return p1.size.compareTo(p2.size)
}
