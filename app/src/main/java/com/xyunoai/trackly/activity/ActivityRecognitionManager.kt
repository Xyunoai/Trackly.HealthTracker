package com.xyunoai.trackly.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

/**
 * Manager class for Activity Recognition API integration
 * Detects physical activities such as walking, running, and being still
 */
class ActivityRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "ActivityRecognition"
        private const val DETECTION_INTERVAL_MILLIS = 5000L // 5 seconds
        private const val ACTION_ACTIVITY_UPDATE = "com.xyunoai.trackly.ACTIVITY_UPDATE"
        private const val EXTRA_ACTIVITY = "activity_type"
        private const val EXTRA_CONFIDENCE = "activity_confidence"
    }

    private var activityRecognitionCallback: ((DetectedActivityData) -> Unit)? = null

    /**
     * Data class to hold detected activity information
     */
    data class DetectedActivityData(
        val activityType: String,
        val confidence: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Start activity recognition updates
     * @param callback Lambda function to handle detected activities
     */
    fun startActivityRecognition(callback: (DetectedActivityData) -> Unit) {
        activityRecognitionCallback = callback
        
        val intent = Intent(context, ActivityRecognitionReceiver::class.java)
        intent.action = ACTION_ACTIVITY_UPDATE
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val task: Task<Void> = ActivityRecognition.getClient(context)
            .requestActivityUpdates(DETECTION_INTERVAL_MILLIS, pendingIntent)

        task.addOnSuccessListener {
            Log.d(TAG, "Activity recognition started successfully")
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to start activity recognition: ${exception.message}")
        }
    }

    /**
     * Stop activity recognition updates
     */
    fun stopActivityRecognition() {
        val intent = Intent(context, ActivityRecognitionReceiver::class.java)
        intent.action = ACTION_ACTIVITY_UPDATE
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        ActivityRecognition.getClient(context)
            .removeActivityUpdates(pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "Activity recognition stopped successfully")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to stop activity recognition: ${exception.message}")
            }
    }

    /**
     * Process activity recognition results
     */
    fun handleActivityRecognitionResult(intent: Intent?) {
        if (intent != null && ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent)
            result?.let {
                val detectedActivities = it.probableActivities
                
                // Get the most probable activity
                val mostProbableActivity = detectedActivities.maxByOrNull { activity ->
                    activity.confidence
                }

                mostProbableActivity?.let { activity ->
                    val activityData = DetectedActivityData(
                        activityType = getActivityName(activity.type),
                        confidence = activity.confidence
                    )
                    
                    Log.d(TAG, "Activity detected: ${activityData.activityType} " +
                            "(Confidence: ${activityData.confidence}%)")
                    
                    // Invoke the callback
                    activityRecognitionCallback?.invoke(activityData)
                }
            }
        }
    }

    /**
     * Convert activity type code to human-readable string
     */
    private fun getActivityName(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.STILL -> "Still"
            DetectedActivity.WALKING -> "Walking"
            DetectedActivity.RUNNING -> "Running"
            DetectedActivity.IN_VEHICLE -> "In Vehicle"
            DetectedActivity.TILTING -> "Tilting"
            DetectedActivity.EXITING_VEHICLE -> "Exiting Vehicle"
            DetectedActivity.ON_BICYCLE -> "On Bicycle"
            DetectedActivity.ON_FOOT -> "On Foot"
            else -> "Unknown"
        }
    }

    /**
     * Get activity type from string
     */
    fun getActivityTypeCode(activityName: String): Int {
        return when (activityName.lowercase()) {
            "still" -> DetectedActivity.STILL
            "walking" -> DetectedActivity.WALKING
            "running" -> DetectedActivity.RUNNING
            "in vehicle" -> DetectedActivity.IN_VEHICLE
            "tilting" -> DetectedActivity.TILTING
            "exiting vehicle" -> DetectedActivity.EXITING_VEHICLE
            "on bicycle" -> DetectedActivity.ON_BICYCLE
            "on foot" -> DetectedActivity.ON_FOOT
            else -> -1
        }
    }

    /**
     * Check if activity is walking
     */
    fun isWalking(activityData: DetectedActivityData): Boolean {
        return activityData.activityType == "Walking" && activityData.confidence >= 50
    }

    /**
     * Check if activity is running
     */
    fun isRunning(activityData: DetectedActivityData): Boolean {
        return activityData.activityType == "Running" && activityData.confidence >= 50
    }

    /**
     * Check if activity is still
     */
    fun isStill(activityData: DetectedActivityData): Boolean {
        return activityData.activityType == "Still" && activityData.confidence >= 50
    }

    /**
     * Check if device is moving
     */
    fun isMoving(activityData: DetectedActivityData): Boolean {
        return (activityData.activityType in listOf("Walking", "Running", "On Foot", "On Bicycle") 
                && activityData.confidence >= 50)
    }
}
