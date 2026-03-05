package com.geeksville.mesh.plannedmessages.data

import android.content.SharedPreferences
import com.geeksville.mesh.CoroutineDispatchers
import com.geeksville.mesh.prefs.UserPrefs
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import com.geeksville.mesh.plannedmessages.orchestration.PlannedMessageScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannedMessageRepository @Inject constructor(
    private val daoLazy: dagger.Lazy<PlannedMessageDao>,
    @PlannedMessageStatusPrefs private val statusPrefs: SharedPreferences,
    private val settingsStore: PlannedMessageSettingsStore,
    private val schedulerEngine: SchedulerEngine,
    private val scheduler: PlannedMessageScheduler,
    private val migrator: LegacyPlannedMessageMigrator,
    private val dispatchers: CoroutineDispatchers,
) : PlannedMessageExecutionStore {

    private val plannedMessageDao by lazy {
        daoLazy.get()
    }

    fun observeDestinationSummaries(): Flow<List<PlannedMessageDestinationSummary>> {
        return plannedMessageDao.observeDestinationSummaries()
    }

    fun observeByDestination(destinationKey: String): Flow<List<PlannedMessageEntity>> {
        return plannedMessageDao.observeByDestination(destinationKey)
    }

    fun isPlannerEnabled(): Boolean {
        return statusPrefs.getBoolean(UserPrefs.PlannedMessage.PLANMSG_SERVICE_ACTIVE, false)
    }

    fun getSettings(): PlannedMessageSettings {
        return settingsStore.getSettings()
    }

    fun canScheduleExactAlarms(): Boolean {
        return scheduler.canScheduleExactAlarms()
    }

    fun getDebugSnapshot(): PlannedMessageDebugSnapshot {
        return PlannedMessageDebugSnapshot(
            exactAlarmAvailable = scheduler.canScheduleExactAlarms(),
            settings = settingsStore.getSettings(),
            lastAlarmScheduledAtUtcMs = statusPrefs.getLongOrNull(UserPrefs.PlannedMessage.PLANMSG_LAST_ALARM_SCHEDULED_AT_UTC_MS),
            lastAlarmFiredAtUtcMs = statusPrefs.getLongOrNull(UserPrefs.PlannedMessage.PLANMSG_LAST_ALARM_FIRED_AT_UTC_MS),
            lastRunAtUtcMs = statusPrefs.getLongOrNull(UserPrefs.PlannedMessage.PLANMSG_LAST_RUN_AT_UTC_MS),
            lastClaimedCount = statusPrefs.getInt(UserPrefs.PlannedMessage.PLANMSG_LAST_CLAIMED_COUNT, 0),
            lastSentCount = statusPrefs.getInt(UserPrefs.PlannedMessage.PLANMSG_LAST_SENT_COUNT, 0),
            lastErrorReason = statusPrefs.getString(UserPrefs.PlannedMessage.PLANMSG_LAST_ERROR_REASON, null),
        )
    }

    suspend fun setPlannerEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        statusPrefs.edit().putBoolean(UserPrefs.PlannedMessage.PLANMSG_SERVICE_ACTIVE, enabled).apply()
        scheduler.scheduleNextAlarm()
    }

    suspend fun updateLateFireWithinGrace(enabled: Boolean, graceMs: Long) = withContext(dispatchers.io) {
        settingsStore.updateLateFireWithinGrace(enabled = enabled, graceMs = graceMs)
        scheduler.scheduleNextAlarm()
    }

    suspend fun bootstrap() = withContext(dispatchers.io) {
        migrator.migrateIfNeeded()
        scheduler.scheduleNextAlarm()
    }

    suspend fun replaceForDestination(
        destinationKey: String,
        drafts: List<PlannedMessageDraft>,
    ) = withContext(dispatchers.io) {
        migrator.migrateIfNeeded()
        val now = System.currentTimeMillis()
        val settings = settingsStore.getSettings()
        plannedMessageDao.deleteByDestination(destinationKey)
        if (drafts.isNotEmpty()) {
            val timezoneFallback = ZoneId.systemDefault().id
            val entities = drafts.map { draft ->
                val base = PlannedMessageEntity(
                    destinationKey = destinationKey,
                    messageText = draft.messageText,
                    scheduleType = draft.scheduleType,
                    daysOfWeekMask = draft.daysOfWeekMask,
                    hourOfDay = draft.hourOfDay,
                    minuteOfHour = draft.minuteOfHour,
                    oneShotAtUtcEpochMs = draft.oneShotAtUtcEpochMs,
                    timezoneId = if (draft.timezoneId.isBlank()) timezoneFallback else draft.timezoneId,
                    deliveryPolicy = draft.deliveryPolicy,
                    isEnabled = draft.isEnabled,
                    nextTriggerAtUtcEpochMs = null,
                    createdAtUtcEpochMs = now,
                    updatedAtUtcEpochMs = now,
                )
                schedulerEngine.initializeForScheduling(base, now, settings)
            }
            plannedMessageDao.insert(entities)
        }
        scheduler.scheduleNextAlarm()
    }

    suspend fun claimDueMessages(
        nowUtcEpochMs: Long,
        leaseMs: Long,
    ): List<PlannedMessageEntity> = withContext(dispatchers.io) {
        migrator.migrateIfNeeded()
        val settings = settingsStore.getSettings()
        plannedMessageDao.claimDueMessages(
            nowUtcEpochMs = nowUtcEpochMs,
            leaseMs = leaseMs,
            limit = settings.maxCatchUpBurst,
        )
    }

    suspend fun update(message: PlannedMessageEntity) = withContext(dispatchers.io) {
        plannedMessageDao.update(message)
    }

    override suspend fun finalizeClaimedMessage(
        claimedMessage: PlannedMessageEntity,
        resolvedMessage: PlannedMessageEntity,
        lastFiredAtUtcEpochMs: Long?,
        attemptCountSinceLastFire: Int,
        updatedAtUtcEpochMs: Long,
    ): Boolean = withContext(dispatchers.io) {
        plannedMessageDao.finalizeClaimedMessage(
            id = claimedMessage.id,
            expectedLastAttemptedAtUtcEpochMs = claimedMessage.lastAttemptedAtUtcEpochMs,
            isEnabled = resolvedMessage.isEnabled,
            nextTriggerAtUtcEpochMs = resolvedMessage.nextTriggerAtUtcEpochMs,
            lastFiredAtUtcEpochMs = lastFiredAtUtcEpochMs,
            attemptCountSinceLastFire = attemptCountSinceLastFire,
            hadTimezoneFallback = resolvedMessage.hadTimezoneFallback,
            updatedAtUtcEpochMs = updatedAtUtcEpochMs,
        ) > 0
    }

    override suspend fun failClaimedMessage(
        claimedMessage: PlannedMessageEntity,
        nextTriggerAtUtcEpochMs: Long,
        attemptCountSinceLastFire: Int,
        updatedAtUtcEpochMs: Long,
    ): Boolean = withContext(dispatchers.io) {
        plannedMessageDao.failClaimedMessage(
            id = claimedMessage.id,
            expectedLastAttemptedAtUtcEpochMs = claimedMessage.lastAttemptedAtUtcEpochMs,
            nextTriggerAtUtcEpochMs = nextTriggerAtUtcEpochMs,
            attemptCountSinceLastFire = attemptCountSinceLastFire,
            updatedAtUtcEpochMs = updatedAtUtcEpochMs,
        ) > 0
    }

    suspend fun recordExecutionRun(
        lastRunAtUtcEpochMs: Long,
        claimedCount: Int,
        sentCount: Int,
        lastErrorReason: String?,
    ) = withContext(dispatchers.io) {
        statusPrefs.edit()
            .putLong(UserPrefs.PlannedMessage.PLANMSG_LAST_RUN_AT_UTC_MS, lastRunAtUtcEpochMs)
            .putInt(UserPrefs.PlannedMessage.PLANMSG_LAST_CLAIMED_COUNT, claimedCount)
            .putInt(UserPrefs.PlannedMessage.PLANMSG_LAST_SENT_COUNT, sentCount)
            .putString(UserPrefs.PlannedMessage.PLANMSG_LAST_ERROR_REASON, lastErrorReason)
            .apply()
    }

    private fun SharedPreferences.getLongOrNull(key: String): Long? {
        return if (contains(key)) getLong(key, 0L) else null
    }
}
