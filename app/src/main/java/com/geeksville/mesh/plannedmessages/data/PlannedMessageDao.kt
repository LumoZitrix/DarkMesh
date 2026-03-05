package com.geeksville.mesh.plannedmessages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedMessageDao {

    @Query(
        """
        SELECT * FROM planned_messages
        WHERE destination_key = :destinationKey
          AND is_enabled = 1
        ORDER BY hour_of_day ASC, minute_of_hour ASC, id ASC
        """
    )
    fun observeByDestination(destinationKey: String): Flow<List<PlannedMessageEntity>>

    @Query(
        """
        SELECT destination_key, COUNT(*) AS rule_count
        FROM planned_messages
        WHERE is_enabled = 1
        GROUP BY destination_key
        ORDER BY destination_key ASC
        """
    )
    fun observeDestinationSummaries(): Flow<List<PlannedMessageDestinationSummary>>

    @Query(
        """
        SELECT * FROM planned_messages
        WHERE is_enabled = 1
          AND next_trigger_at_utc_epoch_ms IS NOT NULL
          AND next_trigger_at_utc_epoch_ms <= :nowUtcEpochMs
        ORDER BY next_trigger_at_utc_epoch_ms ASC, id ASC
        """
    )
    suspend fun getDueMessages(nowUtcEpochMs: Long): List<PlannedMessageEntity>

    @Query(
        """
        SELECT MIN(next_trigger_at_utc_epoch_ms)
        FROM planned_messages
        WHERE is_enabled = 1
          AND next_trigger_at_utc_epoch_ms IS NOT NULL
        """
    )
    suspend fun getNextTriggerAtUtcEpochMs(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(messages: List<PlannedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: PlannedMessageEntity): Long

    @Update
    suspend fun update(message: PlannedMessageEntity)

    @Query("DELETE FROM planned_messages WHERE destination_key = :destinationKey")
    suspend fun deleteByDestination(destinationKey: String)

    @Query("DELETE FROM planned_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM planned_messages")
    suspend fun countAll(): Int
}
