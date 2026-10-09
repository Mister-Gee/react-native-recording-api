package com.recordingapi

// Pure logic, no Android or React Native types, so it runs as plain JVM unit tests.

internal const val STATUS_READY = "ready"
internal const val STATUS_NEEDS_PERMISSION = "needs_permission"
internal const val STATUS_NO_SENSOR = "no_sensor"
internal const val STATUS_UPDATE_REQUIRED = "play_services_update_required"
internal const val STATUS_PLAY_SERVICES_MISSING = "play_services_missing"
internal const val SUBSCRIPTION_FAILED = "subscription_failed"

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_DAY = 1440
internal const val RETENTION_MS = 10L * 24 * 60 * 60 * 1000

internal data class ReadWindow(val startMs: Long, val endMs: Long)
internal data class StepInterval(val startMs: Long, val endMs: Long, val steps: Long)

/** Bucket widths that tile a UTC day exactly, so every bucket sits on one fixed epoch grid. */
internal fun bucketMs(bucketMinutes: Double): Long? {
  if (!bucketMinutes.isFinite() || bucketMinutes % 1.0 != 0.0) return null
  if (bucketMinutes < 1 || bucketMinutes > MINUTES_PER_DAY) return null
  val minutes = bucketMinutes.toInt()
  return if (MINUTES_PER_DAY % minutes == 0) minutes * MINUTE_MS else null
}

/** JS numbers arrive as doubles; accept only whole, finite epoch milliseconds. */
internal fun epochMs(value: Double): Long? =
  if (value.isFinite() && value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) value.toLong() else null

/** Omit an uncertain leading edge; keep the active trailing prefix in its fixed UTC bucket. */
internal fun readWindow(startMs: Long, endMs: Long, nowMs: Long, bucketMs: Long): ReadWindow? {
  require(endMs > startMs) { "Step read requires an increasing range" }
  require(bucketMs > 0) { "Bucket width must be positive" }
  val first = maxOf(startMs, nowMs - RETENTION_MS)
  val alignedStart = Math.floorDiv(first + bucketMs - 1, bucketMs) * bucketMs
  val boundedEnd = minOf(endMs, nowMs)
  return if (alignedStart < boundedEnd) ReadWindow(alignedStart, boundedEnd) else null
}

internal fun recordingStatus(
  permission: Boolean,
  sensor: Boolean,
  playServicesAvailable: Boolean,
  updateRequired: Boolean,
): String = when {
  !permission -> STATUS_NEEDS_PERMISSION
  !sensor -> STATUS_NO_SENSOR
  playServicesAvailable -> STATUS_READY
  updateRequired -> STATUS_UPDATE_REQUIRED
  else -> STATUS_PLAY_SERVICES_MISSING
}

/** One response per subscription task, including concurrent probes. */
internal class SubscriptionGate<T> {
  private val waiters = mutableListOf<(T) -> Unit>()

  /** Returns true when the caller is first and must start the task. */
  @Synchronized fun join(waiter: (T) -> Unit): Boolean {
    val first = waiters.isEmpty()
    waiters.add(waiter)
    return first
  }

  @Synchronized fun finish(result: T) {
    val current = waiters.toList()
    waiters.clear()
    current.forEach { it(result) }
  }
}

/**
 * Validate and clip the buckets Play services returns; drop empty ones.
 *
 * Each bucket must fit inside one epoch-grid bucket and the read window, in order,
 * without overlap. Observed on device (play-services-fitness 21.2.0, 15-minute
 * buckets): Play steps buckets from the request start, but the first bucket begins
 * at the first recorded data point (off-grid when the window predates it) and the
 * last ends at the read end. A defensive clip still trims any bucket ending after
 * the read; its steps were all recorded before the read.
 */
internal fun mapIntervals(window: ReadWindow, intervals: List<StepInterval>, bucketMs: Long): List<StepInterval> {
  var previousEnd = window.startMs
  return intervals.sortedBy { it.startMs }.mapNotNull { supplied ->
    if (supplied.startMs >= window.endMs) return@mapNotNull null
    val interval = supplied.copy(endMs = minOf(supplied.endMs, window.endMs))
    require(
      interval.startMs >= previousEnd && interval.endMs <= window.endMs &&
        interval.startMs < interval.endMs &&
        Math.floorDiv(interval.startMs, bucketMs) == Math.floorDiv(interval.endMs - 1, bucketMs) &&
        interval.steps in 0..Int.MAX_VALUE.toLong(),
    ) {
      "Invalid Recording API bucket ${supplied.startMs}-${supplied.endMs} in ${window.startMs}-${window.endMs}"
    }
    previousEnd = interval.endMs
    if (interval.steps == 0L) null else interval
  }
}
