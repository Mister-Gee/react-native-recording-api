package com.recordingapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLogicTest {
  private val minute = 60_000L
  private val day = 24L * 60 * minute

  @Test fun acceptsEveryProviderRepresentableWholeMinuteWidth() {
    listOf(1.0, 7.0, 100.0, 1441.0, 2880.0, Int.MAX_VALUE.toDouble()).forEach {
      assertEquals(it.toLong() * minute, bucketMs(it))
    }
    listOf(0.0, -1.0, 1.5, Int.MAX_VALUE.toDouble() + 1, Double.NaN,
      Double.POSITIVE_INFINITY).forEach { assertNull("bucketMinutes=$it", bucketMs(it)) }
  }

  @Test fun acceptsOnlyWholeFiniteEpochMillis() {
    assertEquals(1_791_464_400_000L, epochMs(1_791_464_400_000.0))
    assertNull(epochMs(1.5))
    assertNull(epochMs(Double.NaN))
    assertNull(epochMs(Double.POSITIVE_INFINITY))
    assertNull(epochMs(1.0e17))
  }

  @Test fun clampsExactCallerRangeWithoutUtcAlignment() {
    val now = 20 * day + 12_345
    val retentionStart = now - RETENTION_MS
    assertEquals(ReadWindow(retentionStart, now), readWindow(0, now + 99_999, now))
    val recentStart = now - 12 * minute + 7_123
    val recentEnd = now - 7 * minute + 12_456
    assertEquals(ReadWindow(recentStart, recentEnd), readWindow(recentStart, recentEnd, now))
    assertNull(readWindow(0, retentionStart, now))
    assertThrows(IllegalArgumentException::class.java) { readWindow(5, 5, now) }
  }

  @Test fun classifiesReadinessAndAllStepCounterSensorVariants() {
    assertEquals(STATUS_NEEDS_PERMISSION, recordingStatus(false, true, true, false))
    assertEquals(STATUS_NO_SENSOR, recordingStatus(true, false, true, false))
    assertEquals(STATUS_PLAY_SERVICES_MISSING, recordingStatus(true, true, false, false))
    assertEquals(STATUS_UPDATE_REQUIRED, recordingStatus(true, true, false, true))
    assertEquals(STATUS_READY, recordingStatus(true, true, true, false))
    assertTrue(hasStepCounterSensor(listOf("wake-up")))
    assertTrue(hasStepCounterSensor(listOf("non-wake-up")))
    assertTrue(hasStepCounterSensor(listOf("wake-up", "non-wake-up")))
    assertFalse(hasStepCounterSensor(emptyList<String>()))
    assertFalse(hasStepCounterSensor(null))
  }

  @Test fun readsFiveMinuteUnalignedRangeWithoutLosingItsSteps() {
    val now = 20 * day + 13 * 60 * minute
    val start = 20 * day + (12 * 60 + 7) * minute
    val end = 20 * day + (12 * 60 + 12) * minute
    val window = readWindow(start, end, now)!!
    assertEquals(ReadWindow(start, end), window)
    assertEquals(listOf(StepInterval(start, end, 14)), mapBuckets(window, listOf(
      ProviderBucket(start, end, listOf(AggregatePoint(start + 30_000, end, 14))),
    )))
  }

  private data class Pending(val kind: MutationKind, val complete: (String) -> Unit)

  @Test fun coalescesOnlyAdjacentSubscribesAcrossOrderedBarriers() {
    val started = mutableListOf<Pending>()
    val settled = mutableListOf<String>()
    lateinit var coordinator: MutationCoordinator<String>
    coordinator = MutationCoordinator(
      start = { kind, complete ->
        assertFalse(coordinator.isLockedByCurrentThread())
        started.add(Pending(kind, complete))
      },
      startFailure = { _, error -> "throw:${error.message}" },
    )

    listOf(MutationKind.SUBSCRIBE, MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE,
      MutationKind.SUBSCRIBE, MutationKind.SUBSCRIBE).forEachIndexed { index, kind ->
      coordinator.enqueue(kind) { settled.add("$index:$it") }
    }
    assertEquals(listOf(MutationKind.SUBSCRIBE), started.map { it.kind })
    started[0].complete("s1")
    assertEquals(listOf(MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE), started.map { it.kind })
    started[1].complete("u")
    assertEquals(listOf(MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE, MutationKind.SUBSCRIBE),
      started.map { it.kind })
    started[2].complete("s2")
    assertEquals(listOf("0:s1", "1:s1", "2:u", "3:s2", "4:s2"), settled)
  }

  @Test fun preservesEveryUnsubscribeBarrierAndExecutionTimeState() {
    val started = mutableListOf<Pending>()
    val observedReady = mutableListOf<Boolean>()
    var ready = false
    val coordinator = MutationCoordinator<String>(
      start = { kind, complete ->
        if (kind == MutationKind.SUBSCRIBE) observedReady.add(ready)
        started.add(Pending(kind, complete))
      },
      startFailure = { _, _ -> "failed" },
    )
    coordinator.enqueue(MutationKind.UNSUBSCRIBE) {}
    coordinator.enqueue(MutationKind.SUBSCRIBE) {}
    coordinator.enqueue(MutationKind.UNSUBSCRIBE) {}
    ready = true
    started[0].complete("u1")
    assertEquals(listOf(true), observedReady)
    started[1].complete("s")
    started[2].complete("u2")
    assertEquals(listOf(MutationKind.UNSUBSCRIBE, MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE),
      started.map { it.kind })
  }

  @Test fun failureThrowCancellationAndDuplicateCompletionAlwaysAdvanceOnce() {
    val started = mutableListOf<Pending>()
    val settled = mutableListOf<String>()
    var throwSecond = false
    val coordinator = MutationCoordinator<String>(
      start = { kind, complete ->
        if (throwSecond && kind == MutationKind.UNSUBSCRIBE) throw IllegalStateException("boom")
        started.add(Pending(kind, complete))
      },
      startFailure = { _, error -> "throw:${error.message}" },
    )
    coordinator.enqueue(MutationKind.SUBSCRIBE) { settled.add("first:$it") }
    coordinator.enqueue(MutationKind.UNSUBSCRIBE) { settled.add("second:$it") }
    coordinator.enqueue(MutationKind.SUBSCRIBE) { settled.add("third:$it") }
    throwSecond = true
    started[0].complete("cancelled")
    started[0].complete("duplicate")
    assertEquals(listOf("first:cancelled", "second:throw:boom"), settled)
    assertEquals(2, started.size)
    started[1].complete("provider-failure")
    assertEquals(listOf("first:cancelled", "second:throw:boom", "third:provider-failure"), settled)
  }

  @Test fun reentrantSettlementStaysBehindSelectedGroupAndRunsOutsideLock() {
    val started = mutableListOf<Pending>()
    val order = mutableListOf<String>()
    lateinit var coordinator: MutationCoordinator<String>
    coordinator = MutationCoordinator(
      start = { kind, complete ->
        assertFalse(coordinator.isLockedByCurrentThread())
        started.add(Pending(kind, complete))
      },
      startFailure = { _, _ -> "failed" },
    )
    coordinator.enqueue(MutationKind.SUBSCRIBE) {
      assertFalse(coordinator.isLockedByCurrentThread())
      order.add("first")
      coordinator.enqueue(MutationKind.SUBSCRIBE) { order.add("reentrant") }
    }
    coordinator.enqueue(MutationKind.UNSUBSCRIBE) { order.add("barrier") }
    started[0].complete("ok")
    assertEquals(listOf(MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE), started.map { it.kind })
    started[1].complete("ok")
    assertEquals(listOf(MutationKind.SUBSCRIBE, MutationKind.UNSUBSCRIBE, MutationKind.SUBSCRIBE),
      started.map { it.kind })
    started[2].complete("ok")
    assertEquals(listOf("first", "barrier", "reentrant"), order)
  }

  @Test fun mapsCrossGridAndShortBucketsAndKeepsExplicitZeros() {
    val window = ReadWindow(7 * minute, 112 * minute)
    val mapped = mapBuckets(window, listOf(
      ProviderBucket(7 * minute, 107 * minute,
        listOf(AggregatePoint(8 * minute, 30 * minute, 9),
          AggregatePoint(20 * minute, 40 * minute, 3))),
      ProviderBucket(107 * minute, 112 * minute, emptyList()),
      ProviderBucket(112 * minute, 120 * minute, null),
    ))
    assertEquals(listOf(12L, 0L), mapped.map { it.steps })
    assertEquals(112 * minute, mapped.last().endMs)
  }

  @Test fun clipsOnlyMetadataWhenPositivePointCoverageIsInsideQuery() {
    val window = ReadWindow(100, 200)
    assertEquals(StepInterval(100, 200, 7), mapBuckets(window, listOf(
      ProviderBucket(0, 300, listOf(AggregatePoint(120, 180, 7))))).single())
    assertEquals(StepInterval(100, 200, 0), mapBuckets(window, listOf(
      ProviderBucket(0, 300, listOf(AggregatePoint(-50, 350, 0))))).single())
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(0, 300, listOf(AggregatePoint(120, 201, 7)))))
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(0, 90, listOf(AggregatePoint(10, 80, 7)))))
    }
  }

  @Test fun rejectsInvalidOriginalMetadataCoverageAndEveryBadCount() {
    val window = ReadWindow(100, 300)
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(50, 50, emptyList())))
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(100, 200,
        listOf(AggregatePoint(90, 150, 1)))))
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(100, 200,
        listOf(AggregatePoint(110, 120, -1), AggregatePoint(120, 130, 10)))))
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(ProviderBucket(100, 200,
        listOf(AggregatePoint(110, 120, Int.MAX_VALUE.toLong()),
          AggregatePoint(120, 130, 1)))))
    }
  }

  @Test fun rejectsProviderOverlapButAcceptsSharedEndpointsAndPointOverlap() {
    val window = ReadWindow(0, 300)
    assertThrows(IllegalArgumentException::class.java) {
      mapBuckets(window, listOf(
        ProviderBucket(0, 200, emptyList()), ProviderBucket(100, 300, emptyList())))
    }
    val mapped = mapBuckets(window, listOf(
      ProviderBucket(0, 150, listOf(
        AggregatePoint(10, 100, 2), AggregatePoint(50, 140, 3))),
      ProviderBucket(150, 300, listOf(AggregatePoint(150, 300, 4))),
    ))
    assertEquals(listOf(5L, 4L), mapped.map { it.steps })
  }
}
