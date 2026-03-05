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

        val pendingIntent = buildPendingIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerAt, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerAt, pendingIntent)
        }
        Log.d(TAG, "Scheduled next planned message alarm at $nextTriggerAt")
    }

    suspend fun cancelAlarm() = withContext(dispatchers.io) {
        cancelAlarmInternal()
    }

    private fun cancelAlarmInternal() {
        alarmManager.cancel(buildPendingIntent())
        Log.d(TAG, "Canceled planned message alarm")
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, PlannedMessageAlarmReceiver::class.java).apply {
            action = PlannedMessageAlarmReceiver.ACTION_FIRE_PLANNED_MESSAGES
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "PlannedMsgScheduler"
        private const val REQUEST_CODE = 84017
    }
}
