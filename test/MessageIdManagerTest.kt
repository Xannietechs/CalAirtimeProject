package org.jetbrains.amper.ktor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageIdManagerTest {

    private fun createManager(): Pair<MessageIdManager, File> {
        val file = File.createTempFile("calairtime-test-", ".txt")
        file.writeText("seq=11\ndateTime=\n")
        return MessageIdManager(file) to file
    }

    @Test
    fun nextMessageIncrementsSequence() {
        val (manager, file) = createManager()
        try {
            assertEquals(12, manager.next().seqNumber)
            assertEquals(13, manager.next().seqNumber)
        } finally {
            file.delete()
        }
    }

    @Test
    fun consumerCheckAndTokenVendReuseSameMessageId() {
        val (manager, file) = createManager()
        try {
            val checkId = manager.startConsumerCheck()

            assertFailsWith<IllegalStateException> {
                manager.successfulConsumerCheck()
            }

            manager.markConsumerCheckSuccessful()
            assertEquals(checkId, manager.successfulConsumerCheck())

            manager.clearConsumerCheck()
            assertEquals(13, manager.next().seqNumber)
        } finally {
            file.delete()
        }
    }

    @Test
    fun unresolvedConsumerCheckBlocksNewIds() {
        val (manager, file) = createManager()
        try {
            manager.startConsumerCheck()

            assertFailsWith<IllegalStateException> {
                manager.next()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun pendingCheckSurvivesRestart() {
        val (manager, file) = createManager()
        try {
            val checkId = manager.startConsumerCheck()
            val restarted = MessageIdManager(file)

            restarted.markConsumerCheckSuccessful()
            assertEquals(checkId, restarted.successfulConsumerCheck())
        } finally {
            file.delete()
        }
    }
    @Test
    fun rapidRequestsHaveStrictlyLaterWireTimestamps() {
        val (manager, file) = createManager()
        try {
            val times = (1..20).map { manager.next().dateTime }
            assertTrue(times.zipWithNext().all { (first, second) -> second > first })
        } finally { file.delete() }
    }

    @Test
    fun vendIsBoundToPaymentAndClaimSurvivesRestart() {
        val (manager, file) = createManager()
        try {
            val id = manager.startConsumerCheck("00000000000", 100.0)
            manager.markConsumerCheckSuccessful()
            assertFailsWith<IllegalStateException> { manager.claimVend("11111111111", 100.0) }
            assertFailsWith<IllegalStateException> { manager.claimVend("00000000000", 50.0) }
            assertEquals(id, manager.claimVend("00000000000", 100.0))
            val restarted = MessageIdManager(file)
            assertFailsWith<IllegalStateException> { restarted.claimVend("00000000000", 100.0) }
        } finally { file.delete() }
    }

    @Test
    fun corruptStateFailsInsteadOfResettingSequence() {
        val file = File.createTempFile("calairtime-corrupt-", ".txt")
        try {
            file.writeText("seq=broken")
            assertFailsWith<IllegalStateException> { MessageIdManager(file) }
        } finally { file.delete() }
    }
    @Test
    fun electricityOwnerIsEnforcedAfterRestart() {
        val (manager, file) = createManager()
        try {
            manager.startConsumerCheck("00000000000", 100.0, "alice")
            manager.markConsumerCheckSuccessful()
            val restarted = MessageIdManager(file)
            assertFailsWith<IllegalStateException> { restarted.claimVend("00000000000", 100.0, "bob") }
            assertEquals(manager.successfulConsumerCheck(), restarted.claimVend("00000000000", 100.0, "alice"))
        } finally { file.delete() }
    }
}


