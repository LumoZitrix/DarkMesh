package com.geeksville.mesh.plannedmessages.trigger

import com.geeksville.mesh.plannedmessages.data.PlannedMessageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlannedMessageReceiverEntryPoint {
    fun plannedMessageRepository(): PlannedMessageRepository
}
