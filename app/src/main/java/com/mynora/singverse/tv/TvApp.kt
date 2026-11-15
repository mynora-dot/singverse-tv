@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.net.URLEncoder
import java.util.UUID

class TvApp(context: android.content.Context, pairingLifetimeMillis: Long = 300_000) {
    val client = TvClient(context)
    val discovery = NasDiscovery(context)
    private val updater = TvUpdater(context, client)
    private val updatePreferences = context.getSharedPreferences("client-updates", android.content.Context.MODE_PRIVATE)
    var updateStatus by mutableStateOf("连接 NAS 后自动检查更新")
        private set
    internal var updateRelease by mutableStateOf<TvRelease?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set
    var updateDownloading by mutableStateOf(false)
        private set
    var updateDownloaded by mutableLongStateOf(0)
        private set
    var updatePanelRequested by mutableIntStateOf(0)
        private set
    var updateFile by mutableStateOf<java.io.File?>(null)
        private set
    private var updatePromptPending by mutableStateOf(false)
    private var updateJob: Job? = null
    private var updateGeneration = 0L
    private var updateFileConnection = -1L
    private var awaitingInstallPermission = false
    internal val updateAvailable: Boolean get() = updateRelease?.let { it.versionCode > updater.installedVersion && updater.compatible(it) } == true
    internal val showUpdatePrompt: Boolean get() = updatePromptPending && connected && foreground && !deferUpdatePrompt(playerState) && !updateDownloading

    fun checkUpdate(manual: Boolean = true, connection: Boolean = false) {
        if (!connected || updateDownloading || updateJob?.isActive == true) return
        val address = client.base
        val preferenceKey = updatePreferenceKey(address)
        val now = System.currentTimeMillis()
        if (!manual && !connection && !updateCheckDue(now, updatePreferences.getLong("checked:$preferenceKey", 0))) return
        val connectionGeneration = client.connectionGeneration
        val generation = ++updateGeneration
        updateChecking = true
        updateStatus = "正在检查 NAS 镜像中的 TV 安装包…"
        updatePreferences.edit().putLong("checked:$preferenceKey", now).apply()
        updateJob = scope.launch {
            try {
                val release = updater.release(json(TV_RELEASE_PATH))
                if (generation != updateGeneration || connectionGeneration != client.connectionGeneration) return@launch
                if (release != updateRelease) { updateFile?.delete(); updateFile = null; awaitingInstallPermission = false }
                updateRelease = release
                updatePromptPending = false
                updateStatus = when {
                    release == null -> "当前 NAS 镜像尚未提供 TV 安装包"
                    release.versionCode <= updater.installedVersion -> "当前已是最新版本（${BuildConfig.VERSION_NAME}）"
                    !updater.compatible(release) -> "新版 ${release.versionName} 不支持当前电视系统或架构"
                    else -> "发现新版本 ${release.versionName} · ${release.size / 1048576} MB"
                }
                if (release != null && updater.compatible(release)) {
                    updatePromptPending = shouldOfferUpdate(release.versionCode, updater.installedVersion,
                        if (updatePreferences.getStringSet("skipped:$preferenceKey", emptySet()).orEmpty().contains(release.versionCode.toString())) release.versionCode else -1, manual)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == updateGeneration && connectionGeneration == client.connectionGeneration) updateStatus = "检查更新失败，可稍后重试：${e.message ?: "连接失败"}"
            } finally {
                if (generation == updateGeneration) { updateChecking = false; updateJob = null }
            }
        }
    }
    fun dismissUpdate() { updatePromptPending = false }
    fun skipUpdate() {
        updateRelease?.let { release ->
            val key = "skipped:${updatePreferenceKey(client.base)}"
            val skipped = updatePreferences.getStringSet(key, emptySet()).orEmpty() + release.versionCode.toString()
            updatePreferences.edit().putStringSet(key, skipped).apply()
        }
        updatePromptPending = false
        updateStatus = "已跳过此版本；仍可在这里手动更新"
    }
    fun downloadUpdate() {
        val release = updateRelease ?: return
        if (!updateAvailable || updateDownloading) return
        updateJob?.cancel()
        val generation = ++updateGeneration
        val connectionGeneration = client.connectionGeneration
        updatePromptPending = false; updateChecking = false; updateDownloading = true; updateDownloaded = 0
        updateFile?.delete(); updateFile = null; awaitingInstallPermission = false
        page = "设置"; updatePanelRequested++
        updateStatus = "正在下载 ${release.versionName}…"
        updateJob = scope.launch {
            try {
                val file = updater.download(release, connectionGeneration) { downloaded ->
                    scope.launch { if (generation == updateGeneration && connectionGeneration == client.connectionGeneration) updateDownloaded = downloaded }
                }
                if (generation != updateGeneration || connectionGeneration != client.connectionGeneration) { file.delete(); return@launch }
                updateFile = file; updateFileConnection = connectionGeneration
                updateStatus = "安装包已验证，选择「确认安装」继续"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == updateGeneration && connectionGeneration == client.connectionGeneration) updateStatus = "下载失败，可重试：${e.message ?: "连接失败"}"
            } finally {
                if (generation == updateGeneration) { updateDownloading = false; updateJob = null }
            }
        }
    }
    fun cancelUpdateDownload() {
        updateGeneration++; updateJob?.cancel(); updateJob = null
        updateDownloading = false; updateChecking = false; updateDownloaded = 0
        updateStatus = "已取消下载，可重新下载"
    }
    fun installUpdate() {
        if (updateJob?.isActive == true) return
        val file = updateFile ?: return
        val release = updateRelease ?: return
        val connectionGeneration = client.connectionGeneration
        if (updateFileConnection != connectionGeneration) {
            resetUpdates(); updateStatus = "NAS 连接已更换，请重新检查更新"; return
        }
        val generation = ++updateGeneration
        updateStatus = "正在重新核对安装包…"
        updateJob = scope.launch {
            try {
                val installed = updater.install(file, release, connectionGeneration)
                if (generation != updateGeneration || connectionGeneration != client.connectionGeneration) return@launch
                awaitingInstallPermission = !installed
                updateStatus = if (awaitingInstallPermission) "请在系统页面允许安装，返回后将继续安装" else "请在 Android 系统页面确认安装"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == updateGeneration && connectionGeneration == client.connectionGeneration) {
                    if (!file.isFile) updateFile = null
                    updateStatus = "无法打开安装程序：${e.message ?: "请重试"}"
                }
            } finally {
                if (generation == updateGeneration) updateJob = null
            }
        }
    }
    fun resumeInstallation() = resumeUpdateInstallation()
    private fun resumeUpdateInstallation() {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        if (updateFileConnection != client.connectionGeneration) return
        if (updater.canInstall()) installUpdate()
        else updateStatus = "尚未允许安装；可选择「确认安装」重新授权"
    }
    private fun resetUpdates() {
        updateGeneration++; updateJob?.cancel(); updateJob = null
        updateFile?.delete(); updateFile = null; updateRelease = null
        updateChecking = false; updateDownloading = false; updateDownloaded = 0
        updatePromptPending = false; awaitingInstallPermission = false; updateFileConnection = -1
        updateStatus = "连接 NAS 后自动检查更新"
    }
    private var transcodeId = ""
    private var transcodeConnection: Pair<String, String>? = null
    private var timelineOffset = 0L
    private var sourceDuration = 0L
    private var resourceGeneration = 0
    private var currentSongGeneration = 0
    var currentSong by mutableStateOf<JSONObject?>(null)
        private set
    var playbackMedia by mutableStateOf(JSONObject())
        private set
    private var observedVideo by mutableStateOf(false)
    private var observedAudio by mutableStateOf(false)
    private var selectedAudioFormat by mutableStateOf<Format?>(null)
    var embeddedCues by mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList())
        private set
    internal val playerMode: TvPlayerMode get() = tvPlayerMode(playbackMedia.optJSONArray("streams")?.objects().orEmpty().map {
        TvPlayerStream(it.optString("kind"), attachedPicture = it.optBoolean("attached_pic") || it.optJSONObject("disposition")?.optBoolean("attached_pic") == true)
    }, observedVideo, observedAudio)
    internal val hasConfirmedPlayback: Boolean get() {
        val playback = snapshot.optJSONObject("playback") ?: return false
        return playback.optBoolean("confirmed") && loadedAttempt.isNotBlank() && playback.optString("attemptId") == loadedAttempt
    }
    internal val audioQualityLabels: List<String> get() {
        val playback = snapshot.optJSONObject("playback")
        if (!hasConfirmedPlayback || currentSong?.optString("id") != playback?.optString("songId")) return emptyList()
        val format = selectedAudioFormat
        val stream = format?.let { TvPlayerStream("audio", it.sampleMimeType.orEmpty(), it.channelCount, it.sampleRate,
            it.averageBitrate.takeIf { value -> value > 0 }?.toLong() ?: 0) }
        return tvAudioQualityLabels(playbackMedia.optString("container"), stream)
    }
    val activeAudioRole: String get() = when {
        playbackOptions.isRoleSelected(false) -> "backing"
        playbackOptions.isRoleSelected(true) -> "original"
        else -> ""
    }
    private val sharedFavorites = TvSharedFavorites(
        connection = { "${client.connectionGeneration}:${client.base}" },
        read = { page ->
            val result = json("/api/v1/tv/favorites?page=$page&size=100")
            FavoritePage(result.getJSONArray("items").objects(), result.getInt("totalElements"), result.getLong("revision"))
        },
        write = { ids, selected ->
            json("/api/v1/tv/favorites", "PUT", JSONObject().put("songIds", JSONArray(ids)).put("selected", selected))
                .getJSONArray("songIds").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
        },
        id = { song: JSONObject -> song.getString("id") },
    )
    val favoritesLoading: Boolean get() = sharedFavorites.loading
    val favoriteSaving: Boolean get() = sharedFavorites.saving
    val favoritesReady: Boolean get() = sharedFavorites.ready
    val favoritesError: String get() = sharedFavorites.error
    fun favoriteSongs(): List<JSONObject> = sharedFavorites.songs
    fun isFavorite(songId: String): Boolean = songId in sharedFavorites.ids
    fun refreshFavorites() { if (connected) scope.launch { sharedFavorites.load() } }
    fun setFavorite(songId: String, selected: Boolean) {
        if (!connected) return
        scope.launch { if (!sharedFavorites.set(songId, selected)) error = sharedFavorites.error }
    }
    fun toggleFavorite() {
        val songId = currentSong?.optString("id").orEmpty()
        val connectionGeneration = client.connectionGeneration
        val selected = !isFavorite(songId)
        if (songId.isNotBlank() && songId == snapshot.optJSONObject("playback")?.optString("songId")) {
            scope.launch {
                if (!sharedFavorites.ready) sharedFavorites.load()
                if (connectionGeneration != client.connectionGeneration) return@launch
                if (!sharedFavorites.set(songId, selected)) error = sharedFavorites.error
            }
        }
    }
    var playbackQuality by mutableStateOf("auto")
    private val playbackPreferences = context.getSharedPreferences("playback-options", android.content.Context.MODE_PRIVATE)
    var compatiblePlayback by mutableStateOf(playbackPreferences.getBoolean("compatible-playback", android.os.Build.VERSION.SDK_INT < 23))
        private set
    private val recovery = PlaybackRecovery()
    private var resourceJob: Job? = null
    private var pendingLoadCommand = ""
    private var failedLoadCommand = ""
    private val remoteResults = RemoteCommandResults()
    private var remoteCommandId = ""
    private var remoteCommandJob: Job? = null
    private val reactionCursor = PartyReactionCursor()
    internal var partyReactions by mutableStateOf(emptyList<PartyReaction>())
        private set
    var partyEffectsEnabled by mutableStateOf(playbackPreferences.getBoolean("party-effects", true))
        private set
    fun setPartyEffects(enabled: Boolean) {
        partyEffectsEnabled = enabled
        playbackPreferences.edit().putBoolean("party-effects", enabled).apply()
        if (!enabled) partyReactions = emptyList()
    }
    fun removeReaction(cursor: Long) { partyReactions = partyReactions.filterNot { it.cursor == cursor } }
    private fun receiveReactions(value: JSONObject) {
        val attempt = value.optJSONObject("playback")?.optString("attemptId").orEmpty()
        val events = value.optJSONArray("reactions")?.objects().orEmpty().map {
            PartyReaction(it.optLong("cursor"), it.optString("kind"), it.optString("ownerName"), it.optString("attemptId"), it.optLong("expiresAt"))
        }
        val added = reactionCursor.accept(value.optString("id"), attempt, value.optLong("reactionCursor"), events, System.currentTimeMillis(), partyEffectsEnabled && foreground)
        partyReactions = (partyReactions.filter { it.attemptId == attempt && it.expiresAt > System.currentTimeMillis() } + added).takeLast(12)
    }
    private var lastLoadCommand: JSONObject? = null
    private var lastLoadSessionId = ""
    private var resumeAfterPreparation = true
    private var resourceChanging = false
    private var resourceFailed = false
    var online by mutableStateOf(false)
    var lastSync by mutableLongStateOf(0)
    private var failureCount = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var page by mutableStateOf("首页")
    var connected by mutableStateOf(false)
    var error by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var activeRequests = 0
    var snapshot by mutableStateOf(JSONObject())
    var homeSongs by mutableStateOf(listOf<JSONObject>())
    var homeAppearance by mutableStateOf(JSONObject())
    var homePlaylists by mutableStateOf(listOf<JSONObject>())
    var homeTotal by mutableLongStateOf(0)
    var serverName by mutableStateOf("SingVerse")
    var artistQuery by mutableStateOf("")
    var artistPage by mutableIntStateOf(0)
    var artistTotal by mutableLongStateOf(0)
    private var artistGeneration = 0
    private var detailGeneration = 0
    private var lastLibraryRefresh = 0L
    var songs by mutableStateOf(listOf<JSONObject>())
    internal var songListResetVersion by mutableIntStateOf(0)
        private set
    internal var songListFocusFirst by mutableStateOf(false)
        private set
    internal var queueAddSongId by mutableStateOf("")
        private set
    internal var queueAddMessage by mutableStateOf("")
        private set
    private var queueAddGeneration = 0
    private var queueAddMessageJob: Job? = null
    var tags by mutableStateOf(listOf<JSONObject>())
    var participants by mutableStateOf(listOf<JSONObject>())
    var participantsKnown by mutableStateOf(false)
        private set
    val participantCount: Int get() = participants.count { !it.optBoolean("revoked") }
    private var participantsGeneration = 0
    private var lastParticipantsRefresh = 0L
    var selected by mutableStateOf<JSONObject?>(null)
    private var partyInvitation by mutableStateOf(PartyInvitation())
    val invitation: String get() = partyInvitation.url
    val invitationLoading: Boolean get() = partyInvitation.loading
    val invitationError: String get() = partyInvitation.error
    var address by mutableStateOf(client.base)
    var query by mutableStateOf("")
    internal val login: TvLogin = TvLogin(context, discovery, pairingLifetimeMillis) { base, credential ->
        stopLocal(); loadedAttempt = ""; resetUpdates(); sharedFavorites.reset(); client.save(base, credential); sharedFavorites.reset(); address = base
        try { establishConnection() } catch (failure: Exception) {
            if (!connected && client.token == credential) client.clear()
            throw failure
        }
    }.also { if (client.base.isNotBlank()) it.setAddress(client.base) }
    var selectedTags by mutableStateOf(setOf<String>())
    var unclassified by mutableStateOf(false)
    internal var categoryResults by mutableStateOf(false)
        private set
    var number by mutableIntStateOf(0)
    var total by mutableLongStateOf(0)
    var artists by mutableStateOf(listOf<JSONObject>())
    var artistDetail by mutableStateOf<JSONObject?>(null)
    var lastArtistId by mutableStateOf("")
    private var artistDetailGeneration=0
    fun openArtist(artist: JSONObject) {
        val generation=++artistDetailGeneration;val id=artist.getString("id");lastArtistId=id
        run { val detail=json("/api/v1/tv/library/artists/$id");if(generation!=artistDetailGeneration)return@run;artistDetail=detail;selected=null;artistId=id;number=0;songs=emptyList();total=0;loadSongs(resetList = true) }
    }
    fun closeArtist() {artistDetailGeneration++;detailGeneration++;requestGeneration++;artistDetail=null;artistId="";selected=null}
    var playlists by mutableStateOf(listOf<JSONObject>())
    var playlistDetail by mutableStateOf<JSONObject?>(null)
    var playlistPage by mutableIntStateOf(0)
    var playlistTotal by mutableLongStateOf(0)
    var playlistQuery by mutableStateOf("")
    var lastPlaylistId by mutableStateOf("")
    private var playlistGeneration=0
    suspend fun fetchPlaylists(){val generation=++playlistGeneration;val result=json("/api/v1/tv/library/playlists?q=${URLEncoder.encode(playlistQuery,"UTF-8")}&page=$playlistPage&size=20");if(generation==playlistGeneration){playlists=result.getJSONArray("items").objects();playlistTotal=result.getLong("totalElements")}}
    fun loadPlaylists()=run {fetchPlaylists()}
    fun openPlaylist(playlist:JSONObject){val generation=++playlistGeneration;val id=playlist.getString("id");lastPlaylistId=id;run {val detail=json("/api/v1/tv/library/playlists/$id");if(generation!=playlistGeneration)return@run;playlistDetail=detail;artistId="";selected=null;number=0;songs=emptyList();total=0;loadSongs(resetList = true)}}
    fun closePlaylist(){playlistGeneration++;detailGeneration++;requestGeneration++;playlistDetail=null;selected=null}
    var artistId by mutableStateOf("")
    var lastSongId by mutableStateOf("")
    var lastSongPage by mutableStateOf("")
    private val commandsLock = Mutex()
    private val audioRoleLock = Mutex()
    private var audioRoleGeneration = 0
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var playerState by mutableStateOf("IDLE")
    var controlVisible by mutableStateOf(false)
    var pendingConfirm by mutableStateOf<String?>(null)
    var pendingOperation by mutableStateOf<JSONObject?>(null)
    private var loadedAttempt = ""
    private var reportingAttempt = ""
    private var playbackLifecycleGeneration = 0
    var foreground by mutableStateOf(true)
        private set
    private var seq = 0L
    private val playbackClock = ActualPlaybackClock(SystemClock::elapsedRealtime)
    private var lastPlaybackReportAt = 0L
    private var expiredReportAttempt = ""
    private var requestGeneration = 0
    private val dataSource = OkHttpDataSource.Factory(TvMediaTransport.client { client.credentials() })
    private val channelProcessor = KaraokeAudioProcessor()
    private val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).setAudioProcessors(arrayOf(channelProcessor)).build()
    }.apply { setEnableDecoderFallback(true) }
    val player = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
    val playbackOptions = PlaybackOptions(context, player, channelProcessor)
    init {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { embeddedCues = cueGroup.cues }
            override fun onTracksChanged(tracks: Tracks) {
                observedVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                observedAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                selectedAudioFormat = tracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } else null
                }
                playbackOptions.refreshTracks()
                playbackOptions.setAudioOnly(playerMode == TvPlayerMode.AUDIO)
                if(selectedSourceId.isNotBlank() && playbackOptions.selectSource(selectedSourceId))selectedSourceId=""
            }
            override fun onPlaybackStateChanged(state: Int) {
                playerState = if (resourceChanging) "PREPARING" else if(player.playerError!=null) "ERROR" else when (state) { Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_ENDED -> "ENDED"; Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"; else -> "IDLE" }
                if (state == Player.STATE_ENDED) controlVisible = true
                report()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (player.playbackState == Player.STATE_READY) playerState = if (isPlaying) "PLAYING" else "PAUSED"; report() }
            override fun onPlayerError(exception: PlaybackException) {
                if (resourceChanging) return
                if (connected && loadedAttempt.isNotBlank() && recovery.begin(exception.errorCode)) {
                    val failedPosition = player.currentPosition.coerceAtLeast(0) + timelineOffset
                    if (requestedAudioRole.isBlank()) requestedAudioRole = activeAudioRole
                    scope.launch {
                        try { rebuildPlayback(startMs = failedPosition) }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            playbackFailed("兼容播放准备失败：${e.message}")
                        }
                    }
                } else playbackFailed("此素材播放失败，可重试或跳过。${exception.errorCodeName}")
            }
        })
        if (client.token.isNotEmpty()) run { connect() }
        scope.launch { while (isActive) { delay(30000); if (connected && foreground && !sharedFavorites.loading) sharedFavorites.load() } }
        scope.launch { while (isActive) { delay((1000L * (1 shl failureCount.coerceAtMost(4))).coerceAtMost(16000)); if (connected) refresh() } }
        scope.launch { while (isActive) { delay(60000); if (connected && online && foreground) checkUpdate(manual = false) } }
        scope.launch { while (isActive) { delay(10000); val id=transcodeId;if(connected && id.isNotBlank()) runCatching { json("/api/v1/tv/transcoding/$id") } } }
        scope.launch { while (isActive) {
            delay(200)
            position = player.currentPosition.coerceAtLeast(0) + timelineOffset
            duration = originalPlaybackDurationMs(sourceDuration, player.duration, timelineOffset, player.isCurrentMediaItemDynamic, player.isCurrentMediaItemLive) ?: 0
            playbackOptions.tick(position)
            playbackClock.sample(actuallyPlaying())
            val interval = if (actuallyPlaying()) 5000 else 15000
            if (connected && reportingAttempt.isNotEmpty() && SystemClock.elapsedRealtime() - lastPlaybackReportAt >= interval) report()
        } }
    }
    private var selectedSourceId = ""
    private var requestedAudioRole=""
    private var backingResource=""
    private fun playbackFailed(message: String) {
        playerState = "ERROR"; controlVisible = true; error = message; report()
    }
    private fun cancelExecution(id: String, connection: Pair<String, String> = client.credentials()) {
        if (id.isBlank()) return
        // Cleanup is bounded and independent of the next resource request or the closing UI scope.
        CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(3000) { runCatching { client.request("/api/v1/tv/transcoding/$id/cancel", "POST", connection = connection) } }
        }
    }
    private fun releaseExecution() {
        val id = transcodeId
        val connection = transcodeConnection ?: client.credentials()
        transcodeId = ""; transcodeConnection = null
        cancelExecution(id, connection)
    }
    private fun mediaItem(resource: JSONObject): MediaItem {
        val builder=MediaItem.Builder().setUri(client.base+resource.getString("url"))
        if (resource.optString("mode") == "HLS") builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if(resource.has("subtitleUrl")) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(client.base+resource.getString("subtitleUrl"))).setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        return builder.build()
    }
    private suspend fun loadResource(song: String, startMs: Long = 0, force: Boolean = false): JSONObject {
        val job = currentCoroutineContext()[Job]
        if (resourceJob !== job) resourceJob?.cancel()
        resourceJob = job
        val turn = ++resourceGeneration
        val connection = client.credentials()
        releaseExecution()
        val body = JSONObject().put("songId",song).put("client",PlaybackCapabilities.report(compatiblePlayback || force)).put("quality",playbackQuality)
            .put("start",startMs/1000.0).put("force",force).put("audioRole",requestedAudioRole)
        var resource = json("/api/v1/tv/playback-resources", "POST", body)
        val initialAudio=resource.optJSONObject("audioRoles")
        val initialBackingResource = resource.optString("backingUrl")
        val executionId = resource.optString("id")
        val revision = resource.optString("revision")
        if (turn != resourceGeneration) {
            cancelExecution(executionId, connection)
            throw CancellationException("播放请求已替换")
        }
        transcodeId = executionId
        transcodeConnection = connection
        val deadline = android.os.SystemClock.elapsedRealtime() + 60000
        try {
            while (resource.optString("status") != "READY") {
                require(resource.optString("state") !in listOf("FAILED","CANCELLED")) { resource.optString("error","转换失败") }
                require(executionId.isNotBlank()) { "播放资源缺少执行标识" }
                require(android.os.SystemClock.elapsedRealtime() < deadline) { "转换准备超时，请重试或生成兼容版本" }
                delay(750)
                if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
                resource = json("/api/v1/tv/transcoding/$executionId")
                if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
            }
            if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
        } catch (e: Exception) {
            if (turn == resourceGeneration && transcodeId == executionId) releaseExecution()
            throw e
        }
        backingResource = initialBackingResource
        timelineOffset = (resource.optDouble("offset",0.0)*1000).toLong()
        sourceDuration = (((resource.optJSONObject("execution")?.optJSONObject("media") ?: resource.optJSONObject("media"))?.optDouble("duration",0.0) ?: 0.0)*1000).toLong()
        resource.put("revision",revision)
        if(initialAudio!=null)resource.put("audioRoles",initialAudio)
        playbackMedia = resource.optJSONObject("execution")?.optJSONObject("media") ?: resource.optJSONObject("media") ?: JSONObject()
        playbackOptions.setAudioOnly(playerMode == TvPlayerMode.AUDIO)
        return resource
    }
    private fun setAudioResource(resource: JSONObject) {
        selectedSourceId=if(backingResource.isNotBlank() && requestedAudioRole=="backing") GENERATED_BACKING_SOURCE
            else if(resource.optString("mode")=="DIRECT")resource.optJSONObject("audioRoles")?.optString(if(requestedAudioRole=="original")"originalId" else if(requestedAudioRole=="backing")"backingId" else "").orEmpty() else ""
        val factory=DefaultMediaSourceFactory(dataSource)
        if(backingResource.isNotBlank()){
            // Keep both audio sources in the same timeline after the first accompaniment load.
            // Subsequent original/backing changes select a track without rebuilding the MV.
            val original=factory.createMediaSource(mediaItem(resource))
            val backing=factory.createMediaSource(MediaItem.Builder().setUri(client.base+backingResource).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(timelineOffset).build()).build())
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(true,original,backing))
        }else player.setMediaItem(mediaItem(resource))
        resource.optJSONObject("audioRoles")?.let { playbackOptions.applyShared(it) }
    }
    fun saveAudioRole(original: Boolean)=run {
        val song=snapshot.optJSONObject("playback")?.optString("songId") ?: return@run
        val tracks=playbackOptions.tracks.filter { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
        val selected=tracks.firstOrNull { it.selected } ?: error("尚未识别音轨")
        if(tracks.size==1 && selected.channels==2 && playbackOptions.channel in listOf("LEFT","RIGHT")){playbackOptions.markRole(original);return@run}
        val other=tracks.firstOrNull { it.sourceId.isNotBlank() && it.sourceId!=selected.sourceId } ?: error("需要两条已识别源音轨")
        require(selected.sourceId.isNotBlank()) { "请先完成媒体探测" }
        val path="/api/v1/tv/audio-roles/songs/$song"
        val current=json(path)
        val saved=json(path,"PUT",JSONObject().put("revision",current.getLong("revision")).put("originalId",if(original)selected.sourceId else other.sourceId).put("backingId",if(original)other.sourceId else selected.sourceId))
        playbackOptions.applyShared(saved)
    }
    fun useAudioRole(original: Boolean) { run { applyAudioRole(original) } }
    private suspend fun applyAudioRole(original: Boolean) = audioRoleLock.withLock {
        ++audioRoleGeneration
        require(canUseAudioRole(original)) { "此歌曲没有可用的${if (original) "原唱" else "伴奏"}" }
        requestedAudioRole = if (original) "original" else "backing"
        if (!playbackOptions.isRoleSelected(original) && !playbackOptions.useRole(original)) {
            val role = if (original) playbackOptions.roleOriginal else playbackOptions.roleBacking
            require(!playbackOptions.isChannelRole(role)) { "此版本的本机声道标记已失效" }
            rebuildPlayback(followPosition = true)
        }
    }
    private suspend fun awaitPrepared(sessionId: String, attempt: String) {
        val ready = withTimeoutOrNull(15000) {
            while (true) {
                check(snapshot.optString("id") == sessionId && snapshot.optJSONObject("playback")?.optString("attemptId") == attempt) { "播放实例已更换" }
                player.playerError?.let { throw it }
                if (!resourceChanging && loadedAttempt == attempt && player.playbackState == Player.STATE_READY) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        check(ready == true) { "电视准备超时" }
    }
    private suspend fun seekRemote(targetMs: Long) {
        check(hasConfirmedPlayback) { "歌曲尚未准备完成" }
        val target = targetMs.coerceAtLeast(0)
        val local = if (transcodeId.isBlank()) target else localPlaybackPosition(target, timelineOffset, player.duration, player.isCurrentMediaItemSeekable)
        if (local != null) player.seekTo(local) else rebuildPlayback(startMs = target)
    }
    private suspend fun rebuildPlayback(force: Boolean = recovery.forceCompatibility, startMs: Long = position, followPosition: Boolean = false) {
        if (!hasConfirmedPlayback || snapshot.optString("status") != "ACTIVE") return
        val sessionId = snapshot.optString("id")
        val attempt = loadedAttempt
        val song = snapshot.optJSONObject("playback")?.optString("songId")?.takeIf { it.isNotBlank() } ?: return
        val previousOffset = timelineOffset
        resourceChanging = true; resourceFailed = false; playerState = "PREPARING"; error = ""
        try {
            val resource = loadResource(song,startMs,force)
            if (snapshot.optString("id") != sessionId || !hasConfirmedPlayback || loadedAttempt != attempt || snapshot.optString("status") != "ACTIVE") {
                releaseExecution()
                throw CancellationException("播放实例已更换")
            }
            val target = if (followPosition) player.currentPosition.coerceAtLeast(0) + previousOffset else startMs
            val resume = resumeAfterPreparation && foreground
            setAudioResource(resource)
            playbackOptions.configureSource(resource.optJSONObject("execution")?.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("audioRoles")?.optJSONArray("streams"),resource.optJSONObject("execution")?.optJSONArray("trackMapping"), resource.optString("mode")=="DIRECT", backingResource.isNotBlank())
            player.seekToOriginalPosition(target, timelineOffset)
            resourceChanging = false
            player.prepare(); player.playWhenReady = resume
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            resourceChanging = false; resourceFailed = true
            playbackFailed(e.message ?: "播放准备失败，请重试")
            throw e
        }
    }
    fun canUseAudioRole(original: Boolean): Boolean {
        if (!hasConfirmedPlayback || playerState == "IDLE") return false
        val role = if (original) playbackOptions.roleOriginal else playbackOptions.roleBacking
        return role.isNotBlank() && (online || playbackOptions.canUseRole(original))
    }
    fun changeQuality(quality: String) {
        require(quality in listOf("auto", "1080p", "720p", "480p"))
        if (playbackQuality == quality) return
        playbackQuality = quality
        reloadCurrentPlayback()
    }
    fun changeCompatibility(enabled: Boolean) {
        if (compatiblePlayback == enabled) return
        compatiblePlayback = enabled
        playbackPreferences.edit().putBoolean("compatible-playback", enabled).apply()
        if (enabled && playbackQuality == "1080p") playbackQuality = "auto"
        reloadCurrentPlayback()
    }
    private fun reloadCurrentPlayback() {
        if (hasConfirmedPlayback) { run { rebuildPlayback() }; return }
        val playback = snapshot.optJSONObject("playback") ?: return
        val command = lastLoadCommand?.takeIf { lastLoadSessionId == snapshot.optString("id") && it.optJSONObject("playback")?.optString("attemptId") == playback.optString("attemptId") } ?: return
        if (!playback.optBoolean("confirmed")) return
        val resume = resumeAfterPreparation
        run { commandsLock.withLock { prepareCommand(command, playback, snapshot.getString("id"), resume) } }
    }
    fun seekAbsolute(positionMs: Long) {
        if (!hasConfirmedPlayback) return
        val target = positionMs.coerceAtLeast(0)
        if (transcodeId.isBlank()) { player.seekTo(target); return }
        val local = if (resourceChanging) null else localPlaybackPosition(target, timelineOffset, player.duration, player.isCurrentMediaItemSeekable)
        if (local != null) player.seekTo(local) else run { rebuildPlayback(startMs = target) }
    }
    fun run(task: suspend () -> Unit) { scope.launch { activeRequests++; busy = true; try { task() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "连接失败，请重试" } finally { activeRequests--; busy = activeRequests > 0 } } }
    private fun acceptSnapshot(value: JSONObject, replaceSession: Boolean = false) {
        if(!replaceSession && snapshot.optString("id").isNotBlank() && value.optString("id")!=snapshot.optString("id"))return
        if(value.optString("id")==snapshot.optString("id") && value.has("stateRevision") && snapshot.has("stateRevision") && value.getLong("stateRevision")<snapshot.getLong("stateRevision"))return
        if (value.optString("id") != snapshot.optString("id")) {
            pendingOperation = null; error = ""; remoteResults.clear(); remoteCommandJob?.cancel(); remoteCommandId = ""
            reactionCursor.reset(); partyReactions = emptyList()
        }
        val previousAttempt = snapshot.optJSONObject("playback")?.optString("attemptId").orEmpty()
        if (previousAttempt.isNotBlank() && previousAttempt != value.optJSONObject("playback")?.optString("attemptId")) {
            remoteCommandJob?.cancel(); remoteCommandId = ""
            resourceGeneration++; resourceJob?.cancel(); pendingLoadCommand = ""
        }
        if (value.optString("id") != snapshot.optString("id") || value.optString("status") != "ACTIVE") clearPartyConnection()
        receiveReactions(value)
        snapshot=value
    }
    private suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val server=client.base;val credential=client.token
        val result=JSONObject(client.request(path,method,body))
        if(client.base!=server || client.token!=credential)throw CancellationException("连接已更换")
        return result
    }
    fun connect() = run {
        val previous = client.credentials()
        try { establishConnection() }
        catch (failure: TvHttpException) {
            if (failure.status == 401 && !connected && client.credentials() == previous) {
                client.clear()
                error = ""
                login.open(LoginPage.METHODS)
            } else throw failure
        }
    }
    private suspend fun establishConnection() {
        val info = json("/api/v1/server-info")
        require(info.optString("service") == "singverse" && info.optInt("apiVersion") == 1) { "不是兼容的 SingVerse 服务" }
        acceptSnapshot(json("/api/v1/tv/sessions", "POST"), replaceSession = true)
        if (!connected) {
            acceptSnapshot(json("/api/v1/tv/sessions/${snapshot.getString("id")}/recover", "POST"))
            page = "首页"
        }
        declarePartyCapabilities()
        // Finish the required handshake before removing the login screen and its lifecycle.
        loadHome()
        login.recordSuccess(client.base, serverName)
        connected = true; online = true; failureCount = 0; error = ""
        refreshFavorites()
        checkUpdate(manual = false, connection = true)
        ensureInvitation()
        run { loadSongs(); loadTags() }
    }
    private suspend fun declarePartyCapabilities() {
        val sid = snapshot.getString("id")
        acceptSnapshot(json("/api/v1/tv/sessions/$sid/capabilities", "POST", JSONObject().put("capabilities", JSONArray(listOf("AUTO_ADVANCE", "SEEK", "AUDIO_ROLE", "APPLY_AUDIO_ROLES", "REACTIONS")))))
    }
    suspend fun refresh() { try { val sid = snapshot.optString("id"); if (sid.isEmpty()) return; acceptSnapshot(json("/api/v1/tv/sessions/$sid/snapshot")); if(error.startsWith("与 NAS 同步失败"))error=""; val restored = !online; online = true; if (restored && foreground) checkUpdate(manual = false, connection = true); failureCount = 0; lastSync = android.os.SystemClock.elapsedRealtime(); if (foreground) { executeCommands(); ensureInvitation(); if (!participantsKnown || lastSync - lastParticipantsRefresh >= 5000) refreshParticipants(sid) }
        if (foreground && android.os.SystemClock.elapsedRealtime() - lastLibraryRefresh > 30000) {
            if (page == "首页") loadHome()
            if (page == "分类") loadTags()
            if (page == "歌单") {
                val id=playlistDetail?.optString("id")
                if(id==null)fetchPlaylists() else {val generation=playlistGeneration;try{val detail=json("/api/v1/tv/library/playlists/$id");if(generation==playlistGeneration&&playlistDetail?.optString("id")==id){playlistDetail=detail;if(selected==null)loadSongs()}}catch(e:TvHttpException){if(e.status==404&&generation==playlistGeneration){closePlaylist();fetchPlaylists()}else throw e}}
            }
            if (page == "歌手") {
                val id=artistDetail?.optString("id")
                if(id==null)fetchArtists() else {
                    val generation=artistDetailGeneration
                    try {
                        val detail=json("/api/v1/tv/library/artists/$id")
                        if(generation==artistDetailGeneration&&artistDetail?.optString("id")==id){artistDetail=detail;if(selected==null)loadSongs()}
                    }catch(e:TvHttpException){if(e.status==404&&generation==artistDetailGeneration){closeArtist();fetchArtists()}else throw e}
                }
            }
            lastLibraryRefresh = android.os.SystemClock.elapsedRealtime()
        } } catch (e: Exception) { if (e is CancellationException) throw e; online = false; reactionCursor.reset(); partyReactions = emptyList(); participantsKnown = false; failureCount++; if (e is TvHttpException && e.status == 401) { pauseLocal(); resetUpdates(); client.clear(); sharedFavorites.reset(); connected = false; clearPartyConnection(); login.open(LoginPage.METHODS); error = "" } else error = "与 NAS 同步失败；本地仍可暂停或停止。${e.message}" } }
    private suspend fun refreshParticipants(sid: String) {
        val generation = ++participantsGeneration
        val server = client.base; val credential = client.token
        val result = JSONArray(client.request("/api/v1/tv/sessions/$sid/participants")).objects()
        if (generation == participantsGeneration && server == client.base && credential == client.token && sid == snapshot.optString("id")) {
            participants = result
            participantsKnown = true
            lastParticipantsRefresh = android.os.SystemClock.elapsedRealtime()
        }
    }
    suspend fun loadHome() {
        val server = client.base; val credential = client.token
        val result = json("/api/v1/tv/home")
        if (client.base != server || client.token != credential) return
        homeSongs = result.getJSONArray("recentSongs").objects()
        homeAppearance = result.optJSONObject("appearance") ?: JSONObject()
        homePlaylists = result.optJSONArray("featuredPlaylists")?.objects().orEmpty()
        homeTotal = result.getLong("totalSongs")
        serverName = result.getJSONObject("server").optString("name", "SingVerse")
    }
    suspend fun loadTags() {
        val server = client.base; val credential = client.token
        val result = JSONArray(client.request("/api/v1/tv/library/classification-tags")).objects()
        if (client.base == server && client.token == credential) tags = result
    }
    fun navigate(destination: String) {
        if(playlistDetail!=null)closePlaylist()
        playlistGeneration++
        if(artistDetail!=null)closeArtist()
        artistDetailGeneration++
        detailGeneration++
        selected = null
        categoryResults = false
        page = destination
        run {
            when (destination) {
                "首页" -> loadHome()
                "点歌" -> Unit
                "找歌" -> loadSongs()
                "歌手" -> fetchArtists()
                "分类" -> loadTags()
                "歌单" -> fetchPlaylists()
                "收藏" -> sharedFavorites.load()
                "已点", "设置" -> { if (destination == "设置") lastParticipantsRefresh = 0; refresh() }
            }
        }
    }
    fun openSong(song: JSONObject) {
        val generation = ++detailGeneration
        val songId = song.getString("id")
        val sourcePage = page
        lastSongId = songId; lastSongPage = sourcePage
        run {
            val detail = json("/api/v1/tv/library/songs/$songId")
            if (generation == detailGeneration && page == sourcePage) selected = detail
        }
    }
    private suspend fun fetchArtists() {
        val generation = ++artistGeneration
        val result = json("/api/v1/tv/library/artists?q=${URLEncoder.encode(artistQuery, "UTF-8")}&page=$artistPage&size=20")
        if (generation == artistGeneration) { artists = result.getJSONArray("items").objects(); artistTotal = result.getLong("totalElements") }
    }
    fun loadArtists() = run { fetchArtists() }
    suspend fun loadSongs(resetList: Boolean = false, focusFirst: Boolean = false) {
        val generation = ++requestGeneration
        val filter = selectedTags.joinToString("") { "&tagIds=${URLEncoder.encode(it, "UTF-8")}" }
        val path = if (playlistDetail!=null) "/api/v1/tv/library/playlists/${playlistDetail!!.getString("id")}/songs?page=$number&size=20" else if (artistId.isNotEmpty()) "/api/v1/tv/library/artists/$artistId/songs?page=$number&size=20" else "/api/v1/tv/library/songs?q=${URLEncoder.encode(query, "UTF-8")}&page=$number&size=20$filter&unclassified=$unclassified"
        val result = json(path)
        if (generation == requestGeneration) {
            songs = result.getJSONArray("items").objects()
            total = result.getLong("totalElements")
            if (resetList) { songListFocusFirst = focusFirst; songListResetVersion++ }
        }
    }
    fun search(reset: Boolean = true, focusFirst: Boolean = false) = run {
        if (reset) number = 0
        detailGeneration++; selected = null
        loadSongs(resetList = true, focusFirst = focusFirst)
    }
    fun openCategory(tagId: String? = null) {
        query = ""
        artistId = ""
        selectedTags = tagId?.let { setOf(it) }.orEmpty()
        unclassified = tagId == null
        categoryResults = true
        page = "找歌"
        songs = emptyList()
        total = 0
        search(focusFirst = true)
    }
    internal fun addSongFromList(song: JSONObject) {
        // Set this before launching so a second confirm cannot enqueue another request.
        if (queueAddSongId.isNotBlank()) return
        val server = client.base; val credential = client.token; val sessionId = snapshot.optString("id")
        val generation = ++queueAddGeneration
        fun current() = generation == queueAddGeneration && client.base == server && client.token == credential &&
            snapshot.optString("id") == sessionId && connected
        queueAddSongId = song.getString("id")
        queueAddMessageJob?.cancel(); queueAddMessage = ""
        run {
            try {
                sendCommand("ADD", songId = song.getString("id"))
                if (current()) {
                    showQueueAddMessage("已加入队列：${song.optString("title")}", generation)
                }
            } catch (e: Exception) {
                if (e is CancellationException || current()) throw e
            } finally {
                if (generation == queueAddGeneration) queueAddSongId = ""
            }
        }
    }
    private fun showQueueAddMessage(message: String, generation: Int) {
        queueAddMessageJob?.cancel()
        queueAddMessage = message
        queueAddMessageJob = scope.launch {
            delay(2500)
            if (generation == queueAddGeneration) queueAddMessage = ""
        }
    }
    fun command(action: String, itemId: String? = null, songId: String? = null, participantId: String? = null) = run {
        sendCommand(action, itemId, songId, participantId)
    }
    private suspend fun sendCommand(action: String, itemId: String? = null, songId: String? = null, participantId: String? = null) {
        check(online) { "NAS 离线，先重连核对状态；本机暂停/停止仍可使用" }
        check(pendingOperation == null) { "上次操作结果未知，请先核对或重试原请求" }
        val sessionId = snapshot.getString("id")
        val body = JSONObject().put("action", action).put("requestId", UUID.randomUUID().toString()).put("expectedRevision", snapshot.optLong("revision"))
            .put("attemptId", snapshot.optJSONObject("playback")?.optString("attemptId").orEmpty())
        if (itemId != null) body.put("itemId", itemId)
        if (songId != null) body.put("songId", songId)
        if (participantId != null) body.put("participantId", participantId)
        pendingOperation = body
        try {
            val response = json("/api/v1/tv/sessions/$sessionId/commands", "POST", body)
            if (snapshot.optString("id") != sessionId) return
            check(response.optString("id") == sessionId) { "聚会响应不匹配，请核对状态" }
            acceptSnapshot(response)
            if (pendingOperation === body) pendingOperation = null
            error = ""
            val handoff = response.optJSONObject("playback")?.takeIf {
                action == "NEXT" && response.optString("id") == sessionId && !it.optBoolean("confirmed")
            }?.optString("attemptId").orEmpty()
            executeCommands()
            // A deliberate local Next press also authorizes starting that exact next item.
            // Remote/recovered/unknown-result handoffs continue to require an explicit START.
            val playback = snapshot.optJSONObject("playback")
            if (handoff.isNotBlank() && snapshot.optString("id") == sessionId &&
                snapshot.optString("status") == "ACTIVE" && playback?.optString("attemptId") == handoff &&
                !playback.optBoolean("confirmed") && snapshot.optJSONArray("queue")?.objects().orEmpty().any {
                    it.optString("id") == playback.optString("itemId") && it.optString("status") == "HANDOFF"
                }) sendCommand("START")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (snapshot.optString("id") != sessionId) return
            if (e is TvHttpException && e.status in 400..499 && pendingOperation === body) pendingOperation = null
            refresh()
            throw e
        }
    }
    fun discardPending() { pendingOperation = null }
    fun reconnect() = run { refresh() }
    fun retryPlayback() {
        if (hasConfirmedPlayback) {
            resumeAfterPreparation = true; error = ""
            if (resourceFailed) run { rebuildPlayback() } else { player.prepare(); player.play() }
            return
        }
        run {
            val playback = snapshot.optJSONObject("playback") ?: error("请先确认开唱")
            check(playback.optBoolean("confirmed")) { "请先确认开唱" }
            val pending = snapshot.optJSONArray("commands")?.objects().orEmpty().any { it.optString("action") in listOf("LOAD", "RESTART") }
            val remembered = lastLoadCommand?.takeIf { lastLoadSessionId == snapshot.optString("id") && it.optJSONObject("playback")?.optString("attemptId") == playback.optString("attemptId") }
            check(pending || remembered != null) { "当前没有可重试的播放请求" }
            failedLoadCommand = ""
            if (remembered != null && resourceFailed) commandsLock.withLock {
                // This explicit retry authorizes one new acquisition after a
                // known failure, even if its failure acknowledgement is in flight.
                remoteResults.forget(remembered.getString("id"))
                prepareCommand(remembered, playback, snapshot.getString("id"))
            } else if (pending) executeCommands() else commandsLock.withLock {
                if (pendingLoadCommand.isBlank()) prepareCommand(remembered!!, playback, snapshot.getString("id"))
            }
        }
    }
    fun reloadText() = run { val songId = snapshot.optJSONObject("playback")?.optString("songId") ?: return@run; loadText(songId) }
    private suspend fun loadText(songId: String) { playbackOptions.beginTextRead(songId); try { playbackOptions.applyText(songId, json("/api/v1/tv/library/songs/$songId/text-tracks")) } catch (e: Exception) { if (e is CancellationException) throw e; playbackOptions.textFailed(songId) } }
    fun retryPending() = run {
        val body = pendingOperation ?: return@run
        val sessionId = snapshot.getString("id")
        val generation = queueAddGeneration
        try {
            val response = json("/api/v1/tv/sessions/$sessionId/commands", "POST", body)
            if (snapshot.optString("id") != sessionId) return@run
            check(response.optString("id") == sessionId) { "聚会响应不匹配，请核对状态" }
            acceptSnapshot(response)
            if (pendingOperation === body) pendingOperation = null
            error = ""
            executeCommands()
            if (body.optString("action") == "ADD" && snapshot.optString("id") == sessionId && generation == queueAddGeneration) {
                val title = response.optJSONArray("queue")?.objects()?.lastOrNull {
                    it.optString("songId") == body.optString("songId")
                }?.optJSONObject("song")?.optString("title")
                showQueueAddMessage(title?.let { "已加入队列：$it" } ?: "点歌请求已确认", generation)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (snapshot.optString("id") == sessionId) {
                if (e is TvHttpException && e.status in 400..499 && pendingOperation === body) pendingOperation = null
                throw e
            }
        }
    }
    private fun prepareCommand(command: JSONObject, playback: JSONObject, sessionId: String, resume: Boolean = foreground) {
        if (snapshot.optString("id") != sessionId || snapshot.optString("status") != "ACTIVE" ||
            snapshot.optJSONObject("playback")?.optString("attemptId") != playback.optString("attemptId") ||
            !playback.optBoolean("confirmed")) return
        remoteCommandJob?.cancel(); remoteCommandId = ""
        resourceGeneration++; resourceJob?.cancel(); audioRoleGeneration++
        releaseExecution()
        playbackClock.sample(false)
        // Stop callbacks belong to the previous media, including retries of this same attempt.
        reportingAttempt = ""
        loadedAttempt = ""; player.stop(); player.playWhenReady = false
        val generation = ++currentSongGeneration
        val songId = playback.getString("songId")
        val attempt = playback.getString("attemptId")
        val commandId = command.getString("id")
        lastLoadCommand = JSONObject(command.toString()); lastLoadSessionId = sessionId
        pendingLoadCommand = commandId
        failedLoadCommand = ""
        resumeAfterPreparation = resume && foreground
        recovery.reset()
        currentSong = snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optJSONObject("song")?.optString("id") == songId }?.optJSONObject("song")
        playbackMedia = JSONObject(); observedVideo = false; observedAudio = false; selectedAudioFormat = null; embeddedCues = emptyList()
        requestedAudioRole = playback.optString("audioRole"); backingResource = ""; timelineOffset = 0; sourceDuration = 0
        resourceChanging = true; resourceFailed = false
        playerState = "PREPARING"; error = ""; page = "演唱"; controlVisible = true
        reportingAttempt = attempt
        if (playbackClock.start("${client.connectionGeneration}:$sessionId:$attempt")) { seq = 0; expiredReportAttempt = "" }
        // A confirmed LOAD owns a reporting lease even while its media request is still pending.
        report()
        resourceJob = scope.launch {
            try {
                val resource = loadResource(songId)
                if (snapshot.optString("id") != sessionId || snapshot.optJSONObject("playback")?.optString("attemptId") != attempt)
                    throw CancellationException("播放实例已更换")
                loadedAttempt = attempt
                setAudioResource(resource)
                playbackOptions.beginSong(songId, resource.getString("revision"))
                playbackOptions.setAudioOnly(playerMode == TvPlayerMode.AUDIO)
                playbackOptions.configureSource(resource.optJSONObject("execution")?.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("audioRoles")?.optJSONArray("streams"), resource.optJSONObject("execution")?.optJSONArray("trackMapping"), resource.optString("mode") == "DIRECT", backingResource.isNotBlank())
                resource.optJSONObject("audioRoles")?.let { playbackOptions.applyShared(it) }
                resourceChanging = false
                player.prepare(); player.playWhenReady = resumeAfterPreparation && foreground; controlVisible = !player.playWhenReady
                awaitPrepared(sessionId, attempt)
                scope.launch { loadText(songId) }
                scope.launch {
                    runCatching { json("/api/v1/tv/library/songs/$songId") }.getOrNull()?.let {
                        if (generation == currentSongGeneration && snapshot.optJSONObject("playback")?.optString("songId") == songId) currentSong = it
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedLoadCommand = commandId
                resourceChanging = false; resourceFailed = true
                remoteResults.record(commandId, false)
                if (generation == currentSongGeneration && pendingLoadCommand == commandId) pendingLoadCommand = ""
                playbackFailed(e.message ?: "播放准备失败，请重试")
                runCatching { acknowledgeCommand(sessionId, commandId, false) }
                return@launch
            } finally {
                if (generation == currentSongGeneration && pendingLoadCommand == commandId) pendingLoadCommand = ""
            }
            // A failed acknowledgement is retried by the next snapshot without preparing media again.
            remoteResults.record(commandId, true)
            runCatching { acknowledgeCommand(sessionId, commandId, true) }
        }
    }
    private suspend fun acknowledgeCommand(sessionId: String, commandId: String, success: Boolean) {
        acceptSnapshot(json("/api/v1/tv/sessions/$sessionId/playback-events", "POST", JSONObject().put("commandId", commandId).put("commandStatus", if (success) "EXECUTED" else "FAILED")))
    }
    private suspend fun executeCommands() = commandsLock.withLock {
        val sessionId = snapshot.getString("id")
        for (c in snapshot.optJSONArray("commands")?.objects().orEmpty()) {
            if (snapshot.optString("id") != sessionId) return@withLock
            val commandId = c.getString("id")
            val knownResult = remoteResults.result(commandId)
            if (knownResult != null) { acknowledgeCommand(sessionId, commandId, knownResult); continue }
            val action = c.getString("action")
            val p = c.optJSONObject("playback")
            val attempt = c.optString("attemptId", p?.optString("attemptId").orEmpty())
            val bound = snapshot.optString("queuePolicy") == "FAIR" || action in listOf("LOAD", "RESTART")
            if (bound && attempt.isNotBlank() && attempt != snapshot.optJSONObject("playback")?.optString("attemptId")) {
                remoteResults.record(commandId, false); acknowledgeCommand(sessionId, commandId, false); continue
            }
            // Transport intent applies while LOAD/seek/role resources are still
            // preparing. Keep LOAD's own result pending until actual readiness.
            if (action in listOf("PAUSE", "RESUME", "STOP")) {
                val success = try {
                    when (action) {
                        "PAUSE" -> { resumeAfterPreparation = false; player.pause(); if (reportingAttempt.isNotBlank()) playerState = "PAUSED" }
                        "RESUME" -> { check(foreground) { "电视当前不在前台" }; resumeAfterPreparation = true; if (loadedAttempt.isNotBlank()) player.play() else if (reportingAttempt.isNotBlank()) playerState = "PREPARING" }
                        "STOP" -> { remoteCommandJob?.cancel(); remoteCommandId = ""; stopLocal(); loadedAttempt = ""; playerState = "IDLE" }
                    }
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "遥控执行失败"
                    false
                }
                remoteResults.record(commandId, success)
                acknowledgeCommand(sessionId, commandId, success)
                report()
                if (action == "STOP") return@withLock
                continue
            }
            if (pendingLoadCommand.isNotBlank() || remoteCommandId.isNotBlank()) continue
            if (action == "LOAD" || action == "RESTART") {
                if (p != null && loadedAttempt != p.optString("attemptId")) {
                    if (failedLoadCommand != commandId) prepareCommand(c, p, sessionId)
                    return@withLock
                }
                remoteResults.record(commandId, true); acknowledgeCommand(sessionId, commandId, true)
                continue
            }
            // Resource acquisition may take a minute. Keep the 1s snapshot/presence loop free.
            remoteCommandId = commandId
            remoteCommandJob = scope.launch {
                var success = false
                try {
                    check(snapshot.optString("id") == sessionId && snapshot.optJSONObject("playback")?.optString("attemptId") == attempt) { "播放实例已更换" }
                    when (action) {
                        "SEEK" -> { seekRemote(c.getLong("positionMs")); awaitPrepared(sessionId, attempt) }
                        "AUDIO_ROLE" -> {
                            applyAudioRole(c.getString("role") == "original")
                            awaitPrepared(sessionId, attempt)
                            check(withTimeoutOrNull(3000) { while (!playbackOptions.isRoleSelected(c.getString("role") == "original")) delay(50); true } == true) { "音轨切换未生效" }
                        }
                        "APPLY_AUDIO_ROLES" -> audioRoleLock.withLock {
                            check(hasConfirmedPlayback) { "歌曲尚未准备完成" }
                            val song = snapshot.getJSONObject("playback").getString("songId")
                            check(song == c.getString("songId")) { "歌曲已更换" }
                            val shared = json("/api/v1/tv/audio-roles/songs/$song")
                            check(snapshot.optJSONObject("playback")?.optString("attemptId") == attempt) { "播放实例已更换" }
                            playbackOptions.applyShared(shared)
                            rebuildPlayback(followPosition = true)
                            awaitPrepared(sessionId, attempt)
                        }
                        else -> error("电视不支持此命令")
                    }
                    success = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "遥控执行失败"
                } finally {
                    if (remoteCommandId == commandId) remoteCommandId = ""
                }
                remoteResults.record(commandId, success)
                runCatching { acknowledgeCommand(sessionId, commandId, success) }
                report()
            }
            return@withLock
        }
    }
    fun report() {
        val clock = playbackClock.sample(actuallyPlaying())
        if (!connected || !shouldReportPlaybackActivity(reportingAttempt, expiredReportAttempt, playerState)) return
        lastPlaybackReportAt = SystemClock.elapsedRealtime()
        val sessionId = snapshot.getString("id")
        val generation = playbackLifecycleGeneration
        val connection = client.credentials()
        val connectionGeneration = client.connectionGeneration
        val attempt = reportingAttempt; val sequence = ++seq; val state = playerState
        val pos = if (loadedAttempt == attempt) player.currentPosition.coerceAtLeast(0) + timelineOffset else 0
        val playback = snapshot.optJSONObject("playback")
        val songId = playback?.optString("songId").orEmpty()
        val durationMs = if (state == "PLAYING" && player.isPlaying && foreground &&
            snapshot.optString("status") == "ACTIVE" && hasConfirmedPlayback &&
            !resourceChanging && !resourceFailed && songId.isNotBlank() && currentSong?.optString("id") == songId) {
            originalPlaybackDurationMs(sourceDuration, player.duration, timelineOffset,
                dynamic = player.isCurrentMediaItemDynamic, live = player.isCurrentMediaItemLive)
        } else null
        val event = JSONObject().put("attemptId", attempt).put("eventSeq", sequence).put("state", state).put("positionMs", pos)
            .put("actualPlayedMs", clock.actualPlayedMs).put("elapsedMs", clock.elapsedMs)
            .put("startedAt", playbackClock.startedAtEpochMs).put("occurredAt", playbackClock.occurredAtEpochMs)
        playbackClock.lastActivityAtEpochMs?.let { event.put("lastActivityAt", it) }
        durationMs?.let { event.put("durationMs", it) }
        if (activeAudioRole in listOf("original", "backing")) event.put("audioRole", activeAudioRole)
        scope.launch {
            val response = try {
                JSONObject(client.request("/api/v1/tv/sessions/$sessionId/playback-events", "POST", event, connection))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is TvHttpException && e.status == 410 && reportingAttempt == attempt &&
                    client.connectionGeneration == connectionGeneration && snapshot.optString("id") == sessionId) expiredReportAttempt = attempt
                return@launch
            }
            if (!connected || generation != playbackLifecycleGeneration || connectionGeneration != client.connectionGeneration ||
                connection != client.credentials() || snapshot.optString("id") != sessionId) return@launch
            acceptSnapshot(response)
            if (foreground) this@TvApp.run { executeCommands() }
        }
    }
    private fun actuallyPlaying() = loadedAttempt.isNotBlank() && hasConfirmedPlayback && foreground &&
        !resourceChanging && !resourceFailed && player.isPlaying
    private fun releasePlaybackHistory() {
        if (!connected || reportingAttempt.isBlank() || playerState in listOf("ENDED", "ERROR")) return
        val sessionId = snapshot.optString("id"); if (sessionId.isBlank()) return
        val sample = playbackClock.sample(false)
        val connection = client.credentials()
        val event = JSONObject().put("attemptId", reportingAttempt).put("eventSeq", ++seq).put("state", "IDLE")
            .put("positionMs", if (loadedAttempt == reportingAttempt) player.currentPosition.coerceAtLeast(0) + timelineOffset else 0)
            .put("actualPlayedMs", sample.actualPlayedMs).put("elapsedMs", sample.elapsedMs)
            .put("startedAt", playbackClock.startedAtEpochMs).put("occurredAt", playbackClock.occurredAtEpochMs)
        playbackClock.lastActivityAtEpochMs?.let { event.put("lastActivityAt", it) }
        CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(3000) { runCatching { client.request("/api/v1/tv/sessions/$sessionId/playback-events", "POST", event, connection) } }
        }
    }
    fun foreground() { foreground = true;refreshFavorites();playbackOptions.refreshRoutes();resumeUpdateInstallation();if(connected){reconnect();checkUpdate(manual = false)} }
    fun background() { playbackLifecycleGeneration++; reactionCursor.reset(); partyReactions = emptyList(); foreground = false; login.pausePairing(); pauseLocal() }
    fun pauseLocal() { resumeAfterPreparation = false; player.pause(); report() }
    fun stopLocal() {
        playbackLifecycleGeneration++; resourceGeneration++; audioRoleGeneration++
        failedLoadCommand = pendingLoadCommand; resourceJob?.cancel(); resourceJob = null
        pendingLoadCommand = ""; lastLoadCommand = null; lastLoadSessionId = ""
        resumeAfterPreparation = false; resourceChanging = false
        releaseExecution(); player.stop(); player.playWhenReady = false; playerState = "IDLE"
        report()
        reportingAttempt = ""
    }
    fun ensureInvitation() = requestInvitation(force = false)
    fun invite() = requestInvitation(force = true)
    private fun requestInvitation(force: Boolean) {
        if (!connected || !online || snapshot.optString("status") != "ACTIVE") return
        val next = partyInvitation.begin(System.currentTimeMillis(), force) ?: return
        partyInvitation = next
        val server = client.base; val credential = client.token; val sid = snapshot.getString("id")
        scope.launch {
            try {
                val result = json("/api/v1/tv/sessions/$sid/invitations", "POST")
                if (server == client.base && credential == client.token && sid == snapshot.optString("id")) {
                    partyInvitation = partyInvitation.complete(next.generation, server + "/party/#invite=" + result.getString("invitation"), invitationExpiry(result.getString("expiresAt")))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (server == client.base && credential == client.token && sid == snapshot.optString("id")) {
                    partyInvitation = partyInvitation.fail(next.generation, e.message ?: "二维码加载失败，请重试", System.currentTimeMillis())
                }
            }
        }
    }
    private fun clearPartyConnection() {
        categoryResults = false
        queueAddGeneration++; queueAddSongId = ""; queueAddMessage = ""
        queueAddMessageJob?.cancel(); queueAddMessageJob = null
        partyInvitation = partyInvitation.clear()
        participantsGeneration++
        participants = emptyList()
        participantsKnown = false
        lastParticipantsRefresh = 0
    }
    fun disconnect() { sharedFavorites.reset(); currentSongGeneration++; currentSong=null; playbackMedia=JSONObject(); observedVideo=false; observedAudio=false; selectedAudioFormat=null; embeddedCues=emptyList(); login.open(LoginPage.METHODS);playlistGeneration++;playlistDetail=null;playlists=emptyList();playlistPage=0;playlistQuery="";playlistTotal=0;lastPlaylistId=""; stopLocal();loadedAttempt="";pendingOperation=null;resetUpdates();online=false; client.clear(); sharedFavorites.reset(); connected = false; login.cancel(); snapshot = JSONObject(); clearPartyConnection(); artistDetail=null;artistDetailGeneration++;lastArtistId=""; selected = null; homeSongs = emptyList(); homeAppearance = JSONObject(); homePlaylists = emptyList(); songs = emptyList(); artists = emptyList(); tags = emptyList(); participants = emptyList(); homeTotal = 0; artistTotal = 0; total = 0; query = ""; artistQuery = ""; number = 0; artistPage = 0; artistId = ""; selectedTags = emptySet(); unclassified = false; requestGeneration++; artistGeneration++; detailGeneration++ }
    fun newSession() = run { acceptSnapshot(json("/api/v1/tv/sessions", "POST"), replaceSession=true); declarePartyCapabilities(); ensureInvitation() }
    fun close() { resetUpdates(); releasePlaybackHistory(); login.close(); resourceGeneration++; releaseExecution(); discovery.stop(); playbackOptions.close(); scope.cancel(); player.release() }
}
