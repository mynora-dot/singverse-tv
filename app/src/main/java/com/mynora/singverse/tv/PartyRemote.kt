package com.mynora.singverse.tv

internal data class PartyReaction(val cursor: Long, val kind: String, val ownerName: String, val attemptId: String, val expiresAt: Long)

/** Keep execution results until the server acknowledges them. A lost HTTP response must not seek twice. */
internal class RemoteCommandResults {
    private val results = linkedMapOf<String, Boolean>()
    fun result(id: String): Boolean? = results[id]
    fun record(id: String, success: Boolean) { results[id] = success; while (results.size > 128) results.remove(results.keys.first()) }
    fun forget(id: String) { results.remove(id) }
    fun clear() = results.clear()
}

/** The first snapshot after a connection or song change is a baseline, never a replay. */
internal class PartyReactionCursor {
    private var session = ""
    private var attempt = ""
    private var cursor = -1L
    fun reset() { session = ""; attempt = ""; cursor = -1 }
    fun accept(sessionId: String, attemptId: String, latest: Long, events: List<PartyReaction>, now: Long, enabled: Boolean): List<PartyReaction> {
        if (session != sessionId || attempt != attemptId || cursor < 0) {
            session = sessionId; attempt = attemptId; cursor = latest
            return emptyList()
        }
        val previous = cursor
        cursor = maxOf(cursor, latest)
        if (!enabled) return emptyList()
        return events.filter { it.cursor > previous && it.cursor <= latest && it.attemptId == attemptId && it.expiresAt > now && it.kind in listOf("LIKE", "CLAP", "CHEER") }.distinctBy { it.cursor }
    }
}
