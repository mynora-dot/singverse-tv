@file:Suppress("DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File

/** Opt-in: an actual temporary Go service, actual ExoPlayer and production Compose. */
class TvPartyGoIntegrationTest : InstrumentationTestCase() {
    fun testRealGoParty() {
        val args=(instrumentation as TvUiTestRunner).launchArguments
        val origin=args.getString("partyOrigin") ?: return
        val token=args.getString("partyDeviceToken") ?: return
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("party-go-origin",origin);putExtra("party-go-token",token)
        }) as TvPartyFixtureActivity
        val output=File(instrumentation.targetContext.getExternalFilesDir(null),"tv-party-checks").apply {mkdirs()}
        File(output,"finished").delete()
        var seenReaction=-1L
        val deadline=SystemClock.elapsedRealtime()+12*60*1000
        try {
            while(SystemClock.elapsedRealtime()<deadline) {
                val state=onMain { val app=activity.app
                    if(File(output,"effects-off").exists())app.setPartyEffects(false)
                    JSONObject().put("connected",app.connected).put("online",app.online).put("playerState",app.playerState)
                        .put("attemptId",app.snapshot.optJSONObject("playback")?.optString("attemptId"))
                        .put("queuePolicy",app.snapshot.optString("queuePolicy")).put("positionMs",app.position)
                        .put("playing",app.player.isPlaying).put("audioRole",app.activeAudioRole).put("error",app.error)
                        .put("effectsEnabled",app.partyEffectsEnabled).put("reactionCount",app.partyReactions.size)
                }
                File(output,"state.json").writeText(state.toString())
                val reaction=onMain {activity.app.partyReactions.lastOrNull()?.cursor ?: -1L}
                if(reaction>seenReaction){seenReaction=reaction;SystemClock.sleep(350);screenshot(File(output,"reaction-$reaction.png"))}
                if(File(output,"finished").exists()) {assertTrue("TV connected",state.optBoolean("connected"));return}
                SystemClock.sleep(150)
            }
            fail("Go party integration timeout")
        } finally {screenshot(File(output,"final.png"));onMain{activity.finish()}}
    }
    private fun screenshot(file:File) {instrumentation.uiAutomation.takeScreenshot()?.let {image -> file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}}
    private fun <T> onMain(block:()->T):T {var result:Result<T>?=null;instrumentation.runOnMainSync {result=runCatching(block)};return result!!.getOrThrow()}
}
