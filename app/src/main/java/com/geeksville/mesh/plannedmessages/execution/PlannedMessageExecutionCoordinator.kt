package com.geeksville.mesh.plannedmessages.execution

import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageExecutionStore
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine

class PlannedMessageExecutionCoordinator(
    private val executionStore: PlannedMessageExecutionStore,
    private val schedulerEngine: SchedulerEngine,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    data class RunResult(
        val sentCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val lastErrorReason: String?,
    )

    suspend fun execute(
        claimedMessages: List<PlannedMessageEntity>,
        settings: PlannedMessageSettings,
        sender: IMeshSender?,
    ): RunResult {
        var sentCount = 0
        var failedCount = 0
        var skippedCount = 0
        var lastErrorReason: String? = if (sender == null) "mesh_service_unavailable" else null

        claimedMessages.forEach { message ->
            val now = nowProvider()
            val outcome = schedulerEngine.applyExecutionPolicy(
                message = message,
                nowUtcEpochMs = now,
                settings = settings,
            )

            if (!outcome.shouldSend) {
                skippedCount += 1
                executionStore.finalizeClaimedMessage(
                    claimedMessage = message,
                    resolvedMessage = outcome.updatedMessage,
                    lastFiredAtUtcEpochMs = message.lastFiredAtUtcEpochMs,
                    attemptCountSinceLastFire = 0,
                    updatedAtUtcEpochMs = now,
                )
                return@forEach
            }

            val accepted = runCatching { sender?.send(message) ?: false }
                .onFailure { throwable ->
                    lastErrorReason = "send_exception:${throwable::class.java.simpleName}"
                }
                .getOrDefault(false)

            if (accepted) {
                sentCount += 1
                executionStore.finalizeClaimedMessage(
                    claimedMessage = message,
                    resolvedMessage = outcome.updatedMessage,
                    lastFiredAtUtcEpochMs = now,
                    attemptCountSinceLastFire = 0,
                    updatedAtUtcEpochMs = now,
                )
            } else {
                failedCount += 1
                if (lastErrorReason == null) {
                    lastErrorReason = if (sender == null) "mesh_service_unavailable" else "send_rejected"
                }
                val attempts = message.attemptCountSinceLastFire + 1
                executionStore.failClaimedMessage(
                    claimedMessage = message,
                    nextTriggerAtUtcEpochMs = now + computeRetryBackoffMs(attempts),
                    attemptCountSinceLastFire = attempts,
                    updatedAtUtcEpochMs = now,
                )
            }
        }

        return RunResult(
            sentCount = sentCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            lastErrorReason = lastErrorReason,
        )
    }

    companion object {
        fun computeRetryBackoffMs(attemptCountSinceLastFire: Int): Long {
            return when {
                attemptCountSinceLastFire <= 1 -> 60_000L
                attemptCountSinceLastFire == 2 -> 2 * 60_000L
                attemptCountSinceLastFire == 3 -> 5 * 60_000L
                else -> 10 * 60_000L
            }
        }
    }
}
