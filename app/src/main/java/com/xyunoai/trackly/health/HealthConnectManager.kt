package com.xyunoai.trackly.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HealthConnectManager handles integration with Google Health Connect for reading
 * health data including steps, heart rate, and sleep metrics.
 *
 * @param context The application context for Health Connect initialization
 */
class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    /**
     * List of permissions required for reading health data from Health Connect
     */
    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            // Steps permissions
            HealthPermission.getReadPermission(StepsRecord::class),
            // Heart Rate permissions
            HealthPermission.getReadPermission(HeartRateRecord::class),
            // Sleep Session permissions
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )

        private const val TAG = "HealthConnectManager"
    }

    /**
     * Check if Health Connect is available on the device
     */
    suspend fun isHealthConnectAvailable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            HealthConnectClient.isAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if all required permissions are granted
     */
    suspend fun hasRequiredPermissions(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
            REQUIRED_PERMISSIONS.all { it in grantedPermissions }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read steps data for a specified time range
     *
     * @param startTime The start time for reading data
     * @param endTime The end time for reading data
     * @return List of StepsRecord containing steps data
     */
    suspend fun readStepsData(
        startTime: Instant,
        endTime: Instant
    ): List<StepsRecord> = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.filterIsInstance<StepsRecord>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Read heart rate data for a specified time range
     *
     * @param startTime The start time for reading data
     * @param endTime The end time for reading data
     * @return List of HeartRateRecord containing heart rate data
     */
    suspend fun readHeartRateData(
        startTime: Instant,
        endTime: Instant
    ): List<HeartRateRecord> = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.filterIsInstance<HeartRateRecord>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Read sleep session data for a specified time range
     *
     * @param startTime The start time for reading data
     * @param endTime The end time for reading data
     * @return List of SleepSessionRecord containing sleep data
     */
    suspend fun readSleepData(
        startTime: Instant,
        endTime: Instant
    ): List<SleepSessionRecord> = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.filterIsInstance<SleepSessionRecord>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get total steps for today
     *
     * @return Total steps count for today
     */
    suspend fun getTodaySteps(): Long = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)
            .atZone(ZoneId.systemDefault()).toInstant()

        val stepsRecords = readStepsData(startOfDay, now)
        stepsRecords.sumOf { it.count }
    }

    /**
     * Get average heart rate for today
     *
     * @return Average heart rate in BPM (beats per minute)
     */
    suspend fun getTodayAverageHeartRate(): Double = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)
            .atZone(ZoneId.systemDefault()).toInstant()

        val heartRateRecords = readHeartRateData(startOfDay, now)
        if (heartRateRecords.isEmpty()) {
            return@withContext 0.0
        }

        val totalBpm = heartRateRecords.sumOf { record ->
            record.samples.sumOf { it.beatsPerMinute }
        }
        val totalSamples = heartRateRecords.sumOf { it.samples.size }

        if (totalSamples == 0) 0.0 else totalBpm.toDouble() / totalSamples
    }

    /**
     * Get total sleep duration for today in minutes
     *
     * @return Total sleep duration in minutes
     */
    suspend fun getTodaySleepDuration(): Long = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)
            .atZone(ZoneId.systemDefault()).toInstant()

        val sleepRecords = readSleepData(startOfDay, now)
        sleepRecords.sumOf { record ->
            (record.endTime.toEpochMilli() - record.startTime.toEpochMilli()) / (1000 * 60)
        }
    }

    /**
     * Data class to hold aggregated daily health metrics
     */
    data class DailyHealthMetrics(
        val steps: Long = 0,
        val averageHeartRate: Double = 0.0,
        val sleepDurationMinutes: Long = 0,
        val timestamp: Instant = Instant.now()
    )

    /**
     * Get aggregated daily health metrics
     *
     * @return DailyHealthMetrics containing today's aggregated health data
     */
    suspend fun getDailyHealthMetrics(): DailyHealthMetrics = withContext(Dispatchers.IO) {
        return@withContext DailyHealthMetrics(
            steps = getTodaySteps(),
            averageHeartRate = getTodayAverageHeartRate(),
            sleepDurationMinutes = getTodaySleepDuration(),
            timestamp = Instant.now()
        )
    }
}
