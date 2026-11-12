@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer

/** API 21/22 leave routing to the TV; API 23 types stay inside the guarded implementation. */
internal interface AudioOutputRoutes {
    val selectable: Boolean
    fun outputs(): List<OutputRoute>
    fun choose(id: Int?): String
    fun close()

    companion object {
        fun create(context: Context, player: ExoPlayer, changed: (Boolean) -> Unit): AudioOutputRoutes =
            if (Build.VERSION.SDK_INT >= 23) DeviceAudioOutputRoutes(context, player, changed) else SystemAudioOutputRoutes
    }
}

private object SystemAudioOutputRoutes : AudioOutputRoutes {
    override val selectable = false
    override fun outputs() = emptyList<OutputRoute>()
    override fun choose(id: Int?) = "系统默认"
    override fun close() = Unit
}

@RequiresApi(23)
private class DeviceAudioOutputRoutes(context: Context, private val player: ExoPlayer, changed: (Boolean) -> Unit) : AudioOutputRoutes {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { changed(false) }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            player.setPreferredAudioDevice(null)
            changed(true)
        }
    }
    init { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
    override val selectable = true
    override fun outputs() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
        val kind = when (it.type) {
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            else -> "输出 ${it.id}"
        }
        val channels = it.channelCounts.maxOrNull()?.takeIf { count -> count > 0 }?.let { count -> "$count 声道" } ?: "声道能力未报告"
        OutputRoute(it.id, "$kind · ${it.productName} · $channels")
    }
    override fun choose(id: Int?): String {
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        player.setPreferredAudioDevice(device)
        return device?.productName?.toString() ?: "系统默认"
    }
    override fun close() { manager.unregisterAudioDeviceCallback(callback) }
}
