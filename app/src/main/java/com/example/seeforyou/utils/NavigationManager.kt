package com.example.seeforyou.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Manages walking navigation using OpenRouteService API.
 * Tracks GPS position and fires turn-by-turn instructions via callback.
 */
class NavigationManager(private val context: Context) {

    private val apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjllZmU5YzhmYTkzOTQ0YjU4ZjMzNmUxMDk3MjljYWU3IiwiaCI6Im11cm11cjY0In0="
    private val orsBaseUrl = "https://api.openrouteservice.org/v2/directions/foot-walking"

    private val client = OkHttpClient()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    // Navigation state
    private var routeSteps = mutableListOf<RouteStep>()
    private var currentStepIndex = 0
    private var isNavigating = false
    private var currentLocation: Location? = null

    // Callbacks
    var onInstructionReady: ((String, AudioPriorityQueue.Priority) -> Unit)? = null
    var onNavigationComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    data class RouteStep(
        val instruction: String,
        val distanceMeters: Double,
        val lat: Double,
        val lng: Double
    )

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3000L
    ).apply {
        setMinUpdateDistanceMeters(5f)
        setWaitForAccurateLocation(false)
    }.build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                currentLocation = location
                if (isNavigating) {
                    checkRouteProgress(location)
                }
            }
        }
    }

    /**
     * Fetch route from ORS and start navigation.
     * @param destinationName already geocoded or use lat/lng directly
     * @param destLat destination latitude
     * @param destLng destination longitude
     */
    fun startNavigation(destLat: Double, destLng: Double, destinationName: String) {
        getCurrentLocation { originLat, originLng ->
            fetchRoute(originLat, originLng, destLat, destLng, destinationName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(onReady: (Double, Double) -> Unit) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                onReady(location.latitude, location.longitude)
            } else {
                onError?.invoke("Could not get your current location. Please try again.")
            }
        }
    }

    private fun fetchRoute(
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double,
        destinationName: String
    ) {
        val url = "$orsBaseUrl?api_key=$apiKey" +
                "&start=$originLng,$originLat" +
                "&end=$destLng,$destLat"

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json, application/geo+json")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError?.invoke("Navigation unavailable. Check internet connection.")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: run {
                    onError?.invoke("Empty response from navigation server.")
                    return
                }

                try {
                    parseRoute(body, destinationName)
                } catch (e: Exception) {
                    onError?.invoke("Could not parse route. Try a different destination.")
                }
            }
        })
    }

    private fun parseRoute(json: String, destinationName: String) {
        val root = JSONObject(json)
        val features = root.getJSONArray("features")
        if (features.length() == 0) {
            onError?.invoke("No route found to $destinationName.")
            return
        }

        val feature = features.getJSONObject(0)
        val properties = feature.getJSONObject("properties")
        val segments = properties.getJSONArray("segments")
        val segment = segments.getJSONObject(0)

        val totalDistance = segment.getDouble("distance")
        val totalDuration = segment.getDouble("duration")
        val steps = segment.getJSONArray("steps")

        // Parse geometry for step coordinates
        val geometry = feature.getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")

        routeSteps.clear()

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val instruction = step.getString("instruction")
            val distance = step.getDouble("distance")
            val waypointIndex = step.getInt("way_points")

            // Get coordinate for this step
            val coordIndex = minOf(waypointIndex, coordinates.length() - 1)
            val coord = coordinates.getJSONArray(coordIndex)
            val lng = coord.getDouble(0)
            val lat = coord.getDouble(1)

            routeSteps.add(RouteStep(instruction, distance, lat, lng))
        }

        currentStepIndex = 0
        isNavigating = true

        // Announce route summary
        val distanceKm = String.format("%.1f", totalDistance / 1000)
        val durationMin = (totalDuration / 60).toInt()
        val summary = "Route found to $destinationName. " +
                "$distanceKm kilometers, approximately $durationMin minutes walking. " +
                "Starting navigation."

        onInstructionReady?.invoke(summary, AudioPriorityQueue.Priority.INFO)

        // Speak first instruction after short delay
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            speakCurrentStep()
        }, 4000)

        startLocationTracking()
    }

    private fun speakCurrentStep() {
        if (currentStepIndex >= routeSteps.size) return
        val step = routeSteps[currentStepIndex]
        val distanceText = formatDistance(step.distanceMeters)
        val instruction = "${step.instruction}, in $distanceText."
        onInstructionReady?.invoke(instruction, AudioPriorityQueue.Priority.NAVIGATION)
    }

    private fun checkRouteProgress(location: Location) {
        if (currentStepIndex >= routeSteps.size) {
            arriveAtDestination()
            return
        }

        val step = routeSteps[currentStepIndex]
        val stepLocation = Location("step").apply {
            latitude = step.lat
            longitude = step.lng
        }

        val distanceToStep = location.distanceTo(stepLocation)

        // Approaching next waypoint — give advance warning
        if (distanceToStep < 30f && currentStepIndex < routeSteps.size - 1) {
            val nextStep = routeSteps[currentStepIndex + 1]
            onInstructionReady?.invoke(
                "${nextStep.instruction} ahead.",
                AudioPriorityQueue.Priority.NAVIGATION
            )
        }

        // Reached current waypoint — move to next step
        if (distanceToStep < 15f) {
            currentStepIndex++
            if (currentStepIndex >= routeSteps.size) {
                arriveAtDestination()
            } else {
                speakCurrentStep()
            }
        }
    }

    private fun arriveAtDestination() {
        isNavigating = false
        stopLocationTracking()
        onNavigationComplete?.invoke()
        onInstructionReady?.invoke(
            "You have arrived at your destination.",
            AudioPriorityQueue.Priority.INFO
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun stopNavigation() {
        isNavigating = false
        routeSteps.clear()
        currentStepIndex = 0
        stopLocationTracking()
        onInstructionReady?.invoke(
            "Navigation stopped.",
            AudioPriorityQueue.Priority.INFO
        )
    }

    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters < 50   -> "a few steps"
            meters < 100  -> "${meters.toInt()} meters"
            meters < 1000 -> "${(meters / 10).toInt() * 10} meters"
            else          -> String.format("%.1f kilometers", meters / 1000)
        }
    }

    fun isActive() = isNavigating

    fun shutdown() {
        stopNavigation()
        client.dispatcher.cancelAll()
    }
}