package com.geeksville.mesh.plannedmessages.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannedMessageDaoClaimTest {

    private lateinit var database: MeshtasticDatabase
    private lateinit var dao: PlannedMessageDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MeshtasticDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.plannedMessageDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun claimDueMessages_isAtomicAcrossConcurrentCallers() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insert(
            listOf(
                dueMessage("dest-1", "m1", now - 1_000L),
                dueMessage("dest-2", "m2", now - 2_000L),
                dueMessage("dest-3", "m3", now - 3_000L),
            )
        )

        val startGate = CompletableDeferred<Unit>()
        val claim1 = async(Dispatchers.IO) {
            startGate.await()
            dao.claimDueMessages(nowUtcEpochMs = now, leaseMs = 60_000L, limit = 10)
                .map { it.id }
                .toSet()
        }
        val claim2 = async(Dispatchers.IO) {
            startGate.await()
            dao.claimDueMessages(nowUtcEpochMs = now, leaseMs = 60_000L, limit = 10)
                .map { it.id }
                .toSet()
        }

        startGate.complete(Unit)
        val ids1 = claim1.await()
        val ids2 = claim2.await()

        assertTrue(ids1.intersect(ids2).isEmpty())
        assertEquals(3, ids1.size + ids2.size)
    }

    @Test
    fun claimDueMessages_allowsReclaimAfterLeaseExpires() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insert(listOf(dueMessage("dest-1", "m1", now - 1_000L)))

        val first = dao.claimDueMessages(nowUtcEpochMs = now, leaseMs = 5_000L, limit = 1)
        val secondBeforeExpiry = dao.claimDueMessages(nowUtcEpochMs = now + 1_000L, leaseMs = 5_000L, limit = 1)
        val secondAfterExpiry = dao.claimDueMessages(nowUtcEpochMs = now + 6_000L, leaseMs = 5_000L, limit = 1)

        assertEquals(1, first.size)
        assertTrue(secondBeforeExpiry.isEmpty())
        assertEquals(1, secondAfterExpiry.size)
        assertEquals(first.first().id, secondAfterExpiry.first().id)
    }

    @Test
    fun observeByDestination_ordersByNextOccurrenceAndIncludesDisabled() = runBlocking {
        val now = 1_700_000_000_000L
        val early = dueMessage("dest-1", "early", now + 60_000L)
        val late = dueMessage("dest-1", "late", now + 600_000L)
        val disabled = dueMessage("dest-1", "disabled", now + 120_000L).copy(
            isEnabled = false,
            nextTriggerAtUtcEpochMs = null,
        )
        dao.insert(listOf(late, disabled, early))

        val observed = dao.observeByDestination("dest-1").first()

        assertEquals(3, observed.size)
        assertEquals("early", observed[0].messageText)
        assertEquals("late", observed[1].messageText)
        assertEquals("disabled", observed[2].messageText)
    }

    private fun dueMessage(destination: String, text: String, dueAtUtcMs: Long): PlannedMessageEntity {
        return PlannedMessageEntity(
            destinationKey = destination,
            messageText = text,
            scheduleType = PlannedMessageScheduleType.ONE_SHOT,
            daysOfWeekMask = 0,
            hourOfDay = 0,
            minuteOfHour = 0,
            oneShotAtUtcEpochMs = dueAtUtcMs,
            timezoneId = "UTC",
            deliveryPolicy = PlannedMessageDeliveryPolicy.CATCH_UP,
            isEnabled = true,
            nextTriggerAtUtcEpochMs = dueAtUtcMs,
            createdAtUtcEpochMs = dueAtUtcMs - 60_000L,
            updatedAtUtcEpochMs = dueAtUtcMs - 60_000L,
        )
    }
}
