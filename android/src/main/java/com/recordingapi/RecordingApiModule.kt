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

/** A subscription belongs to Play services, not to this process. Re-subscribing is a no-op there. */
class RecordingApiModule(reactContext: ReactApplicationContext) :
  NativeRecordingApiSpec(reactContext) {

  private val context: Context
    get() = reactApplicationContext.applicationContext

  private val subscription = SubscriptionGate<String>()

  override fun getStatus(promise: Promise) {
    promise.resolve(status())
  }

  override fun hasStepSensor(promise: Promise) {
    promise.resolve(hasSensor())
  }

  override fun subscribe(promise: Promise) {
    val current = status()
    if (current != STATUS_READY) {
      promise.resolve(current)
      return
    }
    if (!subscription.join { promise.resolve(it) }) return
    try {
      client().subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
        .addOnCompleteListener { task ->
          subscription.finish(if (task.isSuccessful) status() else SUBSCRIPTION_FAILED)
        }
    } catch (error: Exception) {
      subscription.finish(SUBSCRIPTION_FAILED)
    }
  }

  override fun unsubscribe(promise: Promise) {
    // Without a usable Play services there is no subscription to remove.
    if (playServices() != ConnectionResult.SUCCESS) {
      promise.resolve(null)
      return
    }
    try {
      client().unsubscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
        .addOnSuccessListener { promise.resolve(null) }
        .addOnFailureListener { error -> promise.reject(ERR_UNSUBSCRIBE_FAILED, error.message, error) }
    } catch (error: Exception) {
      promise.reject(ERR_UNSUBSCRIBE_FAILED, error.message, error)
    }
  }

  override fun readSteps(startMs: Double, endMs: Double, bucketMinutes: Double, promise: Promise) {
    val bucket = bucketMs(bucketMinutes)
    if (bucket == null) {
      promise.reject(ERR_INVALID_BUCKET, "bucketMinutes must be a whole number that divides 1440")
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
    val window = readWindow(start, end, System.currentTimeMillis(), bucket)
    if (window == null) {
      promise.resolve(Arguments.createArray())
      return
    }
    val request = LocalDataReadRequest.Builder()
      .setTimeRange(window.startMs, window.endMs, TimeUnit.MILLISECONDS)
      .bucketByTime(bucketMinutes.toInt(), TimeUnit.MINUTES)
      .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
      .build()
    try {
      client().readData(request)
        .addOnSuccessListener { response ->
          if (!response.status.isSuccess) {
            promise.reject(ERR_READ_FAILED, response.status.statusMessage ?: "Read failed")
            return@addOnSuccessListener
          }
          try {
            val intervals = response.buckets.map { b ->
              val count = b.getDataSet(LocalDataType.TYPE_STEP_COUNT_DELTA)
                ?.dataPoints?.sumOf { it.getValue(LocalField.FIELD_STEPS).asInt().toLong() } ?: 0L
              StepInterval(b.getStartTime(TimeUnit.MILLISECONDS), b.getEndTime(TimeUnit.MILLISECONDS), count)
            }
            val result = Arguments.createArray()
            mapIntervals(window, intervals, bucket).forEach { interval ->
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
    (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?)
      ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

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
