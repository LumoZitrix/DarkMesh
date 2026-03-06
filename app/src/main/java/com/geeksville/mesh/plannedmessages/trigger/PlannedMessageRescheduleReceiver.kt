package com.geeksville.mesh.plannedmessages.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlannedMessageRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    PlannedMessageReceiverEntryPoint::class.java,
                )
                entryPoint.plannedMessageRepository().bootstrap()
                Log.d(TAG, "Rescheduled planned messages after ${intent.action}")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to reschedule planned messages", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PM_SCHED"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
