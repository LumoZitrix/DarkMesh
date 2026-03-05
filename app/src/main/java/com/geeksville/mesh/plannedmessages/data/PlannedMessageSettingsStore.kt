package com.geeksville.mesh.plannedmessages.data

import android.content.SharedPreferences
import com.geeksville.mesh.plannedmessages.domain.LateFireMode
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class PlannedMessageSettingsStore @Inject constructor(
    @PlannedMessageSettingsPrefs private val prefs: SharedPreferences,
) {

    fun getSettings(): PlannedMessageSettings {
        val graceMs = normalizeGraceWindow(
            prefs.getLong(
                KEY_SKIP_MISSED_GRACE_MS,
                PlannedMessageSettings.DEFAULT_SKIP_MISSED_GRACE_MS,
            )
        )
        val maxCatchUpBurst = max(
            1,
            prefs.getInt(KEY_MAX_CATCH_UP_BURST, PlannedMessageSettings.DEFAULT_MAX_CATCH_UP_BURST),
        )
        val lateFireMode = prefs.getString(KEY_LATE_FIRE_MODE, LateFireMode.FIRE_IF_WITHIN_GRACE.name)
            ?.let { value -> runCatching { LateFireMode.valueOf(value) }.getOrNull() }
            ?: LateFireMode.FIRE_IF_WITHIN_GRACE
        return PlannedMessageSettings(
            skipMissedGraceMs = graceMs,
            maxCatchUpBurst = maxCatchUpBurst,
            lateFireMode = lateFireMode,
        )
    }

    fun updateSettings(settings: PlannedMessageSettings) {
        prefs.edit()
            .putLong(KEY_SKIP_MISSED_GRACE_MS, normalizeGraceWindow(settings.skipMissedGraceMs))
            .putInt(KEY_MAX_CATCH_UP_BURST, max(1, settings.maxCatchUpBurst))
            .putString(KEY_LATE_FIRE_MODE, settings.lateFireMode.name)
            .apply()
    }

    fun updateLateFireWithinGrace(enabled: Boolean, graceMs: Long) {
        val current = getSettings()
        updateSettings(
            current.copy(
                skipMissedGraceMs = normalizeGraceWindow(graceMs),
                lateFireMode = if (enabled) LateFireMode.FIRE_IF_WITHIN_GRACE else LateFireMode.SKIP,
            )
        )
    }

    private fun normalizeGraceWindow(graceMs: Long): Long {
        return PlannedMessageSettings.ALLOWED_GRACE_WINDOWS_MS.minByOrNull { candidate ->
            kotlin.math.abs(candidate - graceMs)
        } ?: PlannedMessageSettings.DEFAULT_SKIP_MISSED_GRACE_MS
    }

    companion object {
        private const val KEY_SKIP_MISSED_GRACE_MS = "skip_missed_grace_ms"
        private const val KEY_MAX_CATCH_UP_BURST = "max_catch_up_burst"
        private const val KEY_LATE_FIRE_MODE = "late_fire_mode"
    }
}
