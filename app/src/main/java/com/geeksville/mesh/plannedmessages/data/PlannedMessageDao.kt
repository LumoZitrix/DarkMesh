package com.geeksville.mesh.plannedmessages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
        SELECT MIN(
            CASE
                WHEN in_flight_until_utc_epoch_ms IS NOT NULL
                    AND in_flight_until_utc_epoch_ms > next_trigger_at_utc_epoch_ms
                THEN in_flight_until_utc_epoch_ms
                ELSE next_trigger_at_utc_epoch_ms
            END
        )
        FROM planned_messages
        WHERE is_enabled = 1
          AND next_trigger_at_utc_epoch_ms IS NOT NULL
        """
    )
    suspend fun getNextTriggerAtUtcEpochMs(): Long?

    @Query(
        """
        SELECT id FROM planned_messages
        WHERE is_enabled = 1
          AND next_trigger_at_utc_epoch_ms IS NOT NULL
          AND next_trigger_at_utc_epoch_ms <= :nowUtcEpochMs
          AND (in_flight_until_utc_epoch_ms IS NULL OR in_flight_until_utc_epoch_ms <= :nowUtcEpochMs)
        ORDER BY next_trigger_at_utc_epoch_ms ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun selectDueMessageIdsForClaim(nowUtcEpochMs: Long, limit: Int): List<Long>

    @Query(
        """
        UPDATE planned_messages
        SET in_flight_until_utc_epoch_ms = :leaseUntilUtcEpochMs,
            last_attempted_at_utc_epoch_ms = :nowUtcEpochMs,
            updated_at_utc_epoch_ms = :nowUtcEpochMs
        WHERE id IN (:ids)
          AND is_enabled = 1
          AND next_trigger_at_utc_epoch_ms IS NOT NULL
          AND next_trigger_at_utc_epoch_ms <= :nowUtcEpochMs
          AND (in_flight_until_utc_epoch_ms IS NULL OR in_flight_until_utc_epoch_ms <= :nowUtcEpochMs)
        """
    )
    suspend fun markMessagesClaimed(
        ids: List<Long>,
        nowUtcEpochMs: Long,
        leaseUntilUtcEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM planned_messages
        WHERE id IN (:ids)
          AND last_attempted_at_utc_epoch_ms = :nowUtcEpochMs
          AND in_flight_until_utc_epoch_ms = :leaseUntilUtcEpochMs
        ORDER BY next_trigger_at_utc_epoch_ms ASC, id ASC
        """
    )
    suspend fun getClaimedMessages(
        ids: List<Long>,
        nowUtcEpochMs: Long,
        leaseUntilUtcEpochMs: Long,
    ): List<PlannedMessageEntity>

    @Transaction
    suspend fun claimDueMessages(
        nowUtcEpochMs: Long,
        leaseMs: Long,
        limit: Int,
    ): List<PlannedMessageEntity> {
        if (limit <= 0) return emptyList()
        val candidateIds = selectDueMessageIdsForClaim(nowUtcEpochMs = nowUtcEpochMs, limit = limit)
        if (candidateIds.isEmpty()) return emptyList()
        val leaseUntil = nowUtcEpochMs + leaseMs
        val claimedCount = markMessagesClaimed(
            ids = candidateIds,
            nowUtcEpochMs = nowUtcEpochMs,
            leaseUntilUtcEpochMs = leaseUntil,
        )
        if (claimedCount <= 0) return emptyList()
        return getClaimedMessages(
            ids = candidateIds,
            nowUtcEpochMs = nowUtcEpochMs,
            leaseUntilUtcEpochMs = leaseUntil,
        )
    }

    @Query(
        """
        UPDATE planned_messages
        SET is_enabled = :isEnabled,
            next_trigger_at_utc_epoch_ms = :nextTriggerAtUtcEpochMs,
            last_fired_at_utc_epoch_ms = :lastFiredAtUtcEpochMs,
            in_flight_until_utc_epoch_ms = NULL,
            attempt_count_since_last_fire = :attemptCountSinceLastFire,
            had_timezone_fallback = :hadTimezoneFallback,
            updated_at_utc_epoch_ms = :updatedAtUtcEpochMs
        WHERE id = :id
          AND last_attempted_at_utc_epoch_ms = :expectedLastAttemptedAtUtcEpochMs
        """
    )
    suspend fun finalizeClaimedMessage(
        id: Long,
        expectedLastAttemptedAtUtcEpochMs: Long?,
        isEnabled: Boolean,
        nextTriggerAtUtcEpochMs: Long?,
        lastFiredAtUtcEpochMs: Long?,
        attemptCountSinceLastFire: Int,
        hadTimezoneFallback: Boolean,
        updatedAtUtcEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE planned_messages
        SET next_trigger_at_utc_epoch_ms = :nextTriggerAtUtcEpochMs,
            in_flight_until_utc_epoch_ms = NULL,
            attempt_count_since_last_fire = :attemptCountSinceLastFire,
            updated_at_utc_epoch_ms = :updatedAtUtcEpochMs
        WHERE id = :id
          AND last_attempted_at_utc_epoch_ms = :expectedLastAttemptedAtUtcEpochMs
        """
    )
    suspend fun failClaimedMessage(
        id: Long,
        expectedLastAttemptedAtUtcEpochMs: Long?,
        nextTriggerAtUtcEpochMs: Long,
        attemptCountSinceLastFire: Int,
        updatedAtUtcEpochMs: Long,
    ): Int

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
