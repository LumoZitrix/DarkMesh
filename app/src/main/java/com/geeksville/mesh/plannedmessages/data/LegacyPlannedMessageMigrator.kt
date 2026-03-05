package com.geeksville.mesh.plannedmessages.data

import android.content.SharedPreferences
import android.util.Log
import com.geeksville.mesh.CoroutineDispatchers
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import com.geeksville.mesh.prefs.UserPrefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyPlannedMessageMigrator @Inject constructor(
    @LegacyPlannedMessagePrefs private val legacyPrefs: SharedPreferences,
    @PlannedMessageStatusPrefs private val statusPrefs: SharedPreferences,
    private val daoLazy: dagger.Lazy<PlannedMessageDao>,
    private val parser: LegacyPlannedMessageParser,
    private val schedulerEngine: SchedulerEngine,
    private val dispatchers: CoroutineDispatchers,
) {

    private val plannedMessageDao by lazy {
        daoLazy.get()
    }
    private val migrationMutex = Mutex()

    suspend fun migrateIfNeeded() = withContext(dispatchers.io) {
        migrationMutex.withLock {
            if (statusPrefs.getBoolean(UserPrefs.PlannedMessage.PLANMSG_MIGRATION_DONE, false)) return@withLock

            if (plannedMessageDao.countAll() > 0) {
                markMigrationDone()
                return@withLock
            }

            val now = System.currentTimeMillis()
            val timezoneId = ZoneId.systemDefault().id
            val migratedRows = mutableListOf<PlannedMessageEntity>()

            legacyPrefs.all.forEach { (destinationRaw, value) ->
                val destinationKey = destinationRaw?.trim().orEmpty()
                if (destinationKey.isBlank()) return@forEach
                val joinedRules = value as? String ?: return@forEach
                joinedRules.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val parsed = parser.parseLine(line)
                        if (parsed == null) {
                            Log.w(TAG, "Skipping malformed legacy rule: $line")
                            return@forEach
                        }
                        val base = PlannedMessageEntity(
                            destinationKey = destinationKey,
                            messageText = parsed.messageText,
                            scheduleType = PlannedMessageScheduleType.WEEKLY,
                            daysOfWeekMask = parsed.daysOfWeekMask,
                            hourOfDay = parsed.hourOfDay,
                            minuteOfHour = parsed.minuteOfHour,
                            oneShotAtUtcEpochMs = null,
                            timezoneId = timezoneId,
                            deliveryPolicy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
                            isEnabled = true,
                            nextTriggerAtUtcEpochMs = null,
                            createdAtUtcEpochMs = now,
                            updatedAtUtcEpochMs = now,
                        )
                        migratedRows += schedulerEngine.initializeForScheduling(base, now)
                    }
            }

            if (migratedRows.isNotEmpty()) {
                plannedMessageDao.insert(migratedRows)
            }
            markMigrationDone()
            Log.i(TAG, "Legacy planned messages migrated: ${migratedRows.size} rows")
        }
    }

    private fun markMigrationDone() {
        statusPrefs.edit().putBoolean(UserPrefs.PlannedMessage.PLANMSG_MIGRATION_DONE, true).apply()
    }

    companion object {
        private const val TAG = "PlannedMsgMigration"
    }
}
