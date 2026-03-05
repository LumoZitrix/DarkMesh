package com.geeksville.mesh.plannedmessages.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageRepository
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import com.geeksville.mesh.plannedmessages.orchestration.PlannedMessageScheduler
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.service.startService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class PlannedMessageSendService : Service() {

    @Inject
    lateinit var repository: PlannedMessageRepository

    @Inject
    lateinit var schedulerEngine: SchedulerEngine

    @Inject
    lateinit var plannedMessageScheduler: PlannedMessageScheduler

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXECUTE_DUE_MESSAGES) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch {
            executeDueMessages()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun executeDueMessages() {
        repository.bootstrap()
        val boundMeshService = connectMeshService()
        if (boundMeshService == null) {
            Log.w(TAG, "MeshService unavailable for planned message execution")
            plannedMessageScheduler.scheduleNextAlarm()
            return
        }

        try {
            val sender: IMeshSender = MeshServicePlannedMessageSender(boundMeshService.meshService)
            val nowUtcEpochMs = System.currentTimeMillis()
            val dueMessages = repository.getDueMessages(nowUtcEpochMs)
            if (dueMessages.isEmpty()) {
                plannedMessageScheduler.scheduleNextAlarm()
                return
            }

            dueMessages.forEach { message ->
                val now = System.currentTimeMillis()
                val outcome = schedulerEngine.applyExecutionPolicy(message, now)
                processMessageOutcome(message, outcome, now, sender)
            }
        } finally {
            runCatching { unbindService(boundMeshService.connection) }
        }

        plannedMessageScheduler.scheduleNextAlarm()
    }

    private suspend fun processMessageOutcome(
        originalMessage: PlannedMessageEntity,
        outcome: SchedulerEngine.ExecutionOutcome,
        nowUtcEpochMs: Long,
        sender: IMeshSender,
    ) {
        if (!outcome.shouldSend) {
            repository.update(outcome.updatedMessage)
            return
        }

        val accepted = sender.send(originalMessage)
        if (accepted) {
            repository.update(outcome.updatedMessage)
        } else {
            // Keep the same occurrence pending but avoid hot-loop retries.
            repository.update(
                originalMessage.copy(
                    nextTriggerAtUtcEpochMs = nowUtcEpochMs + SEND_RETRY_DELAY_MS,
                    updatedAtUtcEpochMs = nowUtcEpochMs,
                )
            )
        }
    }

    private suspend fun connectMeshService(): BoundMeshService? {
        MeshService.startService(applicationContext)
        return withTimeoutOrNull(MESH_BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val serviceIntent = Intent(this@PlannedMessageSendService, MeshService::class.java).apply {
                    action = MeshService.BIND_LOCAL_ACTION_INTENT
                }
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                        val accessor = binder as? MeshService.MeshServiceAccessor
                        if (accessor == null) {
                            runCatching { unbindService(this) }
                            continuation.resume(null)
                            return
                        }
                        continuation.resume(BoundMeshService(accessor.getService(), this))
                    }

                    override fun onServiceDisconnected(componentName: ComponentName) = Unit
                }
                val bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { unbindService(connection) }
                }
            }
        }
    }

    private fun createNotification(): Notification {
        createChannelIfNeeded()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Planned Messages")
            .setContentText("Executing scheduled messages")
            .setSmallIcon(R.drawable.ic_twotone_send_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Planned message execution",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "PlannedMsgExec"
        private const val CHANNEL_ID = "planned_message_execution"
        private const val NOTIFICATION_ID = 84018
        private const val MESH_BIND_TIMEOUT_MS = 10_000L
        private const val SEND_RETRY_DELAY_MS = 60_000L

        const val ACTION_EXECUTE_DUE_MESSAGES = "com.geeksville.mesh.plannedmessages.EXECUTE"

        fun start(context: Context) {
            val intent = Intent(context, PlannedMessageSendService::class.java).apply {
                action = ACTION_EXECUTE_DUE_MESSAGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private data class BoundMeshService(
        val meshService: MeshService,
        val connection: ServiceConnection,
    )
}
