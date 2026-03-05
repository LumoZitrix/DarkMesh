package com.geeksville.mesh.plannedmessages.domain

data class PlannedMessageSettings(
    val skipMissedGraceMs: Long = DEFAULT_SKIP_MISSED_GRACE_MS,
    val maxCatchUpBurst: Int = DEFAULT_MAX_CATCH_UP_BURST,
    val lateFireMode: LateFireMode = LateFireMode.FIRE_IF_WITHIN_GRACE,
) {
    companion object {
        const val DEFAULT_SKIP_MISSED_GRACE_MS: Long = 15 * 60 * 1000L
        const val DEFAULT_MAX_CATCH_UP_BURST: Int = 10

        val ALLOWED_GRACE_WINDOWS_MS: List<Long> = listOf(
            5 * 60 * 1000L,
            10 * 60 * 1000L,
            DEFAULT_SKIP_MISSED_GRACE_MS,
            30 * 60 * 1000L,
        )
    }
}

enum class LateFireMode {
    SKIP,
    FIRE_IMMEDIATELY,
    FIRE_IF_WITHIN_GRACE,
}
