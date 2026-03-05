package com.geeksville.mesh.plannedmessages.execution

import android.util.Log
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.service.MeshService
import javax.inject.Inject

class MeshServicePlannedMessageSender @Inject constructor(
    private val meshService: MeshService,
) : IMeshSender {

    override suspend fun send(message: PlannedMessageEntity): Boolean {
        return runCatching {
            val destination = resolveDestination(message.destinationKey) ?: return false
            meshService.getBinder().send(
                DataPacket(
                    destination.toNodeId,
                    destination.channel,
                    message.messageText,
                    null,
                )
            )
            true
        }.onFailure {
            Log.w(TAG, "Unable to submit planned message id=${message.id}", it)
        }.getOrDefault(false)
    }

    private fun resolveDestination(destinationKey: String): Destination? {
        if (destinationKey.contains(BROADCAST_KEY_TOKEN)) {
            val channel = destinationKey.substringBefore("^").toIntOrNull() ?: 0
            return Destination(channel = channel, toNodeId = DataPacket.ID_BROADCAST)
        }

        val nodeNum = destinationKey.toIntOrNull() ?: return null
        val node = meshService.nodeDBbyNodeNum[nodeNum] ?: return null
        val contactKey = meshService.buildContactKeyForMessage(node)
        val channel = contactKey.firstOrNull()?.digitToIntOrNull() ?: 0
        return Destination(
            channel = channel,
            toNodeId = contactKey.drop(1),
        )
    }

    private data class Destination(
        val channel: Int,
        val toNodeId: String,
    )

    companion object {
        private const val TAG = "PM_SEND"
        private const val BROADCAST_KEY_TOKEN = "^all"
    }
}
