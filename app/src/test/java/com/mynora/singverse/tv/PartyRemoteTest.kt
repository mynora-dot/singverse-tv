package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class PartyRemoteTest {
    @Test fun reactionsNeverReplayAcrossReconnectSongChangesAndDisabledEffects() {
        val cursor = PartyReactionCursor()
        val e = PartyReaction(1,"LIKE","小明","a",3000)
        assertTrue(cursor.accept("s","a",1,listOf(e),1000,true).isEmpty())
        val next = e.copy(cursor=2)
        assertEquals(listOf(next),cursor.accept("s","a",2,listOf(e,next,next),1000,true))
        assertTrue(cursor.accept("s","a",2,listOf(next),1000,true).isEmpty())
        assertTrue(cursor.accept("s","a",3,listOf(e.copy(cursor=3)),1000,false).isEmpty())
        assertTrue(cursor.accept("s","a",3,listOf(e.copy(cursor=3)),1000,true).isEmpty())
        assertTrue(cursor.accept("s","a",4,listOf(e.copy(cursor=4)),4000,true).isEmpty())
        assertTrue(cursor.accept("s","b",5,listOf(e.copy(cursor=5)),1000,true).isEmpty())
        cursor.reset()
        assertTrue(cursor.accept("s","b",6,listOf(e.copy(cursor=6,attemptId="b")),1000,true).isEmpty())
    }
    @Test fun lostAcknowledgmentsKeepSuccessfulAndFailedExecutionResults() {
        val results=RemoteCommandResults()
        assertNull(results.result("seek"))
        results.record("seek",true); results.record("roles",false)
        assertEquals(true,results.result("seek"));assertEquals(false,results.result("roles"))
        results.clear();assertNull(results.result("seek"))
    }
}
