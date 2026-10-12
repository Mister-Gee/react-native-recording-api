package com.recordingapi

// Pure logic, no Android or React Native types, so it runs as plain JVM unit tests.

internal const val STATUS_READY = "ready"
internal const val STATUS_NEEDS_PERMISSION = "needs_permission"
internal const val STATUS_NO_SENSOR = "no_sensor"
internal const val STATUS_UPDATE_REQUIRED = "play_services_update_required"
internal const val STATUS_PLAY_SERVICES_MISSING = "play_services_missing"
internal const val SUBSCRIPTION_FAILED = "subscription_failed"

private const val MINUTE_MS = 60_000L
internal const val RETENTION_MS = 10L * 24 * 60 * 60 * 1000

internal data class ReadWindow(val startMs: Long, val endMs: Long)
internal data class StepInterval(val startMs: Long, val endMs: Long, val steps: Long)
internal data class AggregatePoint(val startMs: Long, val endMs: Long, val steps: Long)
internal data class ProviderBucket(
  val startMs: Long,
  val endMs: Long,
  /** Null means the requested step dataset was absent; an empty list is a present dataset. */
  val points: List<AggregatePoint>?,
)

/** Any positive whole-minute width accepted by the provider's Int argument. */
internal fun bucketMs(bucketMinutes: Double): Long? {
  if (!bucketMinutes.isFinite() || bucketMinutes % 1.0 != 0.0) return null
  if (bucketMinutes < 1 || bucketMinutes > Int.MAX_VALUE.toDouble()) return null
  return bucketMinutes.toLong() * MINUTE_MS
}

/** JS numbers arrive as doubles; accept only whole, finite epoch milliseconds. */
internal fun epochMs(value: Double): Long? =
  if (value.isFinite() && value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) value.toLong() else null

/** Preserve the caller's boundaries except for the exact provider retention and now clamps. */
internal fun readWindow(startMs: Long, endMs: Long, nowMs: Long): ReadWindow? {
  require(endMs > startMs) { "Step read requires an increasing range" }
  val boundedStart = maxOf(startMs, nowMs - RETENTION_MS)
  val boundedEnd = minOf(endMs, nowMs)
  return if (boundedStart < boundedEnd) ReadWindow(boundedStart, boundedEnd) else null
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

internal fun hasStepCounterSensor(sensors: List<*>?): Boolean = !sensors.isNullOrEmpty()

internal enum class MutationKind { SUBSCRIBE, UNSUBSCRIBE }

/**
 * Serializes provider mutations by invocation order. Only adjacent subscribe callers share work.
 * Queue transitions are protected by [lock]; provider work and settlements always run afterwards.
 */
internal class MutationCoordinator<T>(
  private val start: (MutationKind, (T) -> Unit) -> Unit,
  private val startFailure: (MutationKind, Throwable) -> T,
) {
  private class Group<T>(
    val kind: MutationKind,
    val callbacks: MutableList<(T) -> Unit>,
  )

  private val lock = Any()
  private val queued = java.util.ArrayDeque<Group<T>>()
  private var active: Group<T>? = null

  fun enqueue(kind: MutationKind, callback: (T) -> Unit) {
    var launch: Group<T>? = null
    synchronized(lock) {
      val queuedTail = queued.peekLast()
      when {
        kind == MutationKind.SUBSCRIBE && queuedTail?.kind == MutationKind.SUBSCRIBE ->
          queuedTail.callbacks.add(callback)
        kind == MutationKind.SUBSCRIBE && queued.isEmpty() && active?.kind == MutationKind.SUBSCRIBE ->
          active!!.callbacks.add(callback)
        else -> {
          val group = Group(kind, mutableListOf(callback))
          if (active == null) {
            active = group
            launch = group
          } else {
            queued.addLast(group)
          }
        }
      }
    }
    launch?.let(::startGroup)
  }

  internal fun isLockedByCurrentThread(): Boolean = Thread.holdsLock(lock)

  private fun startGroup(group: Group<T>) {
    try {
      start(group.kind) { result -> finish(group, result) }
    } catch (error: Throwable) {
      finish(group, startFailure(group.kind, error))
    }
  }

  private fun finish(group: Group<T>, result: T) {
    val callbacks: List<(T) -> Unit>
    var next: Group<T>? = null
    synchronized(lock) {
      if (active !== group) return
      callbacks = group.callbacks.toList()
      next = if (queued.isEmpty()) null else queued.removeFirst()
      active = next
    }

    // A throwing/reentrant caller cannot strand or bypass the group already selected above.
    callbacks.forEach { callback ->
      try {
        callback(result)
      } catch (_: Throwable) {
        // Settlement belongs to the caller; queue progress must not depend on its callback.
      }
    }
    next?.let(::startGroup)
  }
}

/**
 * Validate provider metadata and aggregate-point coverage, then clip metadata to the exact query.
 * A present empty/zero dataset is evidence for an explicit zero bucket; a missing dataset is not.
 */
internal fun mapBuckets(window: ReadWindow, supplied: List<ProviderBucket>): List<StepInterval> {
  val buckets = supplied.sortedBy { it.startMs }
  var previousOriginalEnd: Long? = null
  var previousReturnedEnd: Long? = null
  val result = mutableListOf<StepInterval>()

  for (bucket in buckets) {
    require(bucket.startMs < bucket.endMs) {
      "Invalid Recording API bucket ${bucket.startMs}-${bucket.endMs}"
    }
    previousOriginalEnd?.let { end ->
      require(bucket.startMs >= end) {
        "Overlapping Recording API buckets at ${bucket.startMs}"
      }
    }
    previousOriginalEnd = bucket.endMs

    val points = bucket.points ?: continue
    var count = 0L
    for (point in points) {
      require(point.steps in 0..Int.MAX_VALUE.toLong()) {
        "Invalid aggregate step count ${point.steps}"
      }
      require(point.startMs < point.endMs) {
        "Invalid aggregate interval ${point.startMs}-${point.endMs}"
      }
      if (point.steps > 0) {
        require(
          point.startMs >= bucket.startMs && point.endMs <= bucket.endMs &&
            point.startMs >= window.startMs && point.endMs <= window.endMs,
        ) {
          "Positive aggregate ${point.startMs}-${point.endMs} is outside its bucket or query"
        }
      }
      count += point.steps
      require(count <= Int.MAX_VALUE.toLong()) { "Aggregate step count exceeds Int.MAX_VALUE" }
    }

    val clippedStart = maxOf(bucket.startMs, window.startMs)
    val clippedEnd = minOf(bucket.endMs, window.endMs)
    if (clippedStart >= clippedEnd) {
      require(count == 0L) { "Positive Recording API bucket is outside the query" }
      continue
    }
    previousReturnedEnd?.let { end ->
      require(clippedStart >= end) { "Overlapping returned Recording API buckets at $clippedStart" }
    }
    result.add(StepInterval(clippedStart, clippedEnd, count))
    previousReturnedEnd = clippedEnd
  }
  return result
}
