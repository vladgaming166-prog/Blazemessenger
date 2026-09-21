package ro.blazemessenger.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test
    fun usernameAcceptsOrdinaryNames() {
        val result = UsernamePolicy.validate("Ada_Lovelace")
        assertTrue(result is UsernameResult.Ok)
        assertEquals("ada_lovelace", (result as UsernameResult.Ok).lower)
    }

    @Test
    fun usernameRejectsShortReservedAndSpaces() {
        assertTrue(UsernamePolicy.validate("ab") is UsernameResult.Invalid)
        assertTrue(UsernamePolicy.validate("cool name") is UsernameResult.Invalid)
        assertTrue(UsernamePolicy.validate("admin") is UsernameResult.Reserved)
    }

    @Test
    fun messageTextAndEditWindow() {
        assertFalse(MessagePolicy.canSendText("   "))
        assertTrue(MessagePolicy.canSendText("hello"))
        assertFalse(MessagePolicy.canSendText("a".repeat(4001)))
        val created = 1_000_000L
        assertTrue(MessagePolicy.canEdit(created, created + 60_000, senderIsSelf = true, deleted = false))
        assertFalse(MessagePolicy.canEdit(created, created + MessagePolicy.EDIT_WINDOW_MS + 1, true, false))
        assertFalse(MessagePolicy.canEdit(created, created + 1000, senderIsSelf = false, deleted = false))
    }

    @Test
    fun burstLimitCountsRecentSendsOnly() {
        val now = 20_000L
        val times = List(19) { now - 1_000L } + listOf(now - 30_000L)
        assertTrue(MessagePolicy.allowSend(times, now))
        val blocked = List(20) { now - 500L }
        assertFalse(MessagePolicy.allowSend(blocked, now))
    }

    @Test
    fun filePolicyAcceptsPdfAndRejectsOversizeVideo() {
        assertTrue(FilePolicy.check("application/pdf", 1024) is FileCheck.Ok)
        assertTrue(FilePolicy.check("application/x-msdownload", 100) is FileCheck.Unsupported)
        val tooBig = FilePolicy.check("video/mp4", FilePolicy.MAX_VIDEO + 1)
        assertTrue(tooBig is FileCheck.TooLarge)
    }

    @Test
    fun callTransitionsAreOneWay() {
        assertEquals(CallStates.ACCEPTED, CallStates.transition(CallStates.RINGING, CallStates.ACCEPTED))
        assertEquals(CallStates.MISSED, CallStates.transition(CallStates.RINGING, CallStates.MISSED))
        assertEquals(CallStates.ENDED, CallStates.transition(CallStates.ACCEPTED, CallStates.ENDED))
        assertNull(CallStates.transition(CallStates.ACCEPTED, CallStates.DECLINED))
        assertNull(CallStates.transition(CallStates.ENDED, CallStates.ACCEPTED))
        assertNull(CallStates.transition(CallStates.DECLINED, CallStates.ENDED))
        assertTrue(CallStates.isTerminal(CallStates.BUSY))
    }

    @Test
    fun directChatIdIsStableRegardlessOfOrder() {
        assertEquals(DirectChatIds.of("b", "a"), DirectChatIds.of("a", "b"))
        assertTrue(DirectChatIds.of("a", "b").startsWith("d_"))
    }

    @Test
    fun mentionsExtractUniqueNames() {
        assertEquals(listOf("ada", "all"), MentionParser.mentions("hi @Ada and @all @ada"))
        assertTrue(MentionParser.mentionsAll("@all please look"))
    }
}
