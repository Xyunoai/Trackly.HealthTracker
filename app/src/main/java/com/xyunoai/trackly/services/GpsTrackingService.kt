package com.xyunoai.trackly.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationServices
import kotlin.math.round

/**
 * Background service for GPS tracking with distance, speed, and duration monitoring.
 * Uses FusedLocationProviderClient for efficient location updates.
 */
class GpsTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null
    private var totalDistance: Float = 0f // in meters
    private var currentSpeed: Float = 0f // in m/s
    private var maxSpeed: Float = 0f // in m/s
    private var startTime: Long = 0L
    private var isTracking: Boolean = false
    private val binder = GpsTrackingBinder()
    private val listeners = mutableListOf<TrackingListener>()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "gps_tracking_channel"
        private const val UPDATE_INTERVAL = 1000L // 1 second
        private const val FASTEST_UPDATE_INTERVAL = 500L // 0.5 seconds
        private const val SMALL_DISPLACEMENT = 5f // 5 meters
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startTracking()
            ACTION_STOP_TRACKING -> stopTracking()
        }
        return START_STICKY
    }

    /**
     * Setup the location callback to handle location updates
     */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    updateLocation(location)
                }
            }
        }
    }

    /**
     * Start GPS tracking with high accuracy
     */
    private fun startTracking() {
        if (isTracking) return

        isTracking = true
        startTime = System.currentTimeMillis()
        totalDistance = 0f
        currentSpeed = 0f
        maxSpeed = 0f
        lastLocation = null

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            .setMinUpdateDistanceMeters(SMALL_DISPLACEMENT)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            startForeground(NOTIFICATION_ID, getNotification())
            notifyListeners(TrackingState.STARTED)
        } catch (e: SecurityException) {
            e.printStackTrace()
            isTracking = false
        }
    }

    /**
     * Stop GPS tracking
     */
    private fun stopTracking() {
        if (!isTracking) return

        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyListeners(TrackingState.STOPPED)
    }

    /**
     * Update location and calculate distance and speed
     */
    private fun updateLocation(location: Location) {
        currentSpeed = location.speed

        if (currentSpeed > maxSpeed) {
            maxSpeed = currentSpeed
        }

        if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location)
            totalDistance += distance
        }

        lastLocation = location
        notifyListeners(TrackingState.UPDATED)
    }

    /**
     * Get current tracking data
     */
    fun getTrackingData(): TrackingData {
        val elapsedTime = if (isTracking) {
            System.currentTimeMillis() - startTime
        } else {
            0L
        }

        return TrackingData(
            distance = round(totalDistance * 100) / 100,
            currentSpeed = round(currentSpeed * 100) / 100,
            maxSpeed = round(maxSpeed * 100) / 100,
            averageSpeed = if (elapsedTime > 0) {
                round((totalDistance / (elapsedTime / 1000f)) * 100) / 100
            } else {
                0f
            },
            duration = elapsedTime,
            isTracking = isTracking,
            lastLocation = lastLocation,
            startTime = startTime
        )
    }

    /**
     * Add a tracking listener
     */
    fun addListener(listener: TrackingListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a tracking listener
     */
    fun removeListener(listener: TrackingListener) {
        listeners.remove(listener)
    }

    /**
     * Notify all listeners of tracking state changes
     */
    private fun notifyListeners(state: TrackingState) {
        for (listener in listeners) {
            listener.onTrackingStateChanged(state, getTrackingData())
        }
    }

    /**
     * Create and update foreground notification
     */
    private fun getNotification(): NotificationCompat.Notification {
        val data = getTrackingData()
        val durationMinutes = data.duration / 1000 / 60
        val durationSeconds = (data.duration / 1000) % 60

        val contentText = "Distance: ${data.distance}m | Speed: ${data.currentSpeed}m/s | Time: ${durationMinutes}m ${durationSeconds}s"

        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .addAction(0, "Stop", pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Create notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for GPS tracking service"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }

    /**
     * Binder class for binding to this service
     */
    inner class GpsTrackingBinder : Binder() {
        fun getService(): GpsTrackingService = this@GpsTrackingService
    }

    /**
     * Interface for listening to tracking state changes
     */
    interface TrackingListener {
        fun onTrackingStateChanged(state: TrackingState, data: TrackingData)
    }
}

/**
 * Data class for tracking information
 */
data class TrackingData(
    val distance: Float, // in meters
    val currentSpeed: Float, // in m/s
    val maxSpeed: Float, // in m/s
    val averageSpeed: Float, // in m/s
    val duration: Long, // in milliseconds
    val isTracking: Boolean,
    val lastLocation: Location?,
    val startTime: Long
)

/**
 * Enum for tracking states
 */
enum class TrackingState {
    STARTED,
    UPDATED,
    STOPPED
}

// Intent action constants
const val ACTION_START_TRACKING = "com.xyunoai.trackly.ACTION_START_TRACKING"
const val ACTION_STOP_TRACKING = "com.xyunoai.trackly.ACTION_STOP_TRACKING"
