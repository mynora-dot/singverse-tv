@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File

/** D-pad checks run against production Compose UI; fixtures never enter the production source set. */
class TvHomeRemoteTest : InstrumentationTestCase() {
    private lateinit var fixture: TvUiFixture
    private lateinit var activity: TvUiFixtureActivity
    private val keyHistory = mutableListOf<String>()
    private val app get() = activity.app
    private val output get() = File(instrumentation.targetContext.getExternalFilesDir(null), "tv-ui-checks").apply { mkdirs() }

    override fun setUp() {
        super.setUp()
        fixture = TvUiFixture()
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-ui-fixture", fixture.base)
        }) as TvUiFixtureActivity
        waitFor("首页初始遥控焦点") { focusedLabel().contains("首页") }
        waitForHomeSections()
        keyHistory.add("初始 ${focusDetails()}")
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) {
                screenshot("${name}-final")
                File(output, "${name}-tree.txt").writeText(allNodes().joinToString("\n") {
                    val rect = Rect(); it.getBoundsInScreen(rect)
                    "focused=${it.isFocused} selected=${it.isSelected} state=${stateDescription(it)} focusable=${it.isFocusable} clickable=${it.isClickable} visible=${it.isVisibleToUser} bounds=$rect ${nodeLabel(it)}"
                })
                File(output, "${name}-keys.txt").writeText(keyHistory.joinToString("\n"))
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (::fixture.isInitialized) fixture.close()
            super.tearDown()
        }
    }

    /** Runnable screenshot acceptance: production Compose, real loopback readers, no seeded home state. */
    fun testHomeAFirstScreenShowsFavoritesHistoryPopularAndPhone() {
        waitForHomeSections()
        assertTrue("首页同时展示三个功能标题", listOf("我的收藏", "历史记录", "热门歌曲").all { title ->
            allNodes().any { it.isVisibleToUser && it.text?.toString() == title }
        })
        assertTrue("历史按真实 recent 接口加载", fixture.homeActivityRequests.any {
            val uri = android.net.Uri.parse(it)
            uri.path == "/api/v1/party/library/recent" && uri.getQueryParameter("period") == "all"
        })
        assertTrue("热门按真实 30d 接口加载", fixture.homeActivityRequests.any {
            val uri = android.net.Uri.parse(it)
            uri.path == "/api/v1/party/library/popular" && uri.getQueryParameter("period") == "30d"
        })
        val favorites = (1..4).map(::homeSongBounds)
        assertTrue("四张收藏封面在同一横排", favorites.maxOf { it.centerY() } - favorites.minOf { it.centerY() } <= 3)
        assertTrue("收藏从左到右有序展示", favorites.zipWithNext().all { (left, right) -> left.right <= right.left })
        val history = (5..7).map(::homeSongBounds)
        assertTrue("历史显示三行", history.zipWithNext().all { (above, below) -> above.bottom <= below.top })
        val popular = (8..11).map(::homeSongBounds)
        assertTrue("热门第一行两列", kotlin.math.abs(popular[0].centerY() - popular[1].centerY()) <= 3 && popular[0].right <= popular[1].left)
        assertTrue("热门第二行两列", kotlin.math.abs(popular[2].centerY() - popular[3].centerY()) <= 3 && popular[2].right <= popular[3].left)
        assertTrue("热门两行分开", popular[0].bottom <= popular[2].top)
        val image = screenshot("home-a-favorites-history-popular")
        (favorites + history + popular).forEach { bounds ->
            assertTrue("三个区的歌曲均完整留在电视安全区：$bounds", bounds.left >= image.width / 30 && bounds.right <= image.width * 29 / 30 && bounds.top > 0 && bounds.bottom < image.height)
        }
        image.recycle()
        assertTrue("手机点歌仍可访问", onMain { activity.focusMemory.targets.containsKey("首页:phone") })
        assertTrue("搜索与三个查看全部均保留独立入口", listOf("search", "favorites:all", "history:all", "popular:all").all { key ->
            onMain { activity.focusMemory.targets.containsKey("首页:$key") }
        })
        assertTrue("首页浏览不应发出任何播放或点歌命令", fixture.commandRequests.isEmpty())
        assertSingleVisibleFocus()
    }

    fun testHomeFavoriteRowMovesWithRemoteAndRestoresAfterDetail() {
        waitForHomeSections()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("首页导航下键直接进入收藏首曲") { focusedAddress() == "首页:favorite:test-song-1" }
        assertSingleVisibleFocus()
        (2..4).forEach { number ->
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("收藏横排右键进入第$number 首") { focusedAddress() == "首页:favorite:test-song-$number" }
            assertSingleVisibleFocus()
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认收藏打开真实歌曲详情") { onMain { app.selected?.optString("id") == "test-song-4" } && focusedAddress().endsWith(":action:加入待唱") }
        assertTrue("打开详情不应立即点歌", fixture.commandRequests.isEmpty())
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复原收藏焦点") { onMain { app.selected == null } && focusedAddress() == "首页:favorite:test-song-4" }
        assertSingleVisibleFocus()
        screenshot("home-a-favorite-focus-restored").recycle()
    }

    fun testHomeAllFavoritesIsReachableWithThreeFavorites() {
        fixture.homeFavoritesCount = 3
        onMain { app.refreshFavorites() }
        waitFor("三首收藏加载完成") { onMain { !app.favoritesLoading && app.favoriteSongs().size == 3 } }

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入第一首收藏", focusedAddressCondition("首页:favorite:test-song-1"))
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor("右键到达末首收藏", focusedAddressCondition("首页:favorite:test-song-3"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("从末首收藏上键进入全部收藏", focusedAddressCondition("首页:favorites:all"))
        assertSingleVisibleFocus()

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("从全部收藏下键回到末首收藏", focusedAddressCondition("首页:favorite:test-song-3"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认打开收藏列表") { onMain { app.page == "收藏" && !activity.focusMemory.restoring } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("从收藏列表返回全部收藏", focusedAddressCondition("首页:favorites:all"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("从全部收藏上键返回首页导航", focusedAddressCondition("首页:nav:首页"))
        assertSingleVisibleFocus()
    }

    fun testHomeTwoFavoritesUseWideCardsAndKeepRemoteTraversal() {
        val originalWidth = homeSongBounds(1).width()
        fixture.homeFavoritesCount = 2
        onMain { app.refreshFavorites() }
        waitFor("两首收藏加载完成") { onMain { !app.favoritesLoading && app.favoriteSongs().size == 2 } }
        val first = homeSongBounds(1)
        val second = homeSongBounds(2)
        assertTrue("两首收藏的卡片各占约一半收藏区", first.width() >= originalWidth * 1.8f && kotlin.math.abs(second.width() - first.width()) <= 1)
        assertTrue("两张收藏左右排列", first.right <= second.left && first.centerY() == second.centerY())

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入第一首收藏", focusedAddressCondition("首页:favorite:test-song-1"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键进入第二首收藏", focusedAddressCondition("首页:favorite:test-song-2"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("从末首收藏右键进入手机点歌", focusedAddressCondition("首页:phone"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("从手机点歌下键进入搜索", focusedAddressCondition("首页:search"))
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("从搜索左键回到末首收藏", focusedAddressCondition("首页:favorite:test-song-2"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认打开第二首详情") {
            onMain { app.selected?.optString("id") == "test-song-2" } && focusedAddress().endsWith(":action:加入待唱")
        }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("从详情返回同一首收藏", focusedAddressCondition("首页:favorite:test-song-2"))
        assertTrue("浏览收藏期间队列保持空闲", fixture.commandRequests.isEmpty())
        assertSingleVisibleFocus()
        screenshot("home-a-two-favorites").recycle()
    }

    fun testHomeUnavailableSongsAreDisabledAndNavigationSkipsThem() {
        fixture.unavailableSongIds = setOf("test-song-1", "test-song-5", "test-song-8")
        onMain { app.refreshFavorites(); app.refreshHomeActivity() }
        waitFor("不可用标记通过三个真实接口同步") { onMain {
            !app.favoritesLoading && !app.homeHistoryLoading && !app.homePopularLoading &&
                !app.favoriteSongs().first().optBoolean("available", true) &&
                !app.homeHistorySongs.first().optBoolean("available", true) &&
                !app.homePopularSongs.first().optBoolean("available", true)
        } }
        val detailRequests = fixture.httpEvents.count { it.matches(Regex("begin GET /api/v1/tv/library/songs/test-song-\\d+")) }
        listOf(1, 5, 8).forEach { number ->
            val title = "测试歌曲 ${number.toString().padStart(2, '0')}"
            val disabled = allNodes().firstOrNull { it.isVisibleToUser && !it.isEnabled && nodeLabel(it).contains(title) }
            assertNotNull("不可用歌曲应保留明确禁用语义：$title", disabled)
            assertTrue("不可用歌曲显示原因：$title", nodeLabel(disabled!!).contains("暂不可用"))
            disabled.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
        assertEquals("不可用歌曲不能请求详情", detailRequests, fixture.httpEvents.count { it.matches(Regex("begin GET /api/v1/tv/library/songs/test-song-\\d+")) })
        assertNull("不可用歌曲不能打开详情", onMain { app.selected })
        assertTrue("不可用歌曲不能发出 ADD", fixture.commandRequests.isEmpty())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("首页下键跳过不可用首收藏") { focusedAddress() == "首页:favorite:test-song-2" }
        assertSingleVisibleFocus()
        screenshot("home-a-unavailable-songs").recycle()

        fixture.unavailableSongIds = (1..4).map { "test-song-$it" }.toSet()
        onMain { app.refreshFavorites() }
        waitFor("四张收藏均标记不可用") { onMain { !app.favoritesLoading && app.favoriteSongs().all { !it.optBoolean("available", true) } } }
        focusHomeTarget("nav:首页")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("全部收藏不可用时下键回退到全部收藏入口") { focusedAddress() == "首页:favorites:all" }
        assertSingleVisibleFocus()
        assertTrue("禁用歌曲不应产生任何队列命令", fixture.commandRequests.isEmpty())
    }

    fun testHomeActivityDeepRowSurvivesDetailAndForegroundRefresh() {
        fixture.songCount = 50
        fixture.homeHistoryCount = 30
        fixture.homePopularCount = 30
        listOf(Triple("history", "历史记录", 5), Triple("popular", "热门歌曲", 8)).forEach { (prefix, destination, firstNumber) ->
            focusHomeTarget("$prefix:all")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("$destination 首页从真实接口读取") { onMain {
                app.page == destination && !activity.focusMemory.restoring &&
                    (if (prefix == "history") app.homeHistorySongs.size == 20 && !app.homeHistoryLoading else app.homePopularSongs.size == 20 && !app.homePopularLoading)
            } }
            onMain { if (prefix == "history") app.loadMoreHomeHistory() else app.loadMoreHomePopular() }
            waitFor("$destination 第二页合并为完整窗口") { onMain {
                if (prefix == "history") app.homeHistorySongs.size == 30 && !app.homeHistoryLoading else app.homePopularSongs.size == 30 && !app.homePopularLoading
            } }
            val first = "$destination:$prefix:test-song-$firstNumber"
            waitFor("$destination 首曲焦点可用") { onMain { activity.focusMemory.targets.containsKey(first) } }
            onMain { activity.focusMemory.targets[first]?.requestFocus() }
            waitFor("$destination 遥控从首曲开始") { focusedAddress() == first }
            repeat(24) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val number = firstNumber + 24
            val address = "$destination:$prefix:test-song-$number"
            waitFor("$destination 遥控到第二页第25首") { focusedAddress() == address }
            assertSingleVisibleFocus()
            screenshot("home-$prefix-row-25-before-detail").recycle()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("$destination 深行可打开真实详情") { onMain { app.selected?.optString("id") == "test-song-$number" } }
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$destination 详情返回恢复第25首及滚动位置") { onMain { app.selected == null } && focusedAddress() == address }
            assertSingleVisibleFocus()
            val secondPageRequests = fixture.homeActivityRequests.count {
                val uri = android.net.Uri.parse(it)
                uri.path == "/api/v1/party/library/${if (prefix == "history") "recent" else "popular"}" && uri.getQueryParameter("page") == "1"
            }
            onMain { app.background(); app.foreground() }
            waitFor("$destination 前台刷新重新读取第二页") { fixture.homeActivityRequests.count {
                val uri = android.net.Uri.parse(it)
                uri.path == "/api/v1/party/library/${if (prefix == "history") "recent" else "popular"}" && uri.getQueryParameter("page") == "1"
            } > secondPageRequests && onMain {
                if (prefix == "history") !app.homeHistoryLoading && app.homeHistorySongs.size == 30 else !app.homePopularLoading && app.homePopularSongs.size == 30
            } }
            assertEquals("$destination 前台刷新不得裁掉第25首焦点", address, focusedAddress())
            assertSingleVisibleFocus()
            screenshot("home-$prefix-row-25-after-foreground").recycle()
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$destination 回到首页原全部入口") { onMain { app.page == "首页" } && focusedAddress() == "首页:$prefix:all" }
        }
        assertTrue("浏览与详情恢复不能发出点歌命令", fixture.commandRequests.isEmpty())
    }

    fun testHomeSectionEntriesAndSearchReturnToTheirTrigger() {
        waitForHomeSections()
        listOf("favorites:all" to "收藏", "history:all" to "历史记录", "popular:all" to "热门歌曲", "search" to "找歌").forEach { (target, destination) ->
            focusHomeTarget(target)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("$target 打开$destination") { onMain { app.page == destination && !activity.focusMemory.restoring } }
            assertSingleVisibleFocus()
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$destination 返回恢复首页原入口") { onMain { app.page == "首页" } && focusedAddress() == "首页:$target" }
            assertSingleVisibleFocus()
        }
    }

    fun testHomeArtistsKeepFullLineHeightAndVisibleGlyphs() {
        waitForHomeSections()
        val rows = onMain { app.favoriteSongs().take(4) + app.homeHistorySongs.take(3) + app.homePopularSongs.take(4) }
        val scaledDensity = instrumentation.targetContext.resources.displayMetrics.scaledDensity
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * scaledDensity }
        val image = screenshot("home-a-complete-artist-lines")
        rows.forEach { song ->
            val artist = allNodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == song.artists() }
            assertNotNull("可见曲目应保留歌手文本：${song.artists()}", artist)
            val bounds = Rect().also { artist!!.getBoundsInScreen(it) }
            val glyphs = Rect().also { textPaint.getTextBounds(song.artists(), 0, song.artists().length, it) }
            assertTrue("歌手必须容纳完整字形高度：${song.artists()} $bounds", bounds.height() >= glyphs.height())
            assertTrue("歌手文本留在可见画面内", bounds.top >= 0 && bounds.bottom <= image.height)
        }
        image.recycle()
    }

    fun testHomeEmptySectionsKeepAllEntriesAndRemoteFocusUsable() {
        fixture.homeFavoritesCount = 0
        fixture.homeHistoryCount = 0
        fixture.homePopularCount = 0
        onMain { app.refreshFavorites(); app.refreshHomeActivity() }
        waitFor("三个内容区真实空结果就绪") { onMain {
            app.favoritesReady && app.homeHistoryReady && app.homePopularReady && !app.favoritesLoading && !app.homeHistoryLoading && !app.homePopularLoading &&
                app.favoriteSongs().isEmpty() && app.homeHistorySongs.isEmpty() && app.homePopularSongs.isEmpty()
        } }
        listOf("favorites:all", "history:all", "popular:all", "search", "phone").forEach { target ->
            focusHomeTarget(target)
            assertSingleVisibleFocus()
        }
        assertFalse("空态不能保留已移除歌曲焦点", focusedAddress().contains(":favorite:test-song-") || focusedAddress().contains(":history:test-song-") || focusedAddress().contains(":popular:test-song-"))
        screenshot("home-a-empty-sections").recycle()
    }

    fun testHomeRefreshErrorsKeepLoadedSongsAndRecoverIndependently() {
        waitForHomeSections()
        val originalFavorites = onMain { app.favoriteSongs().map { it.getString("id") } }
        val originalHistory = onMain { app.homeHistorySongs.map { it.getString("id") } }
        val originalPopular = onMain { app.homePopularSongs.map { it.getString("id") } }
        fixture.failNextFavorites.set(503)
        fixture.failNextHomeHistory.set(503)
        fixture.failNextHomePopular.set(503)
        onMain { app.refreshFavorites(); app.refreshHomeActivity() }
        waitFor("三个区域分别显示读取失败") { onMain { app.favoritesError.isNotBlank() && app.homeHistoryError.isNotBlank() && app.homePopularError.isNotBlank() } }
        assertEquals("收藏刷新失败保留已加载歌曲", originalFavorites, onMain { app.favoriteSongs().map { it.getString("id") } })
        assertEquals("历史刷新失败保留已加载歌曲", originalHistory, onMain { app.homeHistorySongs.map { it.getString("id") } })
        assertEquals("热门刷新失败保留已加载歌曲", originalPopular, onMain { app.homePopularSongs.map { it.getString("id") } })
        assertEquals("区域错误不应覆盖为全屏操作错误", "", onMain { app.error })
        screenshot("home-a-refresh-errors").recycle()
        onMain { app.refreshHomeHistory() }
        waitFor("只重试历史可独立恢复") { onMain { app.homeHistoryError.isEmpty() && !app.homeHistoryLoading } }
        assertTrue("历史恢复不应抹掉其他区域错误", onMain { app.favoritesError.isNotBlank() && app.homePopularError.isNotBlank() })
        onMain { app.refreshFavorites(); app.refreshHomePopular() }
        waitFor("其余区域重试后清错") { onMain { app.favoritesError.isEmpty() && app.homePopularError.isEmpty() && !app.favoritesLoading && !app.homePopularLoading } }
        assertSingleVisibleFocus()
    }

    fun testHomeInitialFailuresExposeIndependentRemoteRetries() {
        // A one-shot error is insufficient: the production snapshot loop also refreshes home.
        fixture.favoritesFailureStatus = 503
        fixture.homeHistoryFailureStatus = 503
        fixture.homePopularFailureStatus = 503
        onMain {
            app.disconnect()
            app.client.base = fixture.base; app.client.token = "test-reconnected-fixture-device"
            app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot(); app.page = "首页"
            app.refreshFavorites(); app.refreshHomeActivity()
        }
        waitFor("冷读取失败显示各区独立重试") { onMain {
            app.favoritesError.isNotBlank() && app.homeHistoryError.isNotBlank() && app.homePopularError.isNotBlank() &&
                !app.favoritesLoading && !app.homeHistoryLoading && !app.homePopularLoading
        } }
        assertTrue("失败不应编造歌曲", onMain { app.favoriteSongs().isEmpty() && app.homeHistorySongs.isEmpty() && app.homePopularSongs.isEmpty() })
        screenshot("home-a-initial-errors").recycle()
        listOf("favorites:retry", "history:retry", "popular:retry").forEachIndexed { index, target ->
            focusHomeTarget(target)
            assertSingleVisibleFocus()
            when (target) {
                "favorites:retry" -> fixture.favoritesFailureStatus = 0
                "history:retry" -> fixture.homeHistoryFailureStatus = 0
                else -> fixture.homePopularFailureStatus = 0
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("遥控重试成功：$target") { onMain { when (target) {
                "favorites:retry" -> app.favoritesReady && app.favoritesError.isEmpty() && app.favoriteSongs().size == 4
                "history:retry" -> app.homeHistoryReady && app.homeHistoryError.isEmpty() && app.homeHistorySongs.size == 3
                else -> app.homePopularReady && app.homePopularError.isEmpty() && app.homePopularSongs.size == 4
            } } }
            if (index == 0) waitFor("收藏恢复时，历史仍保持独立错误与空列表") { onMain {
                app.homeHistoryError.isNotBlank() && app.homeHistorySongs.isEmpty()
            } }
            if (index < 2) waitFor("其他区恢复不能擅自清除热门错误") { onMain {
                app.homePopularError.isNotBlank() && app.homePopularSongs.isEmpty()
            } }
        }
        assertEquals("区域错误不会遮挡全屏", "", onMain { app.error })
        screenshot("home-a-retry-recovered").recycle()
    }

    fun testHomeLateOldNasActivityCannotReplaceNewConnection() {
        waitForHomeSections()
        fixture.homeHistoryDelayMs = 1800
        val previousRequests = fixture.httpEvents.count { it == "begin GET /api/v1/party/library/recent" }
        val previousResponses = fixture.httpEvents.count { it.startsWith("end GET /api/v1/party/library/recent ") }
        onMain { app.refreshHomeHistory() }
        waitFor("旧 NAS 历史读取已在途") { fixture.httpEvents.count { it == "begin GET /api/v1/party/library/recent" } > previousRequests }
        TvUiFixture().use { next ->
            next.homeSongOffset = 9
            onMain {
                app.disconnect()
                app.client.base = next.base; app.client.token = "test-next-fixture-device"
                app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot(); app.page = "首页"
                app.refreshFavorites(); app.refreshHomeActivity()
            }
            waitFor("新 NAS 显示自己的收藏与活动") { onMain {
                app.favoriteSongs().firstOrNull()?.optString("id") == "test-song-10" &&
                    app.homeHistorySongs.firstOrNull()?.optString("id") == "test-song-14" &&
                    app.homePopularSongs.firstOrNull()?.optString("id") == "test-song-17"
            } }
            waitFor("旧 NAS 延迟响应已结束") { fixture.httpEvents.count { it.startsWith("end GET /api/v1/party/library/recent ") } > previousResponses }
            assertEquals("旧历史不能覆盖新 NAS", "test-song-14", onMain { app.homeHistorySongs.first().getString("id") })
            assertEquals("旧收藏不能回到新 NAS 首页", "test-song-10", onMain { app.favoriteSongs().first().getString("id") })
            assertEquals("旧请求不能污染新 NAS 错误", "", onMain { app.homeHistoryError })
            screenshot("home-a-new-nas-only").recycle()
        }
    }

    fun testNavigationSwitchesOnFocusAndKeepsSearchInputUsable() {
        val songCenterBounds = navigationBounds("点歌")
        assertEquals("首页初始页面", "首页", onMain { app.page })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键直接进入点歌并保留导航焦点") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:nav:点歌" }
        assertSingleVisibleFocus()
        val image = screenshot("navigation-focus-selects-page")
        assertEquals("当前焦点导航同步成为选中页面", "当前页面", stateDescription(allNodes().first { it.isClickable && nodeLabel(it) == "点歌" }))
        assertTrue("点歌焦点保留深色容器", colorPixels(image, 44, 44, 46, songCenterBounds) > 100)
        val focusBounds = Rect(
            (songCenterBounds.left - 12).coerceAtLeast(0), (songCenterBounds.top - 12).coerceAtLeast(0),
            (songCenterBounds.right + 12).coerceAtMost(image.width), (songCenterBounds.bottom + 12).coerceAtMost(image.height),
        )
        assertTrue("点歌当前遥控焦点应有可见白色描边", colorPixels(image, 245, 245, 247, focusBounds) > 100)
        assertSingleVisibleFocus()
        image.recycle()
        assertTrue("点歌中心保留四项真实功能", listOf("搜索歌曲", "歌手点歌", "分类点歌", "我的歌单").all { text -> allNodes().any { nodeLabel(it).contains(text) } })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(3) { if (!focusedLabel().contains("搜索歌曲")) key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("点歌中心可用方向键进入搜索入口", focusedLabel().contains("搜索歌曲"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("真实搜索页面") { onMain { app.page == "找歌" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("搜索输入行遥控焦点") { focusedLabel().contains("歌名 / 歌手 / 拼音 / 编号") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认进入可编辑输入框") { focusedAddress().contains(":input:") }
        instrumentation.sendStringSync("remote")
        waitFor("硬件按键输入进入真实搜索状态") { onMain { app.query == "remote" } }
        screenshot("search-input-editing")
        key(KeyEvent.KEYCODE_BACK)
        // An IME may consume the first Back to hide itself before the editor receives Back.
        if (focusedAddress().contains(":input:")) key(KeyEvent.KEYCODE_BACK)
        waitFor("结束编辑恢复原输入行") { focusedAddress().endsWith(":action:remote") }
        assertEquals("退出键盘不能离开搜索页面", "找歌", onMain { app.page })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("搜索返回点歌中心并恢复搜索入口焦点") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:quick:0" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回首页恢复当前首页导航焦点") { onMain { app.page == "首页" } && focusedAddress() == "首页:nav:首页" }
        assertSingleVisibleFocus()
    }

    fun testTopNavigationSwitchesWithoutConfirmAndRetainsFocus() {
        listOf("点歌", "收藏", "已点", "设置", "已点", "收藏", "点歌", "首页").forEachIndexed { index, destination ->
            key(if (index < 4) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            waitFor("方向键直接切换至$destination") { onMain { app.page == destination && !activity.focusMemory.restoring } && focusedAddress() == "$destination:nav:$destination" }
            assertTrue("页面选中状态同步导航", allNodes().any { it.isClickable && nodeLabel(it).startsWith(destination) && stateDescription(it) == "当前页面" })
            assertSingleVisibleFocus()
        }
        repeat(2) {
            repeat(4) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(70) }
            waitFor("快速连续右键仍停留设置导航") { onMain { app.page == "设置" && !activity.focusMemory.restoring } && focusedAddress() == "设置:nav:设置" }
            repeat(4) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); SystemClock.sleep(70) }
            waitFor("快速连续左键仍停留首页导航") { onMain { app.page == "首页" && !activity.focusMemory.restoring } && focusedAddress() == "首页:nav:首页" }
        }
        assertTrue("浏览导航不能发出点歌或播放命令", fixture.commandRequests.isEmpty())
        waitFor("连续切换的在途读取已完成") { onMain { !app.busy } }
        val requests = fixture.httpEvents.count { it == "begin GET /api/v1/tv/home" }
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals("当前导航重复确认不能重复加载或抢走焦点", requests, fixture.httpEvents.count { it == "begin GET /api/v1/tv/home" })
        assertEquals("首页:nav:首页", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("切换后下键仍进入页面内容") { focusedAddress() == "首页:favorite:test-song-1" }
        assertSingleVisibleFocus()
    }

    fun testCategoryTabsSwitchOnFocusAndRestoreWithoutNavigating() {
        onMain { app.navigate("分类") }
        waitFor("分类页面恢复导航焦点不跳回点歌") { onMain { app.page == "分类" && !app.busy && !activity.focusMemory.restoring } && focusedAddress() == "分类:nav:点歌" }
        val navigation = focusedAddress()
        onMain { app.error = "测试导航恢复" }
        waitFor("打开操作提示") { onMain { activity.focusMemory.activeDialogs == 1 } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示恢复分类页导航，不自动进入点歌中心") { onMain { app.page == "分类" && activity.focusMemory.activeDialogs == 0 } && focusedAddress() == navigation }
        val dimensions = listOf("语种", "年代", "风格", "情绪", "场景", "自定义")
        repeat(4) { if (focusedAddress().removePrefix("分类:action:") !in dimensions) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(5) { if (focusedAddress() != "分类:action:语种") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        val requests = fixture.songRequests.size
        dimensions.forEachIndexed { index, label ->
            if (index > 0) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("焦点进入$label 后直接切换分类内容") {
                focusedAddress() == "分类:action:$label" && allNodes().any { nodeLabel(it).contains(when (label) { "语种" -> "国语"; "风格" -> "流行"; else -> "暂无${label}分类" }) }
            }
            assertEquals("切换维度仍留在分类页面", "分类", onMain { app.page })
            assertSingleVisibleFocus()
        }
        assertEquals("浏览维度不能打开歌曲结果", requests, fixture.songRequests.size)
        assertTrue("浏览维度不能修改已点队列", fixture.commandRequests.isEmpty())
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        waitFor("左键直接回到风格分类") { focusedAddress() == "分类:action:风格" && allNodes().any { nodeLabel(it).contains("流行") } }
        onMain { app.error = "测试分类焦点恢复" }
        waitFor("分类标签上打开提示") { onMain { activity.focusMemory.activeDialogs == 1 } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示保留原分类标签和内容") { focusedAddress() == "分类:action:风格" && onMain { app.page == "分类" } && allNodes().any { nodeLabel(it).contains("流行") } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("维度切换后下键进入当前分类") { focusedAddress() == "分类:category:tag-genre" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认分类卡片仍直接进入歌曲") { onMain { app.page == "找歌" && app.selectedTags == setOf("tag-genre") } && focusedAddress() == "找歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("歌曲返回仍保留风格维度和来源卡片") { focusedAddress() == "分类:category:tag-genre" }
        assertSingleVisibleFocus()
    }

    fun testSongCenterCoversOpenSongAndRestoreRemoteFocus() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("进入点歌中心", focusedAddressCondition("点歌:nav:点歌"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入点歌方式") { focusedAddress().startsWith("点歌:quick:") }
        repeat(3) { if (focusedAddress() != "点歌:quick:0") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        waitFor("聚焦搜索入口", focusedAddressCondition("点歌:quick:0"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入曲库第一首歌曲", focusedAddressCondition("点歌:song:test-song-1"))
        for (number in 2..8) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("右键选中曲库第 $number 首歌曲", focusedAddressCondition("点歌:song:test-song-$number"))
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        val cover = "点歌:song:test-song-7"
        waitFor("左键回到上一首", focusedAddressCondition(cover))
        assertTrue("封面按钮具有歌曲名称", allNodes().any { it.isClickable && nodeLabel(it).contains("测试歌曲 07") })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认打开对应歌曲详情") {
            onMain { app.selected?.optString("id") == "test-song-7" } && focusedAddress().endsWith(":action:加入待唱")
        }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复同一张曲库封面") { onMain { app.selected == null } && focusedAddress() == cover }
        assertTrue("浏览曲库封面不能修改队列", fixture.commandRequests.isEmpty())
        assertSingleVisibleFocus()
    }

    fun testSongCenterEntriesRestoreTheirRemoteFocus() {
        fun assertSearchTileNeutral(label: String) {
            val search = allNodes().first { it.isClickable && nodeLabel(it).startsWith("搜索歌曲") }
            val bounds = Rect().also { search.getBoundsInScreen(it) }
            val image = screenshot(label)
            val tone = if (focusedAddress() == "点歌:quick:0") 44 else 28
            assertTrue("搜索入口底色应与其他入口一致，不能常驻白底", colorPixels(image, tone, tone, tone + 2, bounds) > bounds.width() * bounds.height() / 2)
            image.recycle()
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键直接进入点歌中心") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:nav:点歌" }
        assertSearchTileNeutral("song-center-search-unfocused")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(3) {
            if (focusedAddress() != "点歌:quick:0") key(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        assertEquals("方向键进入第一张点歌方式卡片", "点歌:quick:0", focusedAddress())
        assertSingleVisibleFocus()
        assertSearchTileNeutral("song-center-search-focused")

        listOf("歌手" to 1, "分类" to 2, "歌单" to 3).forEach { (page, index) ->
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val originalFocus = "点歌:quick:$index"
            waitFor("聚焦${page}点歌入口") { focusedAddress() == originalFocus }
            if (index == 1) assertSearchTileNeutral("song-center-search-focus-moved")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("遥控进入${page}页面") { onMain { app.page == page } && focusedAddress().startsWith("$page:") }
            assertSingleVisibleFocus()
            assertTrue("${page}页面保留可见手机点歌入口", allNodes().any {
                it.isVisibleToUser && it.isClickable && nodeLabel(it) == "手机点歌"
            })
            screenshot("song-center-entry-$index")

            key(KeyEvent.KEYCODE_BACK)
            waitFor("${page}返回点歌中心恢复原入口") {
                onMain { app.page == "点歌" } && focusedAddress() == originalFocus
            }
            assertSingleVisibleFocus()
        }
    }

    fun testHomeExitDialogRestoresOriginalFavoriteFocus() {
        waitForHomeSections()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("第二张收藏获得焦点") { focusedAddress() == "首页:favorite:test-song-2" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("首页返回打开确认弹窗") { onMain { app.pendingConfirm != null } && focusedLabel().contains("取消") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("弹窗方向键可移动到确认", focusedLabel().contains("确认"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭弹窗恢复原收藏焦点") { onMain { app.pendingConfirm == null } && focusedAddress() == "首页:favorite:test-song-2" }
        assertSingleVisibleFocus()
    }

    fun testNestedErrorDialogRestoresPhoneDialogAndHomeFocus() {
        waitFor("手机邀请已就绪") { onMain { app.invitation == fixture.invitationUrl } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(6) {
            if (!focusedAddress().endsWith(":phone")) key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        assertTrue("首页手机点歌卡片可通过方向键到达", focusedAddress().endsWith(":phone"))
        val phoneCardAddress = focusedAddress()
        assertSingleVisibleFocus()

        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("手机点歌弹窗获得唯一焦点") {
            onMain { activity.focusMemory.activeDialogs == 1 } && focusedAddress().endsWith(":手机点歌:action:完成")
        }
        // Move away from the default so restoring the underlying dialog must retain its history.
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("手机弹窗聚焦刷新邀请") { focusedAddress().endsWith(":手机点歌:action:刷新邀请") }
        val phoneDialogAddress = focusedAddress()
        assertSingleVisibleFocus()
        screenshot("phone-dialog-before-async-error")

        onMain { app.error = "测试网络操作失败" }
        waitFor("异步错误覆盖手机弹窗") {
            onMain { activity.focusMemory.activeDialogs == 2 } && focusedAddress().endsWith(":操作提示:action:关闭提示")
        }
        assertSingleVisibleFocus()
        screenshot("phone-dialog-with-top-error")

        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭错误后恢复手机弹窗原焦点") {
            onMain { app.error.isEmpty() && activity.focusMemory.activeDialogs == 1 } && focusedAddress() == phoneDialogAddress
        }
        assertSingleVisibleFocus()
        screenshot("phone-dialog-restored-after-error")

        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭手机弹窗后恢复首页手机卡片") {
            onMain { activity.focusMemory.activeDialogs == 0 && app.page == "首页" && app.pendingConfirm == null } && focusedAddress() == phoneCardAddress
        }
        assertSingleVisibleFocus()
        screenshot("home-phone-focus-restored")
    }

    fun testRenderedInvitationQrDecodesAndRevokedDeviceIsNotCounted() {
        waitFor("动态邀请和参与者同步") { onMain { app.invitation == fixture.invitationUrl && app.participantsKnown } }
        assertEquals("已撤销设备不能算作已加入", 1, onMain { app.participantCount })
        waitFor("通过真实图片HTTP加载歌曲封面") { visibleCoverImages().size >= 5 }
        visibleCoverImages().forEach { cover ->
            val rect = Rect(); cover.getBoundsInScreen(rect)
            assertTrue("横图、竖图和方图原件都必须按1:1封面显示：$rect", kotlin.math.abs(rect.width() - rect.height()) <= 1)
        }
        val image = screenshot("home-dynamic-qr")
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val result = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        )
        assertEquals("从最终屏幕像素解码应得到本次动态邀请URL", fixture.invitationUrl, result.text)

        assertEquals("空队列数量", 0, onMain { queuedSongCount(app) })
        assertTrue("空队列仍有可用的底栏入口", allNodes().any { nodeLabel(it).contains("已点歌曲 (0)") })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入首张收藏", focusedAddressCondition("首页:favorite:test-song-1"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("收藏详情展示真实点歌动作") { onMain { app.selected?.optString("id") == "test-song-1" } && focusedAddress().endsWith(":action:加入待唱") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("真实ADD命令更新队列") { fixture.actions.contains("ADD") && onMain { queuedSongCount(app) == 1 } }
        repeat(6) { if (!focusedLabel().contains("已点歌曲")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        if (focusedLabel().contains("确认开唱")) key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("底栏已点入口须可达且绑定真实数量", focusedAddress().endsWith(":bar:queue") && onMain { queuedSongCount(app) == 1 })
        screenshot("home-added-queue-count")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("底栏进入真实已点页面") { onMain { app.page == "已点" } }
        assertEquals("已点页面继续保留刚加入的歌曲", 1, onMain { queuedSongCount(app) })
        screenshot("queue-added-song")
    }

    fun testSettingsGroupsKeepRemoteFocusAndRealParticipantState() {
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor("进入设置") { onMain { app.page == "设置" } }
        onMain { activity.focusMemory.targets["设置:action:连接"]?.requestFocus() }
        waitFor("聚焦连接分组") { focusedAddress().endsWith(":action:连接") }
        assertTrue("参与者数据来自隔离快照", onMain { app.participantCount == 1 })
        screenshot("settings-connection")

        listOf(
            "播放设置" to "可用音轨",
            "聚会管理" to "亲友点歌",
            "本场参与者" to "测试亲友",
            "设备信息" to "Android",
            "应用更新" to "检查更新",
        ).forEach { (group, content) ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("聚焦$group") { focusedAddress().endsWith(":action:$group") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("显示$group 内容") { allNodes().any { it.isVisibleToUser && nodeLabel(it).contains(content) } }
            assertSingleVisibleFocus()
            screenshot("settings-$group")
        }
    }

    fun testArtistAndPlaylistDetailsRestoreCardFocus() {
        onMain { app.navigate("歌手") }
        waitFor("真实歌手列表") { onMain { app.artists.isNotEmpty() && app.page == "歌手" } }
        repeat(4) { if (!focusedAddress().contains(":artist:test-artist")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("聚焦歌手卡片") { focusedAddress().contains(":artist:test-artist") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开歌手详情") { onMain { app.artistDetail?.optString("id") == "test-artist" } && focusedAddress().endsWith(":action:返回歌手") }
        assertTrue("歌手详情包含真实歌曲", allNodes().any { nodeLabel(it).contains("测试歌曲 01") })
        screenshot("artist-detail")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回歌手卡片") { onMain { app.artistDetail == null } && focusedAddress().contains(":artist:test-artist") }
        assertSingleVisibleFocus()

        onMain { app.navigate("歌单") }
        waitFor("真实歌单列表") { onMain { app.playlists.isNotEmpty() && app.page == "歌单" } }
        repeat(4) { if (!focusedAddress().contains(":playlist:test-playlist")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("聚焦歌单卡片") { focusedAddress().contains(":playlist:test-playlist") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开歌单详情") { onMain { app.playlistDetail?.optString("id") == "test-playlist" } && focusedAddress().endsWith(":action:返回歌单") }
        assertTrue("歌单详情包含真实歌曲", allNodes().any { nodeLabel(it).contains("测试歌曲 01") })
        screenshot("playlist-detail")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回歌单卡片") { onMain { app.playlistDetail == null } && focusedAddress().contains(":playlist:test-playlist") }
        assertSingleVisibleFocus()
    }

    fun testQueueRowActionsAreReachableWithRemote() {
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("加入第一首") { onMain { queuedSongCount(app) == 1 && app.pendingOperation == null } }
        onMain { app.command("ADD", songId = "test-song-2") }
        waitFor("加入第二首") { onMain { queuedSongCount(app) == 2 && app.pendingOperation == null } }
        onMain { app.navigate("已点") }
        waitFor("打开两首待唱") { onMain { app.page == "已点" } }
        repeat(5) { if (!focusedAddress().contains(":action:queue:test-queue-0")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("聚焦第一首待唱") { focusedAddress().contains(":action:queue:test-queue-0") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("第一首可聚焦下移") { focusedAddress().endsWith(":action:queue:test-queue-0:DOWN") }
        assertSingleVisibleFocus()
        screenshot("queue-row-actions")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("下移通过真实命令通道") { fixture.actions.contains("DOWN") }
    }

    fun testSongTablesAddDirectlyAcrossAllFourEntryPaths() {
        (0..3).forEach { entry ->
            enterSongTable(entry)
            repeat(entry) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val address = focusedAddress()
            val songId = address.substringAfterLast(":song:")
            val before = songRowBounds(songId)
            val count = onMain { queuedSongCount(app) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("第${entry + 1}条找歌路径直接入队") { onMain { queuedSongCount(app) == count + 1 && app.queueAddSongId.isEmpty() } }
            assertEquals("入队应保留原歌曲焦点", address, focusedAddress())
            assertEquals("入队不能改变列表滚动位置", before, songRowBounds(songId))
            assertTrue("列表加歌不能先打开歌曲详情", onMain { app.selected == null })
            assertTrue("应显示真实成功反馈", onMain { app.queueAddMessage.startsWith("已加入队列：") })
            assertEquals("命令绑定当前行歌曲", songId, fixture.commandRequests.last().optString("songId"))
            assertTrue("已点状态应直接出现在行内", nodeLabel(songRow(songId)).contains("已点 ×1"))
            assertSingleVisibleFocus()
            screenshot("song-table-entry-$entry-added").recycle()
        }
        assertEquals("四条路径各发一次 ADD", listOf("ADD", "ADD", "ADD", "ADD"), fixture.actions.toList())
        assertFalse("直接点歌不能请求歌曲详情", fixture.httpEvents.any {
            it.matches(Regex("begin GET /api/v1/tv/library/songs/test-song-\\d+"))
        })
    }

    fun testCategoryConfirmOpensSongsAndReplacesPreviousFilters() {
        onMain {
            app.query = "之前的关键词"
            app.selectedTags = setOf("old-language", "old-genre")
            app.unclassified = true
            app.number = 2
        }
        enterSongTable(2)
        assertEquals("确认分类直接聚焦首首歌曲", "找歌:song:test-song-1", focusedAddress())
        assertTrue("分类结果保留返回来源", onMain { app.categoryResults })
        assertEquals("当前分类替换以前的复合筛选", setOf("tag-language"), onMain { app.selectedTags })
        assertEquals("分类不沿用旧搜索词", "", onMain { app.query })
        assertFalse("普通分类关闭未分类筛选", onMain { app.unclassified })
        assertEquals("新分类从第一页开始", 0, onMain { app.number })
        val request = android.net.Uri.parse(fixture.songRequests.last())
        assertEquals(listOf("tag-language"), request.getQueryParameters("tagIds"))
        assertEquals("", request.getQueryParameter("q"))
        assertEquals("false", request.getQueryParameter("unclassified"))
        assertSingleVisibleFocus()
        screenshot("category-confirm-direct-song-table").recycle()

        key(KeyEvent.KEYCODE_BACK)
        waitFor("结果返回原分类卡片") { onMain { app.page == "分类" } && focusedAddress() == "分类:category:tag-language" }
        assertFalse("不再保留查看歌曲的额外步骤", allNodes().any { it.isClickable && nodeLabel(it).startsWith("查看歌曲") })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("再次确认同一分类仍直接打开歌曲") { focusedAddress() == "找歌:song:test-song-1" && onMain { !app.busy } }
        assertEquals(setOf("tag-language"), onMain { app.selectedTags })
    }

    fun testCategoryReturnRestoresDimensionAndScrolledCard() {
        fixture.extraGenreTags = 23
        onMain { app.navigate("分类") }
        waitFor("多行分类词表加载") { onMain { app.tags.size == 25 && !app.busy && activity.focusMemory.view == "分类" && !activity.focusMemory.restoring } }
        val dimensions = setOf("语种", "年代", "风格", "情绪", "场景", "自定义")
        repeat(4) { if (focusedAddress().removePrefix("分类:action:") !in dimensions) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(5) { if (focusedAddress() != "分类:action:语种") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals("分类:action:语种", focusedAddress())
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("分类:action:风格", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("风格卡片可用") { focusedAddress().startsWith("分类:category:tag-genre") }
        screenshot("category-compact-first-two-rows").recycle()
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val original = focusedAddress()
        assertEquals("须滚到屏外的第五行分类", "分类:category:tag-genre-15", original)
        assertSingleVisibleFocus()
        assertCategoryFocusOutlineComplete("tag-genre-15")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("屏外分类直接进入歌曲") { focusedAddress() == "找歌:song:test-song-1" && onMain { !app.busy } }
        assertEquals(setOf("tag-genre-15"), onMain { app.selectedTags })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("分类歌曲右键查看详情") { onMain { app.selected != null } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情先返回当前分类歌曲行") { onMain { app.selected == null && app.page == "找歌" } && focusedAddress() == "找歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回保留风格维度与屏外分类焦点") { onMain { app.page == "分类" && !app.busy } && focusedAddress() == original }
        assertSingleVisibleFocus()
        assertCategoryFocusOutlineComplete("tag-genre-15")
        screenshot("category-return-scrolled-genre").recycle()

        fixture.songCount = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("空分类直接进入空结果") { onMain { app.page == "找歌" && app.songs.isEmpty() && !app.busy } }
        assertTrue("空分类有结果提示", allNodes().any { nodeLabel(it).contains("没有找到歌曲") })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("空结果仍返回原分类") { focusedAddress() == original }
        repeat(5) { if (focusedAddress() != "分类:action:未分类") key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("分类:action:未分类", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("未分类仍直接打开结果") { onMain { app.page == "找歌" && app.unclassified && app.selectedTags.isEmpty() && !app.busy } }
        assertEquals("true", android.net.Uri.parse(fixture.songRequests.last()).getQueryParameter("unclassified"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("未分类返回原按钮") { focusedAddress() == "分类:action:未分类" }
        assertSingleVisibleFocus()
    }

    fun testSongTableDetailRestoresScrolledRowAndPagination() {
        fixture.songCount = 45
        enterSongTable(0)
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val original = focusedAddress()
        val id = original.substringAfterLast(":song:")
        assertEquals("应滚到第13首歌曲", "test-song-13", id)
        val bounds = songRowBounds(id)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键打开当前行详情") { onMain { app.selected?.optString("id") == id } && focusedAddress().endsWith(":action:加入待唱") }
        assertEquals("查看详情不应点歌", 0, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复屏外来源行") { onMain { app.selected == null } && focusedAddress() == original }
        assertEquals("详情返回保留原滚动位置", bounds, songRowBounds(id))
        assertSingleVisibleFocus()
        repeat(12) { if (focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("最后一行向下可达分页", focusedAddress().endsWith(":action:下一页"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("翻页滚到新页第一行并恢复焦点") { onMain { app.number == 1 } && focusedAddress() == "找歌:song:test-song-21" }
        assertSingleVisibleFocus()
        repeat(24) { if (focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        if (!focusedAddress().endsWith(":action:上一页")) key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("可向左选择上一页", focusedAddress().endsWith(":action:上一页"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("返回上一页仍定位首行") { onMain { app.number == 0 } && focusedAddress() == "找歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertFalse("首行向上可返回搜索控件", focusedAddress().contains(":song:"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("搜索控件向下进入首行") { focusedAddress() == "找歌:song:test-song-1" }
        screenshot("song-table-paged-first-row").recycle()
    }

    fun testSongTablePendingConfirmDoesNotDuplicateAndCompletedAddsCanRepeat() {
        fixture.addDelayMs = 1800
        enterSongTable(0)
        val address = focusedAddress()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals("请求中连续确认仅提交一次", 1, fixture.commandRequests.size)
        assertTrue("当前行显示加入中", nodeLabel(songRow("test-song-1")).contains("加入中"))
        waitFor("延迟 ADD 返回真实成功") { onMain { app.queueAddSongId.isEmpty() && queuedSongCount(app) == 1 } }
        assertEquals("请求完成仍保留当前行", address, focusedAddress())
        fixture.addDelayMs = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("完成后允许有意重复点歌") { onMain { queuedSongCount(app) == 2 } }
        assertEquals(2, fixture.commandRequests.size)
        assertTrue("第二次有意点歌使用新 requestId", fixture.commandRequests[0].optString("requestId") != fixture.commandRequests[1].optString("requestId"))
        assertEquals("第二次使用最新 revision", 2L, fixture.commandRequests[1].optLong("expectedRevision"))
        waitFor("重复点歌状态真实更新") { nodeLabel(songRow("test-song-1")).contains("已点 ×2") }
    }

    fun testSongTableOfflineAndRejectedAddsRestoreSourceFocus() {
        enterSongTable(0)
        val address = focusedAddress()
        onMain { app.online = false }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("离线点歌显示真实错误") { onMain { app.error.contains("NAS 离线") && app.pendingOperation == null } }
        assertEquals("离线不发网络命令", 0, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭离线错误恢复原行") { focusedAddress() == address }
        onMain { app.online = true }
        fixture.rejectNextAdd.set(409)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("明确拒绝不占用未知请求") { onMain { app.error.contains("暂停接单") && app.pendingOperation == null } }
        assertEquals("拒绝不展示成功", "", onMain { app.queueAddMessage })
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭拒绝提示恢复原行") { focusedAddress() == address }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("解除拒绝后可重新点歌") { onMain { queuedSongCount(app) == 1 && app.error.isEmpty() } }
        assertEquals("拒绝加重新尝试共两次请求", 2, fixture.commandRequests.size)
        assertEquals(address, focusedAddress())
        assertSingleVisibleFocus()
    }

    fun testSongTableUnknownAddResultRetriesOriginalRequest() {
        enterSongTable(0)
        val address = focusedAddress()
        fixture.failNextAddResponse.set(503)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("服务端已收下但回报失败保留原请求") { onMain { app.pendingOperation != null && app.error.contains("回报中断") && queuedSongCount(app) == 1 } }
        val requestId = fixture.commandRequests.single().getString("requestId")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示可回到歌曲行") { focusedAddress() == address }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("未知结果阻止新点歌") { onMain { app.error.contains("上次操作结果未知") } }
        assertEquals("未知结果不能创建另一条 ADD", 1, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("可遥控选择原请求重试") { focusedAddress().endsWith(":action:核对后重试") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("原 requestId 重试后关闭提示并恢复原行") { onMain { app.pendingOperation == null && app.error.isEmpty() } && focusedAddress() == address }
        assertEquals("幂等重试不重复入队", 1, onMain { queuedSongCount(app) })
        assertEquals(2, fixture.commandRequests.size)
        assertEquals(requestId, fixture.commandRequests[1].getString("requestId"))
        assertTrue("重试成功后显示确认反馈", onMain { app.queueAddMessage.startsWith("已加入队列：") })
        assertSingleVisibleFocus()
    }

    fun testSongTableNewSearchResetsScrollAndEmptyResultsStayUsable() {
        enterSongTable(0)
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("找歌:song:test-song-13", focusedAddress())
        val version = onMain { app.songListResetVersion }
        onMain { app.query = "测试"; app.search() }
        waitFor("新搜索重置滚动及歌曲焦点") { onMain { app.songListResetVersion > version } && focusedAddress() == "找歌:song:test-song-1" }
        val row = songRowBounds("test-song-1")
        assertTrue("首行应回到表头下方", row.top < 500)
        fixture.songCount = 0
        onMain { app.search() }
        waitFor("空结果不保留已回收行焦点") { onMain { app.songs.isEmpty() && !app.busy } && focusedAddress().contains(":nav:") }
        assertTrue("显示真实空态", allNodes().any { nodeLabel(it).contains("没有找到歌曲") })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse("空结果仍可回到可用搜索控件", focusedAddress().isBlank())
        assertSingleVisibleFocus()
    }

    fun testSongTableOldNasResponseCannotReplaceNewConnectionFeedback() {
        enterSongTable(0)
        fixture.addDelayMs = 1800
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        TvUiFixture().use { next ->
            onMain {
                app.disconnect()
                app.client.base = next.base; app.client.token = "test-next-fixture-device"
                app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot()
                app.page = "找歌"; app.songs = TvUiFixture.songs(); app.total = 20
            }
            waitFor("新 NAS 恢复可用页面且清空旧反馈") { onMain { app.queueAddSongId.isEmpty() && app.queueAddMessage.isEmpty() } && !onMain { activity.focusMemory.restoring } }
            repeat(8) { if (!focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("新 NAS 成功入队") { next.commandRequests.size == 1 && onMain { queuedSongCount(app) == 1 && app.queueAddMessage.isNotBlank() } }
            val message = onMain { app.queueAddMessage }
            waitFor("旧 NAS 延迟请求完成") { fixture.actions.contains("ADD") && onMain { !app.busy } }
            assertEquals("旧结果不能覆盖新队列", 1, onMain { queuedSongCount(app) })
            assertEquals("旧结果不能抹掉新成功提示", message, onMain { app.queueAddMessage })
            assertEquals("旧连接错误不能覆盖新页面", "", onMain { app.error })
        }
    }

    fun testSongTableOldSessionResponseCannotAffectNewSession() {
        enterSongTable(0)
        fixture.addDelayMs = 1600
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        fixture.sessionId = "ui-test-next-session"
        onMain { app.newSession() }
        waitFor("新聚会清除旧点歌占用与反馈") { onMain {
            app.snapshot.optString("id") == fixture.sessionId && app.pendingOperation == null && app.queueAddSongId.isEmpty() && app.queueAddMessage.isEmpty()
        } }
        fixture.addDelayMs = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("新聚会可独立点歌") { onMain { queuedSongCount(app) == 1 && app.queueAddMessage.isNotBlank() } }
        val message = onMain { app.queueAddMessage }
        waitFor("旧聚会迟到拒绝已返回") { onMain { !app.busy } }
        assertEquals("旧聚会错误不能进入新页面", "", onMain { app.error })
        assertEquals("新聚会队列保持一次入队", 1, onMain { queuedSongCount(app) })
        assertEquals(message, onMain { app.queueAddMessage })
    }

    fun testSongTableMetadataAndFocusRemainReadable() {
        fixture.mixedSongMetadata = true
        enterSongTable(0)
        val first = onMain { app.songs.first() }
        assertTrue("fixture 包含长中英文歌名", first.optString("title").contains("My Lovely Girl"))
        val row = songRowBounds("test-song-1")
        val artist = allNodes().first { it.text?.toString() == first.artists() }
        val artistBounds = Rect().also { artist.getBoundsInScreen(it) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * instrumentation.targetContext.resources.displayMetrics.scaledDensity }
        val glyphs = Rect().also { textPaint.getTextBounds(first.artists(), 0, first.artists().length, it) }
        assertTrue("歌手字形完整容纳在行内", artistBounds.height() >= glyphs.height() && row.contains(artistBounds))
        val image = screenshot("song-table-long-and-missing-metadata")
        assertTrue("聚焦歌曲行采用深色底", colorPixels(image, 44, 44, 46, row) > row.width() * row.height() / 2)
        val lowerHalf = Rect(artistBounds.left, artistBounds.centerY(), artistBounds.right, artistBounds.bottom)
        assertTrue("歌手下半部真实绘制", colorPixels(image, 212, 212, 214, lowerHalf) > 3)
        image.recycle()
        val missing = nodeLabel(songRow("test-song-2"))
        assertTrue("缺少时长显示占位符", missing.contains("—"))
        assertTrue("缺少版本不猜测", missing.contains("版本待确认"))
        assertFalse("无封面行不伪造封面图", missing.contains("歌曲封面"))
        assertSingleVisibleFocus()
    }

    private fun waitForHomeSections() {
        waitFor("首页三个区域通过真实 HTTP 加载完成") { onMain {
            app.favoritesReady && app.homeHistoryReady && app.homePopularReady &&
                !app.favoritesLoading && !app.homeHistoryLoading && !app.homePopularLoading &&
                app.favoriteSongs().size == 4 && app.homeHistorySongs.size == 3 && app.homePopularSongs.size == 4
        } }
    }

    private fun focusHomeTarget(key: String) {
        waitFor("首页入口注册：$key") { onMain { activity.focusMemory.targets.containsKey("首页:$key") } }
        onMain { activity.focusMemory.targets["首页:$key"]?.requestFocus() }
        waitFor("首页入口持有真实焦点：$key") { focusedAddress() == "首页:$key" }
    }

    private fun focusedAddressCondition(address: String): () -> Boolean = { focusedAddress() == address }

    private fun homeSongBounds(number: Int): Rect {
        val title = "测试歌曲 ${number.toString().padStart(2, '0')}"
        val node = allNodes().filter { it.isVisibleToUser && it.isClickable && nodeLabel(it).contains(title) }
            .minByOrNull { candidate -> Rect().also { candidate.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        assertNotNull("首屏曲目可见且可确认：$title", node)
        return Rect().also { node!!.getBoundsInScreen(it) }
    }

    private fun enterSongTable(entry: Int) {
        onMain { app.navigate("点歌") }
        waitFor("点歌中心入口就绪") { onMain { app.page == "点歌" && activity.focusMemory.view == "点歌" && !activity.focusMemory.restoring } && focusedAddress().startsWith("点歌:") }
        if (!focusedAddress().startsWith("点歌:quick:")) key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(3) { if (focusedAddress() != "点歌:quick:0") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(entry) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("选择第${entry + 1}个真实点歌入口", "点歌:quick:$entry", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        when (entry) {
            0 -> waitFor("读取搜索歌曲") { onMain { app.page == "找歌" && app.songs.isNotEmpty() && app.total == fixture.songCount.toLong() } }
            1 -> {
                waitFor("读取歌手") { onMain { app.page == "歌手" && app.artists.isNotEmpty() } }
                repeat(6) { if (!focusedAddress().startsWith("歌手:artist:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("读取歌手歌曲") { onMain { app.artistDetail != null && app.songs.isNotEmpty() } }
            }
            2 -> {
                waitFor("读取分类") { onMain { app.page == "分类" && app.tags.isNotEmpty() } }
                val dimensions = setOf("语种", "年代", "风格", "情绪", "场景", "自定义")
                repeat(6) { if (focusedAddress().removePrefix("分类:action:") !in dimensions) key(KeyEvent.KEYCODE_DPAD_DOWN) }
                repeat(5) { if (focusedAddress() != "分类:action:语种") key(KeyEvent.KEYCODE_DPAD_LEFT) }
                repeat(6) { if (!focusedAddress().startsWith("分类:category:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("确认分类后直接读取歌曲") { onMain { app.page == "找歌" && app.selectedTags == setOf("tag-language") && app.songs.isNotEmpty() && !app.busy } && focusedAddress() == "找歌:song:test-song-1" }
            }
            3 -> {
                waitFor("读取歌单") { onMain { app.page == "歌单" && app.playlists.isNotEmpty() } }
                repeat(6) { if (!focusedAddress().startsWith("歌单:playlist:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("读取歌单歌曲") { onMain { app.playlistDetail != null && app.songs.isNotEmpty() } }
            }
        }
        waitFor("歌曲表格请求及焦点恢复完成") { onMain {
            val expected = when {
                app.playlistDetail != null -> "${app.page}:playlist:${app.playlistDetail!!.optString("id")}"
                app.artistDetail != null -> "${app.page}:artist:${app.artistDetail!!.optString("id")}"
                else -> app.page
            }
            !app.busy && app.songs.isNotEmpty() && activity.focusMemory.view == expected && !activity.focusMemory.restoring
        } }
        repeat(8) { if (!focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("遥控进入歌曲表格") { focusedAddress().contains(":song:") && onMain { app.selected == null } }
    }

    private fun assertCategoryFocusOutlineComplete(id: String) {
        val title = onMain { app.tags.first { it.optString("id") == id }.optString("name") }
        val node = allNodes().first { it.isClickable && nodeLabel(it).contains(title) }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val frame = screenshot("category-focus-outline-${keyHistory.size}")
        val lowerEdge = Rect(bounds.left + bounds.width() / 3, bounds.bottom - 20,
            bounds.right - bounds.width() / 3, minOf(frame.height, bounds.bottom + 20))
        // tvFocus draws Paper at .95 alpha over the dark card, rather than opaque Paper.
        assertTrue("分类焦点框底边不能被滚动视口裁切", colorPixels(frame, 234, 234, 235, lowerEdge) > bounds.width() / 4)
        frame.recycle()
    }

    private fun songRow(id: String): AccessibilityNodeInfo {
        val title = onMain { app.songs.first { it.optString("id") == id }.optString("title") }
        return allNodes().first { it.isClickable && nodeLabel(it).contains(title) }
    }

    private fun songRowBounds(id: String) = Rect().also { songRow(id).getBoundsInScreen(it) }

    private fun key(code: Int) {
        val before = focusDetails()
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(230) // Wait for the production focus/scroll animation to settle.
        keyHistory.add("${KeyEvent.keyCodeToString(code)}: $before -> ${focusDetails()}")
        File(output, "${name}-keys.txt").writeText(keyHistory.joinToString("\n"))
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(70)
        }
        fail("超时：$description；当前焦点=${focusedLabel()}；页面=${onMain { app.page }}")
    }

    private fun clearAccessibilityCache() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun focusedNode(): AccessibilityNodeInfo? {
        clearAccessibilityCache()
        return instrumentation.uiAutomation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun visibleCoverImages() = allNodes().filter {
        it.isVisibleToUser && it.childCount == 0 && it.contentDescription?.toString() == "歌曲封面"
    }
    private fun focusedAddress(): String = onMain { activity.focusMemory.focusedTargets.singleOrNull().orEmpty() }

    /** Observe actual Compose callbacks; Accessibility focus is stale on some TV system images. */
    private fun focusedLabel(): String {
        val address = focusedAddress()
        return when {
            address.contains(":action:") -> address.substringAfterLast(":action:")
            address.contains(":input:") -> "<编辑器> ${address.substringAfterLast(":input:")}"
            address.contains(":nav:") -> address.substringAfterLast(":nav:")
            address.contains(":quick:") -> listOf("搜索歌曲", "歌手点歌", "分类点歌", "我的歌单")[address.substringAfterLast(":quick:").toInt()]
            address.endsWith(":bar:queue") -> "已点歌曲"
            address.endsWith(":bar:start") -> "确认开唱"
            address.contains(":song:") || address.contains(":favorite:") || address.contains(":history:") || address.contains(":popular:") -> onMain {
                (app.songs + app.favoriteSongs() + app.homeHistorySongs + app.homePopularSongs).firstOrNull { it.optString("id") == address.substringAfterLast(':') }?.optString("title")
            }.orEmpty()
            else -> address
        }
    }
    private fun stateDescription(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty()
        else node.extras.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY")?.toString().orEmpty()

    private fun focusDetails(): String = focusedNode()?.let { node ->
        val rect = Rect(); node.getBoundsInScreen(rect)
        "actualTargets=${onMain { activity.focusMemory.focusedTargets.toList() }} history=${onMain { activity.focusMemory.last.toMap() }} a11yLabel=${nodeLabel(node)} bounds=$rect selected=${node.isSelected} state=${stateDescription(node)} focusable=${node.isFocusable} clickable=${node.isClickable}"
    } ?: "actualTargets=${onMain { activity.focusMemory.focusedTargets.toList() }} history=${onMain { activity.focusMemory.last.toMap() }} <a11y焦点缺失>"

    private fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        if (!node.text.isNullOrBlank()) append(node.text).append(' ')
        if (!node.contentDescription.isNullOrBlank()) append(node.contentDescription).append(' ')
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(nodeLabel(it)) }
    }.trim()

    private fun allNodes(): List<AccessibilityNodeInfo> {
        clearAccessibilityCache()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return nodes
    }

    private fun assertSingleVisibleFocus() {
        assertEquals("不允许焦点丢失或多个控件同时持有真实Compose焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        val expected = focusedAddress()
        val deadline = SystemClock.uptimeMillis() + 8000
        var settled: Bitmap? = null
        var capturedPendingFrame = false
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals("等待白色焦点框绘制期间遥控焦点不能改变", expected, focusedAddress())
            val frame = instrumentation.uiAutomation.takeScreenshot() ?: error("焦点可见性检查需要屏幕截图")
            if (colorPixels(frame, 245, 245, 247) > 100) {
                settled = frame
                break
            }
            if (!capturedPendingFrame) {
                File(output, "$name-focus-pending-${keyHistory.size}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                capturedPendingFrame = true
            }
            frame.recycle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(70)
        }
        assertNotNull("实际焦点白色焦点框必须在8秒内绘制到可见屏幕：$expected", settled)
        val bitmap = settled!!
        assertEquals("绘制后仍只能有一个真实焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        assertEquals("绘制后的焦点目标保持一致", expected, focusedAddress())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val boundary = pixels.indices.any { index ->
            val x = index % bitmap.width; val y = index / bitmap.width
            (x <= 1 || y <= 1 || x >= bitmap.width - 2 || y >= bitmap.height - 2) && isColor(pixels[index], 245, 245, 247)
        }
        assertFalse("白色焦点框不能被屏幕边缘裁切", boundary)
        bitmap.recycle()
    }

    private fun navigationBounds(label: String): Rect {
        val node = allNodes().firstOrNull { it.isClickable && nodeLabel(it) == label }
        assertNotNull("导航可访问文本：$label", node)
        return Rect().also { node!!.getBoundsInScreen(it) }
    }

    private fun screenshot(label: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("模拟器应允许截图", bitmap)
        File(output, "$label.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap!!
    }

    private fun colorPixels(bitmap: Bitmap, red: Int, green: Int, blue: Int, region: Rect? = null): Int {
        val area = region ?: Rect(0, 0, bitmap.width, bitmap.height)
        val pixels = IntArray(area.width() * area.height())
        bitmap.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
        return pixels.count { isColor(it, red, green, blue) }
    }

    private fun isColor(pixel: Int, red: Int, green: Int, blue: Int) =
        kotlin.math.abs(Color.red(pixel) - red) < 12 && kotlin.math.abs(Color.green(pixel) - green) < 12 && kotlin.math.abs(Color.blue(pixel) - blue) < 12

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
