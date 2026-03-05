package com.geeksville.mesh.plannedmessages.data

import com.geeksville.mesh.plannedmessages.domain.WeeklyDays
import java.time.DayOfWeek
import java.util.Locale
import javax.inject.Inject

class LegacyPlannedMessageParser @Inject constructor() {

    data class ParsedLegacyRule(
        val daysOfWeekMask: Int,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val messageText: String,
    )

    fun parseLine(rawLine: String): ParsedLegacyRule? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        val headerMatch = HEADER_REGEX.find(line) ?: return null
        val dayToken = headerMatch.groupValues[1].uppercase(Locale.ROOT)
        val timeToken = headerMatch.groupValues[2]
        val rawMessagePart = headerMatch.groupValues[3].trim()

        val dayOfWeek = dayTokenToDayOfWeek(dayToken) ?: return null
        val (hour, minute) = parseHourMinute(timeToken) ?: return null
        val messageText = sanitizeMessage(rawMessagePart)
        if (messageText.isBlank()) return null

        return ParsedLegacyRule(
            daysOfWeekMask = WeeklyDays.maskFor(dayOfWeek),
            hourOfDay = hour,
            minuteOfHour = minute,
            messageText = messageText,
        )
    }

    private fun sanitizeMessage(rawMessage: String): String {
        var message = rawMessage.trim()
        if (message.startsWith("â€”")) {
            message = message.removePrefix("â€”").trim()
        }
        message = message.trimStart('—', '-', ':', ' ')
        return message.trim()
    }

    private fun parseHourMinute(value: String): Pair<Int, Int>? {
        val chunks = value.split(":")
        if (chunks.size != 2) return null
        val hour = chunks[0].toIntOrNull() ?: return null
        val minute = chunks[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun dayTokenToDayOfWeek(dayToken: String): DayOfWeek? {
        return DAY_TOKENS[dayToken]
    }

    companion object {
        private val HEADER_REGEX = Regex("""^\s*(\S+)\s+(\d{1,2}:\d{2})\s*(.*)$""")

        private val DAY_TOKENS = mapOf(
            "LUN" to DayOfWeek.MONDAY,
            "MAR" to DayOfWeek.TUESDAY,
            "MER" to DayOfWeek.WEDNESDAY,
            "GIO" to DayOfWeek.THURSDAY,
            "VEN" to DayOfWeek.FRIDAY,
            "SAB" to DayOfWeek.SATURDAY,
            "DOM" to DayOfWeek.SUNDAY,
            "MON" to DayOfWeek.MONDAY,
            "TUE" to DayOfWeek.TUESDAY,
            "WED" to DayOfWeek.WEDNESDAY,
            "THU" to DayOfWeek.THURSDAY,
            "FRI" to DayOfWeek.FRIDAY,
            "SAT" to DayOfWeek.SATURDAY,
            "SUN" to DayOfWeek.SUNDAY,
        )
    }
}
