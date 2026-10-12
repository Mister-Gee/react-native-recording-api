package com.recordingapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import java.util.concurrent.TimeUnit

private sealed interface ProviderMutationResult {
  data class Subscribe(val status: String) : ProviderMutationResult
  data object UnsubscribeSuccess : ProviderMutationResult
  data class UnsubscribeFailure(val error: Throwable) : ProviderMutationResult
}

/** A subscription belongs to Play services, not to this process. Re-subscribing is a no-op there. */
class RecordingApiModule(reactContext: ReactApplicationContext) :
  NativeRecordingApiSpec(reactContext) {

  private val context: Context
    get() = reactApplicationContext.applicationContext

  private val mutations = MutationCoordinator<ProviderMutationResult>(
    start = ::startMutation,
    startFailure = { kind, error -> mutationFailure(kind, error) },
  )

  override fun getStatus(promise: Promise) {
    promise.resolve(status())
  }

  override fun hasStepSensor(promise: Promise) {
    promise.resolve(hasSensor())
  }

  override fun subscribe(promise: Promise) {
    mutations.enqueue(MutationKind.SUBSCRIBE) { result ->
      when (result) {
        is ProviderMutationResult.Subscribe -> promise.resolve(result.status)
        else -> promise.resolve(SUBSCRIPTION_FAILED)
      }
    }
  }

  override fun unsubscribe(promise: Promise) {
    mutations.enqueue(MutationKind.UNSUBSCRIBE) { result ->
      when (result) {
        ProviderMutationResult.UnsubscribeSuccess -> promise.resolve(null)
        is ProviderMutationResult.UnsubscribeFailure ->
          promise.reject(ERR_UNSUBSCRIBE_FAILED, result.error.message, result.error)
        else -> promise.reject(ERR_UNSUBSCRIBE_FAILED, "Unexpected unsubscribe result")
      }
    }
  }

  private fun startMutation(
    kind: MutationKind,
    complete: (ProviderMutationResult) -> Unit,
  ) {
    when (kind) {
      MutationKind.SUBSCRIBE -> {
        val current = status()
        if (current != STATUS_READY) {
          complete(ProviderMutationResult.Subscribe(current))
          return
        }
        client().subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
          .addOnCompleteListener { task ->
            complete(ProviderMutationResult.Subscribe(
              if (task.isSuccessful) STATUS_READY else SUBSCRIPTION_FAILED))
          }
      }
      MutationKind.UNSUBSCRIBE -> {
        val service = playServices()
        if (service != ConnectionResult.SUCCESS) {
          complete(ProviderMutationResult.UnsubscribeFailure(
            IllegalStateException("Google Play services unavailable for unsubscribe: $service")))
          return
        }
        client().unsubscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
          .addOnCompleteListener { task ->
            if (task.isSuccessful) {
              complete(ProviderMutationResult.UnsubscribeSuccess)
            } else {
              complete(ProviderMutationResult.UnsubscribeFailure(
                task.exception ?: IllegalStateException("Play services rejected unsubscribe")))
            }
          }
      }
    }
  }

  private fun mutationFailure(kind: MutationKind, error: Throwable): ProviderMutationResult =
    when (kind) {
      MutationKind.SUBSCRIBE -> ProviderMutationResult.Subscribe(SUBSCRIPTION_FAILED)
      MutationKind.UNSUBSCRIBE -> ProviderMutationResult.UnsubscribeFailure(error)
    }

  override fun readSteps(startMs: Double, endMs: Double, bucketMinutes: Double, promise: Promise) {
    if (bucketMs(bucketMinutes) == null) {
      promise.reject(ERR_INVALID_BUCKET, "bucketMinutes must be a whole number from 1 through ${Int.MAX_VALUE}")
      return
    }
    val start = epochMs(startMs)
    val end = epochMs(endMs)
    if (start == null || end == null || end <= start) {
      promise.reject(ERR_INVALID_RANGE, "Step read requires an increasing epoch-millisecond range")
      return
    }
    val current = status()
    if (current != STATUS_READY) {
      // The message is the status; the JS layer exposes it as RecordingApiError.status.
      promise.reject(ERR_RECORDING_UNAVAILABLE, current)
      return
    }
    val window = readWindow(start, end, System.currentTimeMillis())
    if (window == null) {
      promise.resolve(Arguments.createArray())
      return
    }
    try {
      val request = LocalDataReadRequest.Builder()
        .setTimeRange(window.startMs, window.endMs, TimeUnit.MILLISECONDS)
        .bucketByTime(bucketMinutes.toInt(), TimeUnit.MINUTES)
        .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
        .build()
      client().readData(request)
        .addOnSuccessListener { response ->
          if (!response.status.isSuccess) {
            promise.reject(ERR_READ_FAILED, response.status.statusMessage ?: "Read failed")
            return@addOnSuccessListener
          }
          try {
            val buckets = response.buckets.map { bucket ->
              val points = bucket.getDataSet(LocalDataType.TYPE_STEP_COUNT_DELTA)
                ?.dataPoints?.map { point ->
                  AggregatePoint(
                    point.getStartTime(TimeUnit.MILLISECONDS),
                    point.getEndTime(TimeUnit.MILLISECONDS),
                    point.getValue(LocalField.FIELD_STEPS).asInt().toLong(),
                  )
                }
              ProviderBucket(
                bucket.getStartTime(TimeUnit.MILLISECONDS),
                bucket.getEndTime(TimeUnit.MILLISECONDS),
                points,
              )
            }
            val result = Arguments.createArray()
            mapBuckets(window, buckets).forEach { interval ->
              result.pushMap(Arguments.createMap().apply {
                putDouble("startMs", interval.startMs.toDouble())
                putDouble("endMs", interval.endMs.toDouble())
                putInt("steps", interval.steps.toInt())
              })
            }
            promise.resolve(result)
          } catch (error: Exception) {
            promise.reject(ERR_READ_FAILED, error.message, error)
          }
        }
        .addOnFailureListener { error -> promise.reject(ERR_READ_FAILED, error.message, error) }
    } catch (error: Exception) {
      promise.reject(ERR_READ_FAILED, error.message, error)
    }
  }

  private fun client(): LocalRecordingClient = FitnessLocal.getLocalRecordingClient(context)

  private fun hasSensor(): Boolean =
    hasStepCounterSensor(
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?)
        ?.getSensorList(Sensor.TYPE_STEP_COUNTER))

  private fun playServices(): Int =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
      context, LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE)

  private fun status(): String {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return STATUS_NEEDS_PERMISSION
    val sensor = hasSensor()
    if (!sensor) return STATUS_NO_SENSOR
    val service = playServices()
    return recordingStatus(granted, sensor, service == ConnectionResult.SUCCESS,
      service == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED)
  }

  companion object {
    const val NAME = NativeRecordingApiSpec.NAME

    private const val ERR_INVALID_RANGE = "ERR_INVALID_RANGE"
    private const val ERR_INVALID_BUCKET = "ERR_INVALID_BUCKET"
    private const val ERR_RECORDING_UNAVAILABLE = "ERR_RECORDING_UNAVAILABLE"
    private const val ERR_READ_FAILED = "ERR_READ_FAILED"
    private const val ERR_UNSUBSCRIBE_FAILED = "ERR_UNSUBSCRIBE_FAILED"
  }
}
