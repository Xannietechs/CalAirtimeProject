
package org.jetbrains.amper.ktor

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MessageIdManager(
    private val stateFile: File = File("calairtime-sequence.txt")
) {
    val requestMutex = Mutex()
    private var checkedOwner: String? = null
    private var checkedMeter: String? = null
    private var checkedAmount: Double? = null
    private var vendClaimed = false
    private var lastSequence: Int
    private var lastDateTime: Instant?
    private var pendingConsumerCheck: MessageIdValue?
    private var consumerCheckConfirmed: Boolean

    private val formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)

    init {
        val saved = loadState() ?: error("Sequence state missing. Ask the administrator for the last accepted sequence, then create the state file before starting.")

        lastSequence = saved.sequence
        lastDateTime = saved.dateTime
        pendingConsumerCheck = saved.pending
        consumerCheckConfirmed = saved.confirmed
        if (stateFile.exists()) {
            val values = stateFile.readLines().mapNotNull { line ->
                val i = line.indexOf('='); if (i < 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            checkedOwner = values["checkedOwner"]?.takeIf { it.isNotBlank() }
            checkedMeter = values["checkedMeter"]
            checkedAmount = values["checkedAmount"]?.toDoubleOrNull()
            vendClaimed = values["vendClaimed"] == "true"
        }
    }

    @Synchronized
    fun next(): MessageIdValue {
        check(pendingConsumerCheck == null) {
            "An electricity consumer-check is unresolved."
        }

        val messageId = createNextMessageId()
        saveState()
        return messageId
    }

    @Synchronized
    fun startConsumerCheck(meter: String? = null, amount: Double? = null, owner: String? = null): MessageIdValue {
        check(pendingConsumerCheck == null) {
            "An electricity consumer-check is already unresolved."
        }

        checkedOwner = owner
        checkedMeter = meter
        checkedAmount = amount
        vendClaimed = false
        pendingConsumerCheck = createNextMessageId()
        consumerCheckConfirmed = false
        saveState()

        return pendingConsumerCheck!!
    }

    @Synchronized
    fun markConsumerCheckSuccessful() {
        check(pendingConsumerCheck != null) {
            "No consumer-check is pending."
        }

        consumerCheckConfirmed = true
        saveState()
    }

    @Synchronized
    fun successfulConsumerCheck(): MessageIdValue {
        check(consumerCheckConfirmed) {
            "Token-vend requires a confirmed successful consumer-check."
        }

        return pendingConsumerCheck
            ?: throw IllegalStateException(
                "No consumer-check message ID is available."
            )
    }

    @Synchronized
    fun clearConsumerCheck() {
        checkedOwner = null
        checkedMeter = null
        checkedAmount = null
        vendClaimed = false
        pendingConsumerCheck = null
        consumerCheckConfirmed = false
        saveState()
    }

    @Synchronized
    fun claimVend(meter: String, amount: Double, owner: String? = null): MessageIdValue {
        check(checkedOwner == owner) { "This electricity check belongs to another operator." }
        val id = successfulConsumerCheck()
        check(!vendClaimed) { "Vend already submitted. Reconcile with the administrator before another purchase." }
        check(checkedMeter == meter && checkedAmount == amount) { "Meter and amount must match the successful consumer-check." }
        val checkTime = java.time.LocalDateTime.parse(id.dateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC)
        check(java.time.Duration.between(checkTime, Instant.now()).abs().toHours() < 24) { "Consumer-check expired. Contact the administrator to reconcile the pending request." }
        vendClaimed = true
        saveState()
        return id
    }

    private fun createNextMessageId(): MessageIdValue {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val nextTime =
            if (lastDateTime == null || now.isAfter(lastDateTime)) {
                now
            } else {
                lastDateTime!!.plusSeconds(1)
            }

        val messageId = MessageIdValue(
            dateTime = formatter.format(nextTime),
            seqNumber = lastSequence + 1
        )

        lastSequence = messageId.seqNumber
        lastDateTime = nextTime.truncatedTo(ChronoUnit.SECONDS)
        return messageId
    }

    private data class SavedState(
        val sequence: Int,
        val dateTime: Instant?,
        val pending: MessageIdValue?,
        val confirmed: Boolean
    )

    private fun loadState(): SavedState? {
        if (!stateFile.exists()) return null

        return try {
            val values = stateFile.readLines()
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index < 0) null
                    else line.substring(0, index) to line.substring(index + 1)
                }
                .toMap()

            val sequence = values["seq"]?.toIntOrNull()
                ?: error("Sequence state is missing a valid seq value.")

            require(sequence >= 0) { "Sequence must not be negative." }
            val dateTime = values["dateTime"]
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }

            val pendingSequence = values["pendingSeq"]?.toIntOrNull()
            val pendingDateTime = values["pendingDateTime"]
                ?.takeIf { it.isNotBlank() }

            val pending = if (
                pendingSequence != null && pendingDateTime != null
            ) {
                MessageIdValue(pendingDateTime, pendingSequence)
            } else {
                null
            }

            SavedState(
                sequence = sequence,
                dateTime = dateTime,
                pending = pending,
                confirmed = values["consumerCheckConfirmed"] == "true"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read sequence state; restore it before sending requests.", e)
        }
    }

    private fun saveState() {
        val temporary = File(stateFile.absolutePath + ".tmp")
        temporary.writeText(
            buildString {
                appendLine("checkedOwner=${checkedOwner ?: ""}")
                appendLine("checkedMeter=${checkedMeter ?: ""}")
                appendLine("checkedAmount=${checkedAmount ?: ""}")
                appendLine("vendClaimed=$vendClaimed")
                appendLine("seq=$lastSequence")
                appendLine("dateTime=${lastDateTime ?: ""}")
                appendLine("pendingSeq=${pendingConsumerCheck?.seqNumber ?: ""}")
                appendLine("pendingDateTime=${pendingConsumerCheck?.dateTime ?: ""}")
                appendLine("consumerCheckConfirmed=$consumerCheckConfirmed")
            }
        )
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

data class MessageIdValue(
    val dateTime: String,
    val seqNumber: Int
)



