package com.geeksville.mesh.plannedmessages.data

import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings

data class PlannedMessageDebugSnapshot(
    val exactAlarmAvailable: Boolean,
    val settings: PlannedMessageSettings,
    val lastAlarmScheduledAtUtcMs: Long?,
    val lastAlarmFiredAtUtcMs: Long?,
    val lastRunAtUtcMs: Long?,
    val lastClaimedCount: Int,
    val lastSentCount: Int,
    val lastErrorReason: String?,
)
