package com.xyunoai.trackly.core

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * TracklyHealthTracker - Comprehensive health and fitness tracking system
 * 
 * Features:
 * - GPS-based location and distance tracking
 * - Accelerometer-based step counting
 * - Activity recognition (walking, running, cycling, etc.)
 * - Health Connect integration for data synchronization
 * - Real-time health metrics collection
 * - Battery-optimized background tracking
 */
class TracklyHealthTracker(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    // Location Services
    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    // Sensor Services
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Health Connect
    @RequiresApi(Build.VERSION_CODES.P)
    private val healthConnectClient: HealthConnectClient? = try {
        HealthConnectClient.getOrCreate(context)
    } catch (e: Exception) {
        null
    }

    // Data tracking state
    private var isTracking = false
    private val trackingData = TrackingData()

    // Sensors
    private var stepCounterSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null

    // Location request
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        TimeUnit.SECONDS.toMillis(2)
    ).apply {
        setMinUpdateDistanceMeters(5f)
        setWaitForAccurateLocation(true)
    }.build()

    // Callbacks
    private var onLocationUpdateListener: ((Location) -> Unit)? = null
    private var onStepCountUpdateListener: ((Int) -> Unit)? = null
    private var onActivityChangeListener: ((ActivityType, Float) -> Unit)? = null
    private var onMetricsUpdateListener: ((HealthMetrics) -> Unit)? = null

    init {
        initializeSensors()
    }

    /**
     * Initialize available sensors
     */
    private fun initializeSensors() {
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    /**
     * Start comprehensive health tracking
     */
    fun startTracking() {
        if (isTracking) return

        isTracking = true
        trackingData.startTime = System.currentTimeMillis()
        trackingData.isActive = true

        scope.launch {
            startGPSTracking()
            startSensorTracking()
            startActivityRecognition()
            startHealthConnectSync()
        }
    }

    /**
     * Stop health tracking
     */
    fun stopTracking() {
        if (!isTracking) return

        isTracking = false
        trackingData.endTime = System.currentTimeMillis()
        trackingData.isActive = false

        stopGPSTracking()
        stopSensorTracking()

        scope.launch {
            syncToHealthConnect()
        }
    }

    // ==================== GPS TRACKING ====================

    private var locationCallback: LocationCallback? = null

    private suspend fun startGPSTracking() = withContext(Dispatchers.Main) {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null
            )
        } catch (e: SecurityException) {
            // Handle permission error
        }
    }

    private fun stopGPSTracking() {
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: SecurityException) {
                // Handle permission error
            }
        }
    }

    private fun updateLocation(location: Location) {
        trackingData.currentLocation = location

        // Calculate distance from last location
        if (trackingData.lastLocation != null) {
            val distance = trackingData.lastLocation!!.distanceTo(location)
            trackingData.totalDistance += distance
            trackingData.recentLocations.add(location)
            if (trackingData.recentLocations.size > 100) {
                trackingData.recentLocations.removeAt(0)
            }
        }

        trackingData.lastLocation = location

        // Update altitude gain
        val currentAltitude = location.altitude
        if (currentAltitude > 0) {
            if (trackingData.lastAltitude > 0) {
                val altitudeDiff = currentAltitude - trackingData.lastAltitude
                if (altitudeDiff > 0) {
                    trackingData.totalAltitudeGain += altitudeDiff
                }
            }
            trackingData.lastAltitude = currentAltitude
        }

        // Calculate pace and speed
        if (trackingData.totalDistance > 0) {
            val elapsedTime = (System.currentTimeMillis() - trackingData.startTime) / 1000.0 / 60.0 // minutes
            trackingData.pace = if (elapsedTime > 0) trackingData.totalDistance / elapsedTime else 0.0
        }

        onLocationUpdateListener?.invoke(location)

        scope.launch {
            updateMetrics()
        }
    }

    // ==================== STEP COUNTER & ACCELEROMETER ====================

    private val sensorEventListener = object : SensorEventListener {
        private var stepOffset = 0
        private var lastStepCount = 0

        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                when (it.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER -> {
                        val stepCount = it.values[0].toInt()
                        if (stepOffset == 0) {
                            stepOffset = stepCount - lastStepCount
                        }
                        val steps = stepCount - stepOffset
                        trackingData.stepCount = steps
                        onStepCountUpdateListener?.invoke(steps)
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        analyzeAccelerometerData(it.values)
                    }

                    Sensor.TYPE_GYROSCOPE -> {
                        analyzeGyroscopeData(it.values)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensorTracking() {
        stepCounterSensor?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        accelerometerSensor?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        gyroscopeSensor?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    private fun stopSensorTracking() {
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun analyzeAccelerometerData(values: FloatArray) {
        if (values.size < 3) return

        val x = values[0]
        val y = values[1]
        val z = values[2]

        val magnitude = sqrt(x * x + y * y + z * z)

        // Detect movement intensity
        trackingData.accelerometerMagnitude = magnitude

        // Update cadence estimation
        if (magnitude > MOVEMENT_THRESHOLD) {
            trackingData.lastMovementTime = System.currentTimeMillis()
            trackingData.movementDetected = true
        }

        // Estimate step cadence from acceleration peaks
        detectStepPeaks(magnitude)
    }

    private fun analyzeGyroscopeData(values: FloatArray) {
        if (values.size < 3) return

        val rotationRate = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        trackingData.rotationRate = rotationRate
    }

    private var lastPeakTime = 0L
    private var lastPeakValue = 0f

    private fun detectStepPeaks(magnitude: Float) {
        val currentTime = System.currentTimeMillis()

        // Detect peaks (steps) - magnitude crosses threshold
        if (magnitude > MOVEMENT_THRESHOLD && lastPeakValue <= MOVEMENT_THRESHOLD) {
            val timeSinceLastPeak = currentTime - lastPeakTime
            if (timeSinceLastPeak > MIN_STEP_INTERVAL && timeSinceLastPeak < MAX_STEP_INTERVAL) {
                trackingData.cadence = (60000.0 / timeSinceLastPeak).toInt() // steps per minute
            }
            lastPeakTime = currentTime
        }

        lastPeakValue = magnitude
    }

    // ==================== ACTIVITY RECOGNITION ====================

    private suspend fun startActivityRecognition() = withContext(Dispatchers.Default) {
        while (isTracking) {
            recognizeActivity()
            delay(ACTIVITY_RECOGNITION_INTERVAL)
        }
    }

    private fun recognizeActivity() {
        val activity = classifyActivity()
        trackingData.currentActivity = activity

        // Calculate confidence based on motion data
        val confidence = calculateActivityConfidence(activity)
        onActivityChangeListener?.invoke(activity, confidence)
    }

    private fun classifyActivity(): ActivityType {
        return when {
            !trackingData.movementDetected -> ActivityType.STATIONARY

            trackingData.currentLocation?.speed?.let { it > 3.5 } == true -> {
                // Speed > 3.5 m/s - likely running
                ActivityType.RUNNING
            }

            trackingData.currentLocation?.speed?.let { it > 1.4 } == true -> {
                // Speed 1.4-3.5 m/s - likely walking
                ActivityType.WALKING
            }

            trackingData.rotationRate > ROTATION_THRESHOLD_CYCLING -> {
                // High rotation rate - likely cycling
                ActivityType.CYCLING
            }

            trackingData.accelerometerMagnitude > INTENSITY_THRESHOLD_HIGH -> {
                // High intensity movement
                ActivityType.INTENSE_EXERCISE
            }

            trackingData.cadence in 120..160 -> {
                // Normal step cadence - walking
                ActivityType.WALKING
            }

            trackingData.cadence in 161..200 -> {
                // High step cadence - running
                ActivityType.RUNNING
            }

            else -> ActivityType.UNKNOWN
        }
    }

    private fun calculateActivityConfidence(activity: ActivityType): Float {
        var confidence = 0.5f

        trackingData.currentLocation?.speed?.let { speed ->
            when (activity) {
                ActivityType.WALKING -> confidence = minOf(1f, 0.5f + (speed / 2).toFloat())
                ActivityType.RUNNING -> confidence = minOf(1f, 0.5f + (speed / 4).toFloat())
                ActivityType.CYCLING -> confidence = minOf(1f, 0.6f + (speed / 10).toFloat())
                else -> {}
            }
        }

        if (trackingData.movementDetected) {
            confidence += 0.2f
        }

        return minOf(1f, confidence)
    }

    // ==================== METRICS CALCULATION ====================

    private suspend fun updateMetrics() = withContext(Dispatchers.Default) {
        val metrics = HealthMetrics(
            timestamp = System.currentTimeMillis(),
            distance = trackingData.totalDistance,
            steps = trackingData.stepCount,
            calories = calculateCalories(),
            heartRate = 0, // Would integrate with wearable
            pace = trackingData.pace,
            speed = trackingData.currentLocation?.speed ?: 0f,
            altitude = trackingData.currentLocation?.altitude ?: 0.0,
            altitudeGain = trackingData.totalAltitudeGain,
            cadence = trackingData.cadence,
            activity = trackingData.currentActivity,
            accuracy = trackingData.currentLocation?.accuracy ?: 0f
        )

        onMetricsUpdateListener?.invoke(metrics)
    }

    private fun calculateCalories(): Double {
        // Using Harris-Benedict equation adjusted for activity
        val durationMinutes = (System.currentTimeMillis() - trackingData.startTime) / 60000.0
        val mets = when (trackingData.currentActivity) {
            ActivityType.STATIONARY -> 1.0
            ActivityType.WALKING -> 3.5
            ActivityType.RUNNING -> 9.8
            ActivityType.CYCLING -> 7.5
            ActivityType.INTENSE_EXERCISE -> 12.0
            ActivityType.UNKNOWN -> 2.0
        }

        // Rough estimation: average person weight 70kg
        return (mets * 70 * durationMinutes) / 60.0
    }

    // ==================== HEALTH CONNECT INTEGRATION ====================

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun startHealthConnectSync() = withContext(Dispatchers.IO) {
        while (isTracking) {
            syncToHealthConnect()
            delay(HEALTH_CONNECT_SYNC_INTERVAL)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun syncToHealthConnect() {
        healthConnectClient?.let { client ->
            try {
                val records = mutableListOf<Record>()

                // Sync distance
                if (trackingData.totalDistance > 0) {
                    records.add(
                        DistanceRecord(
                            distance = trackingData.totalDistance.toLong() * 1000, // to meters
                            startTime = Instant.ofEpochMilli(trackingData.startTime),
                            endTime = Instant.now(),
                            startZoneOffset = null,
                            endZoneOffset = null
                        )
                    )
                }

                // Sync steps
                if (trackingData.stepCount > 0) {
                    records.add(
                        StepsRecord(
                            count = trackingData.stepCount.toLong(),
                            startTime = Instant.ofEpochMilli(trackingData.startTime),
                            endTime = Instant.now(),
                            startZoneOffset = null,
                            endZoneOffset = null
                        )
                    )
                }

                // Sync calories
                val calories = calculateCalories()
                if (calories > 0) {
                    records.add(
                        ActiveCaloriesBurnedRecord(
                            energy = calories.toDouble() * 4184, // to joules
                            startTime = Instant.ofEpochMilli(trackingData.startTime),
                            endTime = Instant.now(),
                            startZoneOffset = null,
                            endZoneOffset = null
                        )
                    )
                }

                // Sync elevation
                if (trackingData.totalAltitudeGain > 0) {
                    records.add(
                        ElevationGainedRecord(
                            elevation = trackingData.totalAltitudeGain,
                            startTime = Instant.ofEpochMilli(trackingData.startTime),
                            endTime = Instant.now(),
                            startZoneOffset = null,
                            endZoneOffset = null
                        )
                    )
                }

                if (records.isNotEmpty()) {
                    client.insertRecords(records)
                }
            } catch (e: Exception) {
                // Handle sync error
            }
        }
    }

    /**
     * Read health data from Health Connect
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun readHealthData(
        recordType: Class<out Record>,
        timeRangeFilter: TimeRangeFilter
    ): List<Record> = withContext(Dispatchers.IO) {
        return@withContext try {
            healthConnectClient?.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = timeRangeFilter,
                    pageSize = 100
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Request Health Connect permissions
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun requestHealthPermissions(
        permissions: Set<String> = DEFAULT_HEALTH_PERMISSIONS
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            healthConnectClient?.requestPermissions(permissions.toSet())
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== CALLBACKS ====================

    fun setOnLocationUpdateListener(listener: (Location) -> Unit) {
        onLocationUpdateListener = listener
    }

    fun setOnStepCountUpdateListener(listener: (Int) -> Unit) {
        onStepCountUpdateListener = listener
    }

    fun setOnActivityChangeListener(listener: (ActivityType, Float) -> Unit) {
        onActivityChangeListener = listener
    }

    fun setOnMetricsUpdateListener(listener: (HealthMetrics) -> Unit) {
        onMetricsUpdateListener = listener
    }

    // ==================== DATA ACCESS ====================

    fun getTrackingData(): TrackingData = trackingData.copy()

    fun isCurrentlyTracking(): Boolean = isTracking

    fun getTotalDistance(): Double = trackingData.totalDistance

    fun getStepCount(): Int = trackingData.stepCount

    fun getPace(): Double = trackingData.pace

    fun getAltitudeGain(): Double = trackingData.totalAltitudeGain

    fun getElapsedTime(): Long = System.currentTimeMillis() - trackingData.startTime

    fun getCurrentActivity(): ActivityType = trackingData.currentActivity

    fun getRecentLocations(): List<Location> = trackingData.recentLocations.toList()

    // ==================== CLEANUP ====================

    fun release() {
        stopTracking()
        scope.cancel()
    }

    // ==================== DATA CLASSES ====================

    data class TrackingData(
        var startTime: Long = 0L,
        var endTime: Long = 0L,
        var isActive: Boolean = false,
        var totalDistance: Double = 0.0,
        var stepCount: Int = 0,
        var totalAltitudeGain: Double = 0.0,
        var pace: Double = 0.0,
        var cadence: Int = 0,
        var currentActivity: ActivityType = ActivityType.STATIONARY,
        var currentLocation: Location? = null,
        var lastLocation: Location? = null,
        var lastAltitude: Double = -1.0,
        var recentLocations: MutableList<Location> = mutableListOf(),
        var accelerometerMagnitude: Float = 0f,
        var rotationRate: Float = 0f,
        var movementDetected: Boolean = false,
        var lastMovementTime: Long = 0L
    ) {
        fun copy(): TrackingData = TrackingData(
            startTime = startTime,
            endTime = endTime,
            isActive = isActive,
            totalDistance = totalDistance,
            stepCount = stepCount,
            totalAltitudeGain = totalAltitudeGain,
            pace = pace,
            cadence = cadence,
            currentActivity = currentActivity,
            currentLocation = currentLocation,
            lastLocation = lastLocation,
            lastAltitude = lastAltitude,
            recentLocations = recentLocations.toMutableList(),
            accelerometerMagnitude = accelerometerMagnitude,
            rotationRate = rotationRate,
            movementDetected = movementDetected,
            lastMovementTime = lastMovementTime
        )
    }

    data class HealthMetrics(
        val timestamp: Long,
        val distance: Double,
        val steps: Int,
        val calories: Double,
        val heartRate: Int,
        val pace: Double,
        val speed: Float,
        val altitude: Double,
        val altitudeGain: Double,
        val cadence: Int,
        val activity: ActivityType,
        val accuracy: Float
    )

    enum class ActivityType {
        STATIONARY,
        WALKING,
        RUNNING,
        CYCLING,
        INTENSE_EXERCISE,
        UNKNOWN
    }

    companion object {
        // Sensor thresholds
        private const val MOVEMENT_THRESHOLD = 0.5f
        private const val INTENSITY_THRESHOLD_HIGH = 25f
        private const val ROTATION_THRESHOLD_CYCLING = 2.0f

        // Step detection
        private const val MIN_STEP_INTERVAL = 300L // milliseconds
        private const val MAX_STEP_INTERVAL = 1500L // milliseconds

        // Update intervals
        private const val ACTIVITY_RECOGNITION_INTERVAL = 1000L // milliseconds
        private const val HEALTH_CONNECT_SYNC_INTERVAL = 60000L // milliseconds

        // Health Connect permissions
        val DEFAULT_HEALTH_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(ElevationGainedRecord::class)
        )
    }
}
