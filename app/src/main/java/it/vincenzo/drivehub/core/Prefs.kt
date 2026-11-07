package it.vincenzo.drivehub.core

import android.content.Context

data class ParkingSpot(val lat: Double, val lon: Double, val time: Long)

object Prefs {
    /** "wifi" per adattatori Wi-Fi, altrimenti indirizzo MAC Bluetooth; null = nessuno scelto */
    const val OBD_WIFI = "wifi"
    /** Adattatore USB con cavo OTG */
    const val OBD_USB = "usb"
    /** Prefisso per adattatori Bluetooth LE: "ble:<indirizzo>" */
    const val OBD_BLE = "ble:"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("drivehub", Context.MODE_PRIVATE)

    fun obdDevice(ctx: Context): String? = sp(ctx).getString("obd_device", null)
    fun setObdDevice(ctx: Context, v: String) = sp(ctx).edit().putString("obd_device", v).apply()

    fun fuel(ctx: Context): String = sp(ctx).getString("fuel", "Benzina")!!
    fun setFuel(ctx: Context, v: String) = sp(ctx).edit().putString("fuel", v).apply()

    /** Ultimo prezzo migliore trovato: serve per stimare il costo dei viaggi */
    fun lastFuelPrice(ctx: Context): Double? = sp(ctx).getFloat("fuel_price", 0f).takeIf { it > 0 }?.toDouble()
    fun lastFuelName(ctx: Context): String = sp(ctx).getString("fuel_name", "")!!
    fun setLastFuel(ctx: Context, name: String, price: Double) =
        sp(ctx).edit().putString("fuel_name", name).putFloat("fuel_price", price.toFloat()).apply()

    /** Contachilometri stimato: lo imposta Vincenzo, poi cresce con i km dei viaggi (0 = non impostato) */
    fun odometerKm(ctx: Context): Double = sp(ctx).getLong("odo_m", 0) / 1000.0
    fun setOdometerKm(ctx: Context, km: Double) = sp(ctx).edit().putLong("odo_m", (km * 1000).toLong()).apply()
    fun addOdometer(ctx: Context, km: Double) {
        val odo = odometerKm(ctx)
        if (odo > 0) setOdometerKm(ctx, odo + km)
    }

    fun nextServiceKm(ctx: Context): Int = sp(ctx).getInt("next_service", 0)
    fun nextTyresKm(ctx: Context): Int = sp(ctx).getInt("next_tyres", 0)
    fun setMaintenance(ctx: Context, service: Int, tyres: Int) =
        sp(ctx).edit().putInt("next_service", service).putInt("next_tyres", tyres).apply()

    fun parking(ctx: Context): ParkingSpot? {
        val p = sp(ctx).getString("parking", null)?.split(',') ?: return null
        return runCatching { ParkingSpot(p[0].toDouble(), p[1].toDouble(), p[2].toLong()) }.getOrNull()
    }
    fun setParking(ctx: Context, lat: Double, lon: Double, time: Long) =
        sp(ctx).edit().putString("parking", "$lat,$lon,$time").apply()

    fun parkingEnd(ctx: Context): Long = sp(ctx).getLong("parking_end", 0)
    fun setParkingEnd(ctx: Context, t: Long) = sp(ctx).edit().putLong("parking_end", t).apply()

    fun autoMusic(ctx: Context): Boolean = sp(ctx).getBoolean("auto_music", false)
    fun setAutoMusic(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_music", v).apply()
    fun autoDashcam(ctx: Context): Boolean = sp(ctx).getBoolean("auto_dashcam", false)
    fun setAutoDashcam(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_dashcam", v).apply()

    // vero dopo la prima volta che lo schermo DriveHub si è aperto in Android Auto (lista di avvio nell'app)
    fun carSeen(ctx: Context): Boolean = sp(ctx).getBoolean("car_seen", false)
    fun setCarSeen(ctx: Context) = sp(ctx).edit().putBoolean("car_seen", true).apply()

    /** Scadenze per data ("bollo", "assicurazione", "revisione") come giorno epoch; 0 = non impostata */
    val DEADLINES = listOf("bollo", "assicurazione", "revisione")
    fun deadline(ctx: Context, what: String): Long = sp(ctx).getLong("scad_$what", 0)
    fun setDeadline(ctx: Context, what: String, epochDay: Long) = sp(ctx).edit().putLong("scad_$what", epochDay).apply()

    /** Ultime app aperte sul display dell'auto (componenti, la più recente prima) */
    fun recentApps(ctx: Context): List<String> = sp(ctx).getString("recent_apps", "")!!.split('\n').filter { it.isNotEmpty() }
    fun addRecentApp(ctx: Context, component: String) =
        sp(ctx).edit().putString("recent_apps", (listOf(component) + recentApps(ctx).minus(component)).take(4).joinToString("\n")).apply()
}
