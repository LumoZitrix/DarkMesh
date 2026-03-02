package com.geeksville.mesh.service

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAlertEvaluatorTest {

    private val settings = BatteryAlertSettings(
        enabled = true,
        percentThreshold = 20,
        voltageThreshold = 3.7f,
    )

    @Test
    fun `returns low when battery percent drops below configured threshold`() {
        val level = BatteryAlertEvaluator.nextLevel(
            previousLevel = BatteryAlertLevel.NONE,
            snapshot = BatterySnapshot(batteryLevel = 19, voltage = 3.9f),
            settings = settings,
        )

        assertEquals(BatteryAlertLevel.LOW, level)
    }

    @Test
    fun `returns low when voltage drops below configured threshold and percent is unavailable`() {
        val level = BatteryAlertEvaluator.nextLevel(
            previousLevel = BatteryAlertLevel.NONE,
            snapshot = BatterySnapshot(batteryLevel = 0, voltage = 3.69f),
            settings = settings,
        )

        assertEquals(BatteryAlertLevel.LOW, level)
    }

    @Test
    fun `keeps critical alert sticky until recovered above critical hysteresis`() {
        val critical = BatteryAlertEvaluator.nextLevel(
            previousLevel = BatteryAlertLevel.NONE,
            snapshot = BatterySnapshot(batteryLevel = 9, voltage = 3.45f),
            settings = settings,
        )
        val stillCritical = BatteryAlertEvaluator.nextLevel(
            previousLevel = critical,
            snapshot = BatterySnapshot(batteryLevel = 11, voltage = 3.56f),
            settings = settings,
        )
        val downgradedToLow = BatteryAlertEvaluator.nextLevel(
            previousLevel = stillCritical,
            snapshot = BatterySnapshot(batteryLevel = 14, voltage = 3.72f),
            settings = settings,
        )

        assertEquals(BatteryAlertLevel.CRITICAL, critical)
        assertEquals(BatteryAlertLevel.CRITICAL, stillCritical)
        assertEquals(BatteryAlertLevel.LOW, downgradedToLow)
    }

    @Test
    fun `keeps low alert sticky until recovered above warning hysteresis`() {
        val low = BatteryAlertEvaluator.nextLevel(
            previousLevel = BatteryAlertLevel.NONE,
            snapshot = BatterySnapshot(batteryLevel = 19, voltage = 3.8f),
            settings = settings,
        )
        val stillLow = BatteryAlertEvaluator.nextLevel(
            previousLevel = low,
            snapshot = BatterySnapshot(batteryLevel = 21, voltage = 3.8f),
            settings = settings,
        )
        val cleared = BatteryAlertEvaluator.nextLevel(
            previousLevel = stillLow,
            snapshot = BatterySnapshot(batteryLevel = 24, voltage = 3.9f),
            settings = settings,
        )

        assertEquals(BatteryAlertLevel.LOW, low)
        assertEquals(BatteryAlertLevel.LOW, stillLow)
        assertEquals(BatteryAlertLevel.NONE, cleared)
    }

    @Test
    fun `disables alerts when the feature is turned off`() {
        val level = BatteryAlertEvaluator.nextLevel(
            previousLevel = BatteryAlertLevel.LOW,
            snapshot = BatterySnapshot(batteryLevel = 5, voltage = 3.4f),
            settings = settings.copy(enabled = false),
        )

        assertEquals(BatteryAlertLevel.NONE, level)
    }
}
