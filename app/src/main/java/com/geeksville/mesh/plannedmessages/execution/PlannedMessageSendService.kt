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
import com.geeksville.mesh.plannedmessages.data.PlannedMessageRepository
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import com.geeksville.mesh.plannedmessages.orchestration.PlannedMessageScheduler
import com.geeksville.mesh.service.MeshService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val executionCoordinator by lazy {
        PlannedMessageExecutionCoordinator(repository, schedulerEngine)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXECUTE_DUE_MESSAGES) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch {
            executionMutex.withLock {
                runCatching { executeDueMessages() }
                    .onFailure { throwable ->
                        Log.w(TAG, "Planned message execution failed", throwable)
                    }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun executeDueMessages() {
        repository.bootstrap()
        try {
            val settings = repository.getSettings()
            val claimNow = System.currentTimeMillis()
            val claimedMessages = repository.claimDueMessages(
                nowUtcEpochMs = claimNow,
                leaseMs = CLAIM_LEASE_MS,
            )
            Log.i(TAG, "Claimed planned messages: ${claimedMessages.size}")
            if (claimedMessages.isEmpty()) {
                repository.recordExecutionRun(
                    lastRunAtUtcEpochMs = System.currentTimeMillis(),
                    claimedCount = 0,
                    sentCount = 0,
                    lastErrorReason = null,
                )
                return
            }

            val boundMeshService = connectMeshService()
            val sender = boundMeshService?.let { MeshServicePlannedMessageSender(it.meshService) }
            if (sender == null) {
                Log.w(TAG, "MeshService unavailable for planned message execution, applying backoff")
            }
            try {
                Log.d(
                    TAG_ENGINE,
                    "Applying policy lateMode=${settings.lateFireMode} graceMs=${settings.skipMissedGraceMs} claimed=${claimedMessages.size}",
                )
                val runResult = executionCoordinator.execute(
                    claimedMessages = claimedMessages,
                    settings = settings,
                    sender = sender,
                )
                Log.i(
                    TAG,
                    "Planned message run summary claimed=${claimedMessages.size} sent=${runResult.sentCount} failed=${runResult.failedCount} skipped=${runResult.skippedCount}",
                )
                repository.recordExecutionRun(
                    lastRunAtUtcEpochMs = System.currentTimeMillis(),
                    claimedCount = claimedMessages.size,
                    sentCount = runResult.sentCount,
                    lastErrorReason = runResult.lastErrorReason,
                )
            } finally {
                if (boundMeshService != null) {
                    runCatching { unbindService(boundMeshService.connection) }
                }
            }
        } finally {
            plannedMessageScheduler.scheduleNextAlarm()
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
        private const val TAG = "PM_SEND"
        private const val CHANNEL_ID = "planned_message_execution"
        private const val NOTIFICATION_ID = 84018
        private const val MESH_BIND_TIMEOUT_MS = 10_000L
        private const val CLAIM_LEASE_MS = 2 * 60 * 1000L
        private const val TAG_ENGINE = "PM_ENGINE"
        private val executionMutex = Mutex()

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
