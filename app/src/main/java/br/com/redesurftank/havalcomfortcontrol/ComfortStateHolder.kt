package br.com.redesurftank.havalcomfortcontrol

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ponte serviço → UI. Objeto único no processo: a Activity só observa, quem escreve
 * é o [br.com.redesurftank.havalcomfortcontrol.services.ComfortControlService].
 *
 * A UI é acessório aqui — nenhuma funcionalidade depende de a Activity estar aberta.
 */
object ComfortStateHolder {

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Serviço conectado ao IIntelligentVehicleControlService. */
    var connected by mutableStateOf(false)
        private set

    /** Valor cru de car.basic.driving_ready_state. */
    var drivingReady by mutableStateOf<String?>(null)
        private set

    /** Valor cru de car.drive.setting.outside_view_mirror_fold_state (0 = rebatidos). */
    var mirrorFold by mutableStateOf<String?>(null)
        private set

    /** Volume atual da multimídia, conforme o serviço do veículo. */
    var mediaVolume by mutableStateOf<String?>(null)
        private set

    var bluetoothOn by mutableStateOf(false)
        private set

    var hotspotOn by mutableStateOf(false)
        private set

    /** Últimas ações do serviço, mais recente primeiro. Teto de 60 linhas. */
    val actionLog = mutableStateListOf<String>()

    fun updateConnected(value: Boolean) {
        connected = value
        if (!value) {
            drivingReady = null
            mirrorFold = null
            mediaVolume = null
        }
    }

    fun setVehicleValue(key: String, value: String?) {
        when (key) {
            CarProps.DRIVING_READY -> drivingReady = value
            CarProps.MIRROR_FOLD   -> mirrorFold = value
            CarProps.MEDIA_VOLUME  -> mediaVolume = value
        }
    }

    fun setRadios(bluetooth: Boolean, hotspot: Boolean) {
        bluetoothOn = bluetooth
        hotspotOn = hotspot
    }

    fun log(message: String) {
        actionLog.add(0, "${stamp.format(Date())}  $message")
        while (actionLog.size > 60) actionLog.removeAt(actionLog.size - 1)
    }
}
