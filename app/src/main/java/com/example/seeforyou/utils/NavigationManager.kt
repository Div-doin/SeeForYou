package com.example.seeforyou.utils

import android.annotation.SuppressLint
import com.example.seeforyou.R
import com.example.seeforyou.localization.AppLanguage
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Route state and callbacks are confined to the main thread. */
class NavigationManager(private val context: Context) {
    private val apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjllZmU5YzhmYTkzOTQ0YjU4ZjMzNmUxMDk3MjljYWU3IiwiaCI6Im11cm11cjY0In0="
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var call: Call? = null
    private var locationToken: CancellationTokenSource? = null
    private var guidance: WalkingGuidance? = null
    private var destination: RoutePoint? = null
    private var destinationName = ""
    private var active = false
    private var fetching = false
    private var lastReroute = 0L
    private var gpsWarning = false
    private var lastFixAt = 0L
    private val gpsWatchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (!fetching && SystemClock.elapsedRealtime() - lastFixAt > 15000 && !gpsWarning) {
                gpsWarning = true
                say(context.getString(R.string.gps_unavailable))
            }
            main.postDelayed(this, 5000)
        }
    }
    var onInstructionReady: ((String, AudioPriorityQueue.Priority) -> Unit)? = null
    var onNavigationComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private fun say(text: String) {
        onInstructionReady?.invoke(text, AudioPriorityQueue.Priority.NAVIGATION)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!active || fetching) return
            val location = result.lastLocation ?: return
            if (!usable(location)) {
                if (!gpsWarning) say(context.getString(R.string.gps_inaccurate))
                gpsWarning = true
                return
            }
            if (gpsWarning) {
                gpsWarning = false
                say(context.getString(R.string.gps_recovered, guidance?.currentInstruction().orEmpty()))
            }
            lastFixAt = SystemClock.elapsedRealtime()
            val update = guidance?.update(RoutePoint(location.latitude, location.longitude), location.accuracy) ?: return
            if (update.reroute) {
                if (SystemClock.elapsedRealtime() - lastReroute >= 30000) {
                    lastReroute = SystemClock.elapsedRealtime()
                    say(context.getString(R.string.off_route))
                    fetchRoute(location, generation, true)
                }
                return
            }
            update.speech?.let { say(it) }
            if (update.arrived) {
                clearSession()
                onNavigationComplete?.invoke()
            }
        }
    }

    private fun usable(location: Location): Boolean =
        location.hasAccuracy() && location.accuracy in 0.1f..25f &&
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..15_000_000_000L

    @SuppressLint("MissingPermission")
    fun startNavigation(destLat: Double, destLng: Double, name: String) {
        clearSession()
        destination = RoutePoint(destLat, destLng)
        destinationName = name
        val session = generation
        say(context.getString(R.string.fresh_gps))
        val token = CancellationTokenSource()
        locationToken = token
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5000).setDurationMillis(20000).build()
        try {
            locationClient.getCurrentLocation(request, token.token)
                .addOnSuccessListener { location ->
                    if (session != generation) return@addOnSuccessListener
                    if (location == null || !usable(location)) {
                        fail(context.getString(R.string.gps_failed))
                    } else fetchRoute(location, session, false)
                }
                .addOnFailureListener {
                    if (session == generation) fail(context.getString(R.string.location_unavailable))
                }
        } catch (_: SecurityException) {
            fail(context.getString(R.string.location_permission))
        }
    }

    private fun fetchRoute(origin: Location, session: Int, rerouting: Boolean) {
        val target = destination ?: return
        fetching = true
        val request = Request.Builder()
            .url("https://api.openrouteservice.org/v2/directions/foot-walking?start=${origin.longitude},${origin.latitude}&end=${target.lon},${target.lat}")
            .header("Authorization", apiKey).header("Accept", "application/geo+json").build()
        call = client.newCall(request)
        call!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { if (session == generation) fail(context.getString(R.string.route_unavailable)) }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val route = try {
                        if (!it.isSuccessful) throw IOException("Routing service rejected request")
                        WalkingRoute.parse(it.body?.string() ?: throw IOException("Empty response"), AppLanguage.book(context))
                    } catch (_: Exception) {
                        main.post { if (session == generation) fail(context.getString(R.string.route_invalid)) }
                        return
                    }
                    main.post {
                        if (session != generation) return@post
                        fetching = false
                        guidance = WalkingGuidance(route, AppLanguage.book(context))
                        active = true
                        lastFixAt = SystemClock.elapsedRealtime()
                        main.removeCallbacks(gpsWatchdog)
                        main.postDelayed(gpsWatchdog, 5000)
                        lastReroute = SystemClock.elapsedRealtime()
                        val distance = if (route.meters < 1000) context.getString(R.string.meters, route.meters.toInt())
                            else context.getString(R.string.kilometers, route.meters / 1000)
                        say(context.getString(R.string.route_summary, destinationName, distance,
                            ceil(route.seconds / 60).toInt().coerceAtLeast(1), guidance!!.currentInstruction()))
                        startTracking()
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        val session = generation
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateDistanceMeters(1f).build()
        try {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener { if (session == generation) fail(context.getString(R.string.tracking_failed)) }
        } catch (_: SecurityException) {
            fail(context.getString(R.string.permission_removed))
        }
    }

    private fun fail(message: String) {
        clearSession()
        onError?.invoke(message)
    }

    private fun clearSession() {
        generation++
        main.removeCallbacks(gpsWatchdog)
        call?.cancel()
        call = null
        locationToken?.cancel()
        locationToken = null
        locationClient.removeLocationUpdates(locationCallback)
        active = false
        fetching = false
        guidance = null
        destination = null
        gpsWarning = false
    }

    fun repeatInstruction() {
        if (fetching) say(context.getString(R.string.recalculating))
        else if (gpsWarning) say(context.getString(R.string.waiting_gps))
        else guidance?.let { say(it.currentInstruction()) }
    }
    fun stopNavigation() { clearSession(); say(context.getString(R.string.nav_stopped)) }
    fun isActive() = active
    fun shutdown() {
        clearSession()
        onInstructionReady = null
        onNavigationComplete = null
        onError = null
    }
}
