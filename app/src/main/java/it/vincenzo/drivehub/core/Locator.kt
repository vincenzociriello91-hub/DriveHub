package it.vincenzo.drivehub.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object Locator {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Posizione attuale (max 10 s), altrimenti l'ultima nota; null senza permesso o segnale */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        for (p in providers) {
            val loc = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(p, null, ctx.mainExecutor) { cont.resume(it) }
                }
            }
            if (loc != null) return loc
        }
        return providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }
}
