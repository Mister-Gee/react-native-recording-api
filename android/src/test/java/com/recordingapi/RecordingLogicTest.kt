package com.recordingapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLogicTest {
  private val bucket = 15L * 60 * 1000
  private val hour = 60L * 60 * 1000
  private val day = 24L * 60 * 60 * 1000

  @Test fun acceptsOnlyBucketWidthsThatTileADay() {
    assertEquals(bucket, bucketMs(15.0))
    assertEquals(hour, bucketMs(60.0))
    assertEquals(day, bucketMs(1440.0))
    assertEquals(60_000L, bucketMs(1.0))
    listOf(0.0, -15.0, 7.0, 15.5, 1441.0, 2880.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
      assertNull("bucketMinutes=$it", bucketMs(it))
    }
  }

  @Test fun acceptsOnlyWholeFiniteEpochMillis() {
    assertEquals(1_791_464_400_000L, epochMs(1_791_464_400_000.0))
    assertNull(epochMs(1.5))
    assertNull(epochMs(Double.NaN))
    assertNull(epochMs(Double.POSITIVE_INFINITY))
    assertNull(epochMs(1.0e17))
  }

  @Test fun classifiesReadinessBeforeSubscribing() {
    assertEquals(STATUS_NEEDS_PERMISSION, recordingStatus(false, true, true, false))
    assertEquals(STATUS_NO_SENSOR, recordingStatus(true, false, true, false))
    assertEquals(STATUS_PLAY_SERVICES_MISSING, recordingStatus(true, true, false, false))
    assertEquals(STATUS_UPDATE_REQUIRED, recordingStatus(true, true, false, true))
    assertEquals(STATUS_READY, recordingStatus(true, true, true, false))
  }

  @Test fun coalescesConcurrentSubscriptionCallbacksAndCanRetry() {
    val gate = SubscriptionGate<String>()
    val results = mutableListOf<String>()
    assertTrue(gate.join { results.add(it) })
    assertEquals(false, gate.join { results.add(it) })
    gate.finish(SUBSCRIPTION_FAILED)
    assertEquals(listOf(SUBSCRIPTION_FAILED, SUBSCRIPTION_FAILED), results)
    assertTrue(gate.join { results.add(it) })
    gate.finish(STATUS_READY)
    assertEquals(STATUS_READY, results.last())
  }

  @Test fun shiftsDoNotChangeClosedUtcBuckets() {
    val now = 20 * day
    val first = readWindow(0, now, now, bucket)!!
    val shifted = readWindow(5 * 60 * 1000, now, now, bucket)!!
    assertEquals(first, shifted)
    val source = listOf(StepInterval(first.startMs, first.startMs + bucket, 150))
    assertEquals(mapIntervals(first, source, bucket), mapIntervals(shifted, source, bucket))
    assertEquals(150L, mapIntervals(first, source, bucket).single().steps)
  }

  @Test fun shiftedStartInsideRetentionOmitsLeadingPartialAndSharesClosedBuckets() {
    val now = 20 * day
    val start = 15 * day
    val first = readWindow(start, now, now, bucket)!!
    val shifted = readWindow(start + 5 * 60 * 1000, now, now, bucket)!!
    assertEquals(start, first.startMs)
    assertEquals(start + bucket, shifted.startMs)
    val closed = StepInterval(start + bucket, start + 2 * bucket, 80)
    val fromFirst = mapIntervals(first, listOf(StepInterval(start, start + bucket, 150), closed), bucket)
    assertEquals(2, fromFirst.size)
    assertEquals(fromFirst.last(), mapIntervals(shifted, listOf(closed), bucket).single())
  }

  @Test fun clipsRetentionAndKeepsTrailingPrefixInItsUtcBucket() {
    val now = 20 * day + 5 * 60 * 1000
    val window = readWindow(0, now, now, bucket)!!
    assertEquals(10 * day + bucket, window.startMs)
    assertEquals(now, window.endMs)
    assertEquals(7L, mapIntervals(window, listOf(StepInterval(now - 5 * 60 * 1000, now, 7)), bucket).single().steps)
    assertNull(readWindow(0, 5 * 60 * 1000, now, bucket))
  }

  @Test fun alignsToTheGridOfTheRequestedWidth() {
    val now = 20 * day + 3 * hour + 10 * 60 * 1000
    val hourly = readWindow(15 * day + 20 * 60 * 1000, now, now, hour)!!
    assertEquals(15 * day + hour, hourly.startMs)
    val daily = readWindow(15 * day + 1, now, now, day)!!
    assertEquals(16 * day, daily.startMs)
    // Retention clamp rounds up to the next whole UTC day.
    assertEquals(11 * day, readWindow(0, now, now, day)!!.startMs)
  }

  @Test fun acceptsDailyBucketsAndRejectsOnesSpanningTwoDays() {
    val now = 3 * day + hour
    val window = ReadWindow(day, now)
    val mapped = mapIntervals(window, listOf(
      StepInterval(day, 2 * day, 9000),
      StepInterval(2 * day, 3 * day, 4000),
      StepInterval(3 * day, now, 120)), day)
    assertEquals(listOf(9000L, 4000L, 120L), mapped.map { it.steps })
    assertThrows(IllegalArgumentException::class.java) {
      mapIntervals(window, listOf(StepInterval(day + hour, 2 * day + hour, 10)), day)
    }
  }

  @Test fun clipsTheActiveFullLengthBucketToTheReadEnd() {
    val now = bucket * 2 + 5 * 60 * 1000
    val window = ReadWindow(0, now)
    val mapped = mapIntervals(window, listOf(
      StepInterval(bucket, bucket * 2, 40),
      StepInterval(bucket * 2, bucket * 3, 9),
      StepInterval(bucket * 3, bucket * 4, 0)), bucket)
    assertEquals(listOf(40L, 9L), mapped.map { it.steps })
    assertEquals(now, mapped.last().endMs)
  }

  @Test fun acceptsTheObservedPlayShapeWithALeadingBucketAtTheFirstDataPoint() {
    // Device capture: window 10:45:00Z..13:31:32.596Z, data from 13:13:35.735Z.
    val grid = 1_791_464_400_000L - 4 * bucket // 12:00:00Z
    val window = ReadWindow(grid - 5 * bucket, grid + 6 * bucket + 92_596)
    val firstData = grid + 4 * bucket + 815_735
    val mapped = mapIntervals(window, listOf(
      StepInterval(firstData, grid + 5 * bucket, 21),
      StepInterval(grid + 5 * bucket, grid + 6 * bucket, 14),
      StepInterval(grid + 6 * bucket, window.endMs, 0)), bucket)
    assertEquals(listOf(21L, 14L), mapped.map { it.steps })
    assertEquals(firstData, mapped.first().startMs)
    assertThrows(IllegalArgumentException::class.java) {
      mapIntervals(window, listOf(StepInterval(firstData, firstData + bucket, 35)), bucket)
    }
  }

  @Test fun rejectsInvalidOrOverlappingProviderBuckets() {
    val window = ReadWindow(0, bucket * 3)
    assertThrows(IllegalArgumentException::class.java) { readWindow(5, 5, 10, bucket) }
    assertThrows(IllegalArgumentException::class.java) {
      mapIntervals(window, listOf(StepInterval(0, bucket, 2), StepInterval(0, bucket, 2)), bucket)
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapIntervals(window, listOf(StepInterval(0, bucket + 1, 2)), bucket)
    }
    assertThrows(IllegalArgumentException::class.java) {
      mapIntervals(window, listOf(StepInterval(0, bucket, -1)), bucket)
    }
    assertEquals(emptyList<StepInterval>(), mapIntervals(window, emptyList(), bucket))
    assertEquals(emptyList<StepInterval>(), mapIntervals(window, listOf(StepInterval(0, bucket, 0)), bucket))
  }
}
