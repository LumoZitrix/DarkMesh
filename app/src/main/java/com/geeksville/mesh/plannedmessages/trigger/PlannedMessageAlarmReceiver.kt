package com.geeksville.mesh.plannedmessages.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geeksville.mesh.plannedmessages.execution.PlannedMessageSendService

class PlannedMessageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_PLANNED_MESSAGES) return
        Log.d(TAG, "Planned message alarm fired")
        PlannedMessageSendService.start(context)
    }

    companion object {
        private const val TAG = "PlannedMsgAlarm"
        const val ACTION_FIRE_PLANNED_MESSAGES = "com.geeksville.mesh.plannedmessages.ACTION_FIRE"
    }
}
