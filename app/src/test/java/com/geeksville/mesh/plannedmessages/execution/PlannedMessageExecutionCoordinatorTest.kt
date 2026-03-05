package com.geeksville.mesh.plannedmessages.execution

import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageExecutionStore
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedMessageExecutionCoordinatorTest {

    @Test
    fun `mesh service down applies backoff and does not crash`() = runBlocking {
        val fakeStore = FakeExecutionStore()
        val now = 1_700_000_000_000L
        val coordinator = PlannedMessageExecutionCoordinator(
            executionStore = fakeStore,
            schedulerEngine = SchedulerEngine(),
            nowProvider = { now },
        )
        val message = dueOneShotMessage(
            id = 1L,
            dueAtUtcMs = now - 5_000L,
            attemptCount = 0,
        )

        val result = coordinator.execute(
            claimedMessages = listOf(message),
            settings = PlannedMessageSettings(),
            sender = null,
        )

        assertEquals(0, result.sentCount)
        assertEquals(1, result.failedCount)
        assertEquals(0, result.skippedCount)
        assertEquals("mesh_service_unavailable", result.lastErrorReason)
        assertEquals(1, fakeStore.failCalls.size)
        assertEquals(now + 60_000L, fakeStore.failCalls.first().nextTriggerAtUtcEpochMs)
    }

    @Test
    fun `successful send updates last fired and clears attempts`() = runBlocking {
        val fakeStore = FakeExecutionStore()
        val now = 1_700_000_000_000L
        val coordinator = PlannedMessageExecutionCoordinator(
            executionStore = fakeStore,
            schedulerEngine = SchedulerEngine(),
            nowProvider = { now },
        )
        val message = dueOneShotMessage(
            id = 10L,
            dueAtUtcMs = now - 5_000L,
            attemptCount = 3,
        )
        val sender = object : IMeshSender {
            override suspend fun send(message: PlannedMessageEntity): Boolean = true
        }

        val result = coordinator.execute(
            claimedMessages = listOf(message),
            settings = PlannedMessageSettings(),
            sender = sender,
        )

        assertEquals(1, result.sentCount)
        assertEquals(0, result.failedCount)
        assertEquals(0, result.skippedCount)
        assertTrue(fakeStore.failCalls.isEmpty())
        assertEquals(1, fakeStore.finalizeCalls.size)
        val finalize = fakeStore.finalizeCalls.first()
        assertEquals(now, finalize.lastFiredAtUtcEpochMs)
        assertEquals(0, finalize.attemptCountSinceLastFire)
        assertEquals(null, finalize.resolvedMessage.nextTriggerAtUtcEpochMs)
        assertEquals(false, finalize.resolvedMessage.isEnabled)
    }

    private fun dueOneShotMessage(id: Long, dueAtUtcMs: Long, attemptCount: Int): PlannedMessageEntity {
        return PlannedMessageEntity(
            id = id,
            destinationKey = "dest",
            messageText = "hello",
            scheduleType = PlannedMessageScheduleType.ONE_SHOT,
            daysOfWeekMask = 0,
            hourOfDay = 0,
            minuteOfHour = 0,
            oneShotAtUtcEpochMs = dueAtUtcMs,
            timezoneId = "UTC",
            deliveryPolicy = PlannedMessageDeliveryPolicy.CATCH_UP,
            isEnabled = true,
            nextTriggerAtUtcEpochMs = dueAtUtcMs,
            inFlightUntilUtcEpochMs = dueAtUtcMs + 60_000L,
            lastAttemptedAtUtcEpochMs = dueAtUtcMs - 1_000L,
            attemptCountSinceLastFire = attemptCount,
            createdAtUtcEpochMs = dueAtUtcMs - 60_000L,
            updatedAtUtcEpochMs = dueAtUtcMs - 60_000L,
        )
    }

    private class FakeExecutionStore : PlannedMessageExecutionStore {
        val finalizeCalls = mutableListOf<FinalizeCall>()
        val failCalls = mutableListOf<FailCall>()

        override suspend fun finalizeClaimedMessage(
            claimedMessage: PlannedMessageEntity,
            resolvedMessage: PlannedMessageEntity,
            lastFiredAtUtcEpochMs: Long?,
            attemptCountSinceLastFire: Int,
            updatedAtUtcEpochMs: Long,
        ): Boolean {
            finalizeCalls += FinalizeCall(
                claimedMessage = claimedMessage,
                resolvedMessage = resolvedMessage,
                lastFiredAtUtcEpochMs = lastFiredAtUtcEpochMs,
                attemptCountSinceLastFire = attemptCountSinceLastFire,
                updatedAtUtcEpochMs = updatedAtUtcEpochMs,
            )
            return true
        }

        override suspend fun failClaimedMessage(
            claimedMessage: PlannedMessageEntity,
            nextTriggerAtUtcEpochMs: Long,
            attemptCountSinceLastFire: Int,
            updatedAtUtcEpochMs: Long,
        ): Boolean {
            failCalls += FailCall(
                claimedMessage = claimedMessage,
                nextTriggerAtUtcEpochMs = nextTriggerAtUtcEpochMs,
                attemptCountSinceLastFire = attemptCountSinceLastFire,
                updatedAtUtcEpochMs = updatedAtUtcEpochMs,
            )
            return true
        }
    }

    private data class FinalizeCall(
        val claimedMessage: PlannedMessageEntity,
        val resolvedMessage: PlannedMessageEntity,
        val lastFiredAtUtcEpochMs: Long?,
        val attemptCountSinceLastFire: Int,
        val updatedAtUtcEpochMs: Long,
    )

    private data class FailCall(
        val claimedMessage: PlannedMessageEntity,
        val nextTriggerAtUtcEpochMs: Long,
        val attemptCountSinceLastFire: Int,
        val updatedAtUtcEpochMs: Long,
    )
}
