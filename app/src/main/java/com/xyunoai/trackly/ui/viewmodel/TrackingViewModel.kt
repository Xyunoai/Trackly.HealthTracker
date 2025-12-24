package com.xyunoai.trackly.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel for managing GPS tracking data, health metrics, and daily summaries.
 * Exposes LiveData for UI consumption and handles lifecycle-aware data management.
 */
class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    // GPS Tracking Data
    private val _gpsTrackingData = MutableLiveData<GpsTrackingData>()
    val gpsTrackingData: LiveData<GpsTrackingData> = _gpsTrackingData

    private val _currentLocation = MutableLiveData<Location>()
    val currentLocation: LiveData<Location> = _currentLocation

    private val _trackingDistance = MutableLiveData<Double>(0.0)
    val trackingDistance: LiveData<Double> = _trackingDistance

    private val _trackingDuration = MutableLiveData<Long>(0L) // in milliseconds
    val trackingDuration: LiveData<Long> = _trackingDuration

    private val _isTracking = MutableLiveData<Boolean>(false)
    val isTracking: LiveData<Boolean> = _isTracking

    // Health Data
    private val _healthData = MutableLiveData<HealthData>()
    val healthData: LiveData<HealthData> = _healthData

    private val _heartRate = MutableLiveData<Int>(0)
    val heartRate: LiveData<Int> = _heartRate

    private val _caloriesBurned = MutableLiveData<Double>(0.0)
    val caloriesBurned: LiveData<Double> = _caloriesBurned

    private val _stepCount = MutableLiveData<Long>(0L)
    val stepCount: LiveData<Long> = _stepCount

    // Daily Summary
    private val _dailySummary = MutableLiveData<DailySummary>()
    val dailySummary: LiveData<DailySummary> = _dailySummary

    private val _totalDistance = MutableLiveData<Double>(0.0)
    val totalDistance: LiveData<Double> = _totalDistance

    private val _activeMinutes = MutableLiveData<Int>(0)
    val activeMinutes: LiveData<Int> = _activeMinutes

    private val _averageHeartRate = MutableLiveData<Int>(0)
    val averageHeartRate: LiveData<Int> = _averageHeartRate

    // UI State Management
    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        initializeData()
    }

    /**
     * Initialize default values for tracking data
     */
    private fun initializeData() {
        _trackingDistance.value = 0.0
        _trackingDuration.value = 0L
        _isTracking.value = false
        _heartRate.value = 0
        _caloriesBurned.value = 0.0
        _stepCount.value = 0L
        _totalDistance.value = 0.0
        _activeMinutes.value = 0
        _averageHeartRate.value = 0
        _isLoading.value = false
    }

    /**
     * Start GPS tracking session
     */
    fun startTracking() {
        viewModelScope.launch {
            try {
                _isTracking.value = true
                _trackingDistance.value = 0.0
                _trackingDuration.value = 0L
                _caloriesBurned.value = 0.0
            } catch (e: Exception) {
                _errorMessage.value = "Failed to start tracking: ${e.message}"
                _isTracking.value = false
            }
        }
    }

    /**
     * Stop GPS tracking session
     */
    fun stopTracking() {
        viewModelScope.launch {
            try {
                _isTracking.value = false
                updateDailySummary()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to stop tracking: ${e.message}"
            }
        }
    }

    /**
     * Update current GPS location
     */
    fun updateLocation(location: Location) {
        _currentLocation.value = location
        updateGpsTrackingData()
    }

    /**
     * Update GPS tracking data with new coordinates and metrics
     */
    fun updateGpsTrackingData(
        distance: Double = _trackingDistance.value ?: 0.0,
        duration: Long = _trackingDuration.value ?: 0L,
        latitude: Double = _currentLocation.value?.latitude ?: 0.0,
        longitude: Double = _currentLocation.value?.longitude ?: 0.0
    ) {
        _gpsTrackingData.value = GpsTrackingData(
            latitude = latitude,
            longitude = longitude,
            distance = distance,
            duration = duration,
            timestamp = System.currentTimeMillis()
        )
        _trackingDistance.value = distance
        _trackingDuration.value = duration
    }

    /**
     * Update health metrics data
     */
    fun updateHealthData(
        heartRate: Int,
        caloriesBurned: Double,
        stepCount: Long
    ) {
        _heartRate.value = heartRate
        _caloriesBurned.value = caloriesBurned
        _stepCount.value = stepCount

        _healthData.value = HealthData(
            heartRate = heartRate,
            caloriesBurned = caloriesBurned,
            stepCount = stepCount,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Update daily summary with aggregated metrics
     */
    fun updateDailySummary(
        totalDistance: Double = _trackingDistance.value ?: 0.0,
        activeMinutes: Int = (_trackingDuration.value ?: 0L).toInt() / 60000,
        averageHeartRate: Int = _heartRate.value ?: 0,
        totalSteps: Long = _stepCount.value ?: 0L,
        totalCalories: Double = _caloriesBurned.value ?: 0.0
    ) {
        val summary = DailySummary(
            date = System.currentTimeMillis(),
            totalDistance = totalDistance,
            activeMinutes = activeMinutes,
            averageHeartRate = averageHeartRate,
            totalSteps = totalSteps,
            totalCalories = totalCalories
        )
        _dailySummary.value = summary
        _totalDistance.value = totalDistance
        _activeMinutes.value = activeMinutes
        _averageHeartRate.value = averageHeartRate
    }

    /**
     * Clear error message
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * Reset all tracking data
     */
    fun resetTracking() {
        initializeData()
    }

    /**
     * Data class for GPS tracking information
     */
    data class GpsTrackingData(
        val latitude: Double,
        val longitude: Double,
        val distance: Double, // in kilometers
        val duration: Long, // in milliseconds
        val timestamp: Long
    )

    /**
     * Data class for health metrics
     */
    data class HealthData(
        val heartRate: Int, // in beats per minute
        val caloriesBurned: Double,
        val stepCount: Long,
        val timestamp: Long
    )

    /**
     * Data class for daily summary
     */
    data class DailySummary(
        val date: Long,
        val totalDistance: Double, // in kilometers
        val activeMinutes: Int,
        val averageHeartRate: Int,
        val totalSteps: Long,
        val totalCalories: Double
    )
}
