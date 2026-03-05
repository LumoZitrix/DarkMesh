package com.geeksville.mesh.plannedmessages.data

import android.content.SharedPreferences
import com.geeksville.mesh.CoroutineDispatchers
import com.geeksville.mesh.prefs.UserPrefs
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
    private val schedulerEngine: SchedulerEngine,
    private val scheduler: PlannedMessageScheduler,
    private val migrator: LegacyPlannedMessageMigrator,
    private val dispatchers: CoroutineDispatchers,
) {

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

    suspend fun setPlannerEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        statusPrefs.edit().putBoolean(UserPrefs.PlannedMessage.PLANMSG_SERVICE_ACTIVE, enabled).apply()
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
                schedulerEngine.initializeForScheduling(base, now)
            }
            plannedMessageDao.insert(entities)
        }
        scheduler.scheduleNextAlarm()
    }

    suspend fun getDueMessages(nowUtcEpochMs: Long): List<PlannedMessageEntity> = withContext(dispatchers.io) {
        migrator.migrateIfNeeded()
        plannedMessageDao.getDueMessages(nowUtcEpochMs)
    }

    suspend fun update(message: PlannedMessageEntity) = withContext(dispatchers.io) {
        plannedMessageDao.update(message)
    }
}
