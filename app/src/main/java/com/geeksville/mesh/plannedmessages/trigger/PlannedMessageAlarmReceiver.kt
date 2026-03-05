package com.geeksville.mesh.plannedmessages.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geeksville.mesh.prefs.UserPrefs
import com.geeksville.mesh.plannedmessages.execution.PlannedMessageSendService

class PlannedMessageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_PLANNED_MESSAGES && intent.action != ACTION_FIRE_PLANNED_MESSAGES_SAFETY) return
        context.getSharedPreferences(UserPrefs.PlannedMessage.SHARED_PLANMSG_PREFS_STATUS, Context.MODE_PRIVATE)
            .edit()
            .putLong(UserPrefs.PlannedMessage.PLANMSG_LAST_ALARM_FIRED_AT_UTC_MS, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Planned message alarm fired action=${intent.action}")
        PlannedMessageSendService.start(context)
    }

    companion object {
        private const val TAG = "PM_SCHED"
        const val ACTION_FIRE_PLANNED_MESSAGES = "com.geeksville.mesh.plannedmessages.ACTION_FIRE"
        const val ACTION_FIRE_PLANNED_MESSAGES_SAFETY = "com.geeksville.mesh.plannedmessages.ACTION_FIRE_SAFETY"
    }
}
