package com.geeksville.mesh.plannedmessages.execution

import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity

interface IMeshSender {
    suspend fun send(message: PlannedMessageEntity): Boolean
}
