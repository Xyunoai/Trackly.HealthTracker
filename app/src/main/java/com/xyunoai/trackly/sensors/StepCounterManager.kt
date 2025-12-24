package com.xyunoai.trackly.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StepCounterManager manages the Android Step Counter sensor for real-time step counting.
 * Handles runtime permissions and provides real-time updates via StateFlow.
 *
 * @param context The application context
 */
class StepCounterManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    // StateFlow for real-time step updates
    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

    // StateFlow for tracking permission status
    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    // StateFlow for sensor availability status
    private val _sensorAvailable = MutableStateFlow(false)
    val sensorAvailable: StateFlow<Boolean> = _sensorAvailable.asStateFlow()

    // Track initial step count offset
    private var initialStepOffset = 0
    private var isListening = false

    init {
        _sensorAvailable.value = stepCounterSensor != null
        checkPermissionStatus()
    }

    /**
     * Check if the required permission is granted
     */
    private fun checkPermissionStatus() {
        _permissionGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permissions not required for Android versions below Q
        }
    }

    /**
     * Request runtime permissions for step counter sensor access
     * This should be called from an Activity context
     */
    fun requestPermissions(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val permissionLauncher = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                _permissionGranted.value = isGranted
                if (isGranted) {
                    startListening()
                }
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BODY_SENSORS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
            }
        }
    }

    /**
     * Start listening to step counter sensor updates
     * Only proceeds if permission is granted and sensor is available
     */
    fun startListening() {
        if (!_permissionGranted.value || !_sensorAvailable.value) {
            return
        }

        if (stepCounterSensor != null && !isListening) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            isListening = true
        }
    }

    /**
     * Stop listening to step counter sensor updates
     */
    fun stopListening() {
        if (isListening) {
            sensorManager.unregisterListener(this, stepCounterSensor)
            isListening = false
        }
    }

    /**
     * Reset step counter to zero
     */
    fun resetStepCount() {
        initialStepOffset = 0
        _stepCount.value = 0
    }

    /**
     * Get current step count
     */
    fun getCurrentStepCount(): Int {
        return _stepCount.value
    }

    /**
     * Called when sensor accuracy changes
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    /**
     * Called when sensor values change
     * Updates the step count in real-time
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val steps = event.values[0].toInt()

                // On first reading, store the offset
                if (initialStepOffset == 0) {
                    initialStepOffset = steps
                }

                // Calculate steps relative to when we started listening
                val adjustedSteps = steps - initialStepOffset
                _stepCount.value = maxOf(0, adjustedSteps) // Ensure non-negative
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                // Step detector fires for each step
                _stepCount.value += 1
            }
        }
    }

    /**
     * Check if the device has a step counter sensor
     */
    fun hasStepCounterSensor(): Boolean {
        return stepCounterSensor != null
    }

    /**
     * Check if the device has a step detector sensor
     */
    fun hasStepDetectorSensor(): Boolean {
        return stepDetectorSensor != null
    }

    /**
     * Get sensor resolution in steps/second
     */
    fun getSensorResolution(): Float {
        return stepCounterSensor?.resolution ?: 0f
    }

    /**
     * Get sensor power consumption in mA
     */
    fun getSensorPower(): Float {
        return stepCounterSensor?.power ?: 0f
    }

    /**
     * Get sensor max range
     */
    fun getSensorMaxRange(): Float {
        return stepCounterSensor?.maxRange ?: 0f
    }

    /**
     * Cleanup resources when the manager is no longer needed
     */
    fun cleanup() {
        stopListening()
    }

    /**
     * Get detailed sensor information for debugging
     */
    fun getSensorInfo(): String {
        return buildString {
            appendLine("=== Step Counter Sensor Info ===")
            appendLine("Sensor Available: ${_sensorAvailable.value}")
            appendLine("Permission Granted: ${_permissionGranted.value}")
            appendLine("Currently Listening: $isListening")
            appendLine("Current Step Count: ${_stepCount.value}")

            stepCounterSensor?.let {
                appendLine("\nSensor Details:")
                appendLine("Name: ${it.name}")
                appendLine("Vendor: ${it.vendor}")
                appendLine("Version: ${it.version}")
                appendLine("Power (mA): ${it.power}")
                appendLine("Resolution: ${it.resolution}")
                appendLine("Max Range: ${it.maxRange}")
                appendLine("Min Delay (microseconds): ${it.minDelay}")
                appendLine("Max Delay (microseconds): ${it.maxDelay}")
                appendLine("Reporting Mode: ${it.reportingMode}")
            } ?: appendLine("\nStep Counter Sensor: Not available")
        }
    }
}
