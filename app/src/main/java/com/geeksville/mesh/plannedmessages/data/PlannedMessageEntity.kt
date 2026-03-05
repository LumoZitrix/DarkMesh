package com.geeksville.mesh.plannedmessages.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType

@Entity(
    tableName = "planned_messages",
    indices = [
        Index(value = ["destination_key"]),
        Index(value = ["is_enabled", "next_trigger_at_utc_epoch_ms", "in_flight_until_utc_epoch_ms"]),
    ],
)
data class PlannedMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "destination_key")
    val destinationKey: String,
    @ColumnInfo(name = "message_text")
    val messageText: String,
    @ColumnInfo(name = "schedule_type")
    val scheduleType: PlannedMessageScheduleType,
    @ColumnInfo(name = "days_of_week_mask")
    val daysOfWeekMask: Int,
    @ColumnInfo(name = "hour_of_day")
    val hourOfDay: Int,
    @ColumnInfo(name = "minute_of_hour")
    val minuteOfHour: Int,
    @ColumnInfo(name = "one_shot_at_utc_epoch_ms")
    val oneShotAtUtcEpochMs: Long?,
    @ColumnInfo(name = "timezone_id")
    val timezoneId: String,
    @ColumnInfo(name = "delivery_policy")
    val deliveryPolicy: PlannedMessageDeliveryPolicy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "next_trigger_at_utc_epoch_ms")
    val nextTriggerAtUtcEpochMs: Long?,
    @ColumnInfo(name = "in_flight_until_utc_epoch_ms")
    val inFlightUntilUtcEpochMs: Long? = null,
    @ColumnInfo(name = "last_attempted_at_utc_epoch_ms")
    val lastAttemptedAtUtcEpochMs: Long? = null,
    @ColumnInfo(name = "attempt_count_since_last_fire")
    val attemptCountSinceLastFire: Int = 0,
    @ColumnInfo(name = "last_fired_at_utc_epoch_ms")
    val lastFiredAtUtcEpochMs: Long? = null,
    @ColumnInfo(name = "had_timezone_fallback")
    val hadTimezoneFallback: Boolean = false,
    @ColumnInfo(name = "created_at_utc_epoch_ms")
    val createdAtUtcEpochMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at_utc_epoch_ms")
    val updatedAtUtcEpochMs: Long = System.currentTimeMillis(),
)

data class PlannedMessageDestinationSummary(
    @ColumnInfo(name = "destination_key")
    val destinationKey: String,
    @ColumnInfo(name = "rule_count")
    val ruleCount: Int,
)

data class PlannedMessageDraft(
    val messageText: String,
    val scheduleType: PlannedMessageScheduleType,
    val daysOfWeekMask: Int,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val oneShotAtUtcEpochMs: Long?,
    val timezoneId: String,
    val deliveryPolicy: PlannedMessageDeliveryPolicy,
    val isEnabled: Boolean = true,
)
