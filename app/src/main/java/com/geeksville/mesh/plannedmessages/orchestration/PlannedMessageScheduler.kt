package com.geeksville.mesh.plannedmessages.orchestration

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.geeksville.mesh.CoroutineDispatchers
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDao
import com.geeksville.mesh.plannedmessages.data.PlannedMessageStatusPrefs
import com.geeksville.mesh.plannedmessages.trigger.PlannedMessageAlarmReceiver
import com.geeksville.mesh.prefs.UserPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannedMessageScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val daoLazy: dagger.Lazy<PlannedMessageDao>,
    @PlannedMessageStatusPrefs private val statusPrefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
) {

    private val plannedMessageDao by lazy {
        daoLazy.get()
    }

    fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    suspend fun scheduleNextAlarm() = withContext(dispatchers.io) {
        if (!statusPrefs.getBoolean(UserPrefs.PlannedMessage.PLANMSG_SERVICE_ACTIVE, false)) {
            cancelAlarmInternal()
            return@withContext
        }

        val nextTriggerAt = plannedMessageDao.getNextTriggerAtUtcEpochMs()
        if (nextTriggerAt == null) {
            cancelAlarmInternal()
            return@withContext
        }

        val triggerAt = maxOf(nextTriggerAt, System.currentTimeMillis() + MIN_ALARM_DELAY_MS)
        val pendingIntent = buildPendingIntent(
            action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES,
            requestCode = REQUEST_CODE_PRIMARY,
        )
        if (!canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            val safetyJitterMs = triggerAt % SAFETY_ALARM_JITTER_WINDOW_MS
            val safetyTriggerAt = triggerAt + SAFETY_ALARM_OFFSET_MS + safetyJitterMs
            val safetyIntent = buildPendingIntent(
                action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES_SAFETY,
                requestCode = REQUEST_CODE_SAFETY,
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safetyTriggerAt, safetyIntent)
            Log.i(TAG, "Scheduled fallback alarms next=$triggerAt safety=$safetyTriggerAt")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            alarmManager.cancel(
                buildPendingIntent(
                    action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES_SAFETY,
                    requestCode = REQUEST_CODE_SAFETY,
                )
            )
            Log.i(TAG, "Scheduled exact alarm next=$triggerAt")
        }
        statusPrefs.edit()
            .putLong(UserPrefs.PlannedMessage.PLANMSG_LAST_ALARM_SCHEDULED_AT_UTC_MS, triggerAt)
            .apply()
        Log.d(TAG, "Alarm scheduled for trigger=$triggerAt")
    }

    suspend fun cancelAlarm() = withContext(dispatchers.io) {
        cancelAlarmInternal()
    }

    private fun cancelAlarmInternal() {
        alarmManager.cancel(
            buildPendingIntent(
                action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES,
                requestCode = REQUEST_CODE_PRIMARY,
            )
        )
        alarmManager.cancel(
            buildPendingIntent(
                action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES_SAFETY,
                requestCode = REQUEST_CODE_SAFETY,
            )
        )
        Log.d(TAG, "Canceled planned message alarms")
    }

    private fun buildPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlannedMessageAlarmReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "PM_SCHED"
        private const val REQUEST_CODE_PRIMARY = 84017
        private const val REQUEST_CODE_SAFETY = 84019
        private const val MIN_ALARM_DELAY_MS = 1_000L
        private const val SAFETY_ALARM_OFFSET_MS = 15 * 60 * 1000L
        private const val SAFETY_ALARM_JITTER_WINDOW_MS = 60_000L
    }
}
