@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

data class PlayerTrack(
    val group: Int,
    val index: Int,
    val type: Int,
    val selected: Boolean,
    val channels: Int,
    val sourceId: String,
)
data class OutputRoute(val id: Int, val name: String)
internal const val GENERATED_BACKING_SOURCE = "derived:backing"
class PlaybackOptions(private val context: Context, private val player: ExoPlayer, private val audio: KaraokeAudioProcessor) {
    private val prefs = context.getSharedPreferences("playback-options", Context.MODE_PRIVATE)
    var tracks by mutableStateOf(listOf<PlayerTrack>()); private set
    var routes by mutableStateOf(listOf<OutputRoute>()); private set
    var route by mutableStateOf("系统默认"); private set
    private var channel by mutableStateOf("STEREO")
    var roleOriginal by mutableStateOf(""); private set
    var roleBacking by mutableStateOf(""); private set
    private var songKey = ""
    private val outputRoutes = AudioOutputRoutes.create(context, player) { removed ->
        if (removed) route = "系统默认"
        refreshRoutes()
    }
    val canSelectOutput: Boolean get() = outputRoutes.selectable
    init { refreshRoutes() }
    private var sourceAudio = listOf<String>()
    private var sourceAudioLabels = listOf<String>()
    private var sourceRepresentation = true
    fun configureSource(streams: org.json.JSONArray?, mapping: org.json.JSONArray?, original: Boolean = true, generatedBacking: Boolean = false) {
        sourceRepresentation = original
        val mapped = mapping?.objects().orEmpty().filter { it.optString("kind")=="audio" }.map { it.optString("sourceId") }
        sourceAudio = if(mapped.isNotEmpty()) mapped else streams?.objects().orEmpty().filter { it.optString("kind")=="audio" }.map { it.optString("id") }
        val source=if(mapped.isNotEmpty()) mapping?.objects().orEmpty() else streams?.objects().orEmpty()
        sourceAudioLabels=source.filter { it.optString("kind")=="audio" }.map { it.optString("title") }
        if (generatedBacking) {
            sourceAudio = sourceAudio + GENERATED_BACKING_SOURCE
            sourceAudioLabels = sourceAudioLabels + ""
        }
        refreshTracks()
    }
    fun refreshTracks() {
        var audioOrdinal = 0
        val count=player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO }.sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }
        tracks = player.currentTracks.groups.flatMapIndexed { groupIndex, group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val f=group.getTrackFormat(index)
            val sourceId=if(group.type==C.TRACK_TYPE_AUDIO) {
                val ordinal=audioOrdinal++
                val label=f.label.orEmpty()
                val match=sourceAudioLabels.indexOfFirst { it.isNotBlank() && it==label }
                if(match>=0 && sourceAudioLabels.count { it==label }==1) sourceAudio.getOrElse(match) { "" }
                else if(count==sourceAudio.size) sourceAudio.getOrElse(ordinal) { "" } else ""
            } else ""
            PlayerTrack(groupIndex, index, group.type, group.isTrackSelected(index), f.channelCount, sourceId)
        } }
    }
    fun selectSource(sourceId: String): Boolean {
        val target=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==sourceId } ?: return false
        if(!target.selected)selectTrack(target)
        return true
    }
    internal fun setEmbeddedSubtitlesEnabled(enabled: Boolean) {
        val current = player.trackSelectionParameters
        val updated = current.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build()
        if (updated != current) player.trackSelectionParameters = updated
        if (enabled) selectDefaultSubtitle()
    }
    fun selectDefaultSubtitle() {
        if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) return
        val subtitles = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
        if (subtitles.any { it.selected }) return
        val groups = player.currentTracks.groups
        val default = subtitles.firstOrNull { track ->
            groups[track.group].getTrackFormat(track.index).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        }
        (default ?: subtitles.firstOrNull())?.let(::selectTrack)
    }
    private fun selectTrack(track: PlayerTrack) {
        val group = player.currentTracks.groups.getOrNull(track.group) ?: return
        val current = player.trackSelectionParameters
        val updated = current.buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
            .build()
        if (updated == current) return
        player.trackSelectionParameters = updated
        refreshTracks()
    }
    fun refreshRoutes() { routes = outputRoutes.outputs() }
    fun chooseRoute(id: Int?) { route = outputRoutes.choose(id) }
    fun beginSong(id: String, revision: String) {
        sourceAudio = emptyList()
        sourceAudioLabels = emptyList()
        songKey = "$id:$revision"
        channel = "STEREO"
        audio.channelMode = channel
        roleOriginal = prefs.getString("original:$songKey", "").orEmpty()
        roleBacking = prefs.getString("backing:$songKey", "").orEmpty()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
    }
    private fun currentSelection(): String {
        val selected=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.selected } ?: return ""
        return if(selected.sourceId.isNotBlank()) "${selected.sourceId}|$channel" else "${selected.group}:${selected.index}:$channel"
    }
    fun isRoleSelected(original: Boolean): Boolean {
        val (track, mode) = roleTrack(original) ?: return false
        return track.selected && channel == mode
    }
    private fun roleTrack(original: Boolean): Pair<PlayerTrack, String>? {
        val role=if(original)roleOriginal else roleBacking
        if(role.isBlank())return null
        val track: PlayerTrack
        val mode: String
        if(role=="generated") {
            mode="STEREO"
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==GENERATED_BACKING_SOURCE } ?: return null
        } else if(role.contains('|')) {
            mode=role.substringAfter('|')
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==role.substringBefore('|') } ?: return null
        } else {
            if(!sourceRepresentation)return null
            val saved=role.split(':');if(saved.size!=3)return null
            mode=saved[2]
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.group.toString()==saved[0] && it.index.toString()==saved[1] } ?: return null
        }
        if(mode !in listOf("STEREO","LEFT","RIGHT","MONO") || (mode!="STEREO" && track.channels!=2))return null
        return track to mode
    }
    fun canUseRole(original: Boolean): Boolean = roleTrack(original) != null
    fun applyShared(value: JSONObject) {
        roleOriginal=value.optString("originalId").takeIf { it.isNotBlank() }?.let { "$it|STEREO" } ?: ""
        if(roleOriginal.isBlank() && value.optJSONArray("streams")?.length()==1)roleOriginal="${value.getJSONArray("streams").getJSONObject(0).optString("id")}|STEREO"
        roleBacking=value.optString("backingId").takeIf { it.isNotBlank() }?.let { "$it|STEREO" } ?: ""
        if(value.has("backingResource"))roleBacking="generated"
        if(value.optJSONArray("streams")?.length()==1){
            val localOriginal=prefs.getString("original:$songKey","").orEmpty();val localBacking=prefs.getString("backing:$songKey","").orEmpty()
            if(isChannelRole(localOriginal))roleOriginal=localOriginal
            if(isChannelRole(localBacking))roleBacking=localBacking
        }
    }
    fun isChannelRole(role: String)=listOf("|LEFT","|RIGHT",":LEFT",":RIGHT").any { role.endsWith(it) }
    private fun markRole(original: Boolean){val value=currentSelection();if(value.isBlank())return;if(original)roleOriginal=value else roleBacking=value;prefs.edit().putString("${if(original) "original" else "backing"}:$songKey",value).apply()}
    fun useRole(original: Boolean): Boolean {
        val (track, mode)=roleTrack(original) ?: return false
        if (!track.selected) {
            selectTrack(track)
            // Paused audio cannot drain the samples already queued for the old track.
            if (!player.playWhenReady) player.seekTo(player.currentPosition)
        }
        // roleTrack already validated the target channel count. Media3 may still report the
        // previous selected track until onTracksChanged after the override is applied.
        if(channel!=mode){channel=mode;audio.channelMode=mode}
        val role=if(original)roleOriginal else roleBacking
        if(track.sourceId.isNotBlank() && role!="generated" && !role.contains('|'))markRole(original)
        return true
    }
    fun close() { outputRoutes.close() }
}
