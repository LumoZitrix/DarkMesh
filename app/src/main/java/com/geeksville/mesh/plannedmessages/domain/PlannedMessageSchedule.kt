package com.geeksville.mesh.plannedmessages.domain

import java.time.DayOfWeek

enum class PlannedMessageScheduleType {
    WEEKLY,
    ONE_SHOT,
}

enum class PlannedMessageDeliveryPolicy {
    SKIP_MISSED,
    CATCH_UP,
}

object WeeklyDays {
    val orderedDays: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    fun maskFor(dayOfWeek: DayOfWeek): Int = 1 shl dayToBit(dayOfWeek)

    fun dayToBit(dayOfWeek: DayOfWeek): Int = (dayOfWeek.value + 6) % 7

    fun bitToDay(bitIndex: Int): DayOfWeek = orderedDays[bitIndex]

    fun contains(mask: Int, dayOfWeek: DayOfWeek): Boolean {
        return mask and maskFor(dayOfWeek) != 0
    }
}
