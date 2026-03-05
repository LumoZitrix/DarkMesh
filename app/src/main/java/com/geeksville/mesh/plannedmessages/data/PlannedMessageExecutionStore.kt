package com.geeksville.mesh.plannedmessages.data

interface PlannedMessageExecutionStore {
    suspend fun finalizeClaimedMessage(
        claimedMessage: PlannedMessageEntity,
        resolvedMessage: PlannedMessageEntity,
        lastFiredAtUtcEpochMs: Long?,
        attemptCountSinceLastFire: Int,
        updatedAtUtcEpochMs: Long,
    ): Boolean

    suspend fun failClaimedMessage(
        claimedMessage: PlannedMessageEntity,
        nextTriggerAtUtcEpochMs: Long,
        attemptCountSinceLastFire: Int,
        updatedAtUtcEpochMs: Long,
    ): Boolean
}
