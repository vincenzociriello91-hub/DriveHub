package it.vincenzo.drivehub.core

import android.content.Context

object Prefs {
    /** "wifi" per adattatori Wi-Fi, altrimenti indirizzo MAC Bluetooth; null = nessuno scelto */
    const val OBD_WIFI = "wifi"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("drivehub", Context.MODE_PRIVATE)

    fun obdDevice(ctx: Context): String? = sp(ctx).getString("obd_device", null)
    fun setObdDevice(ctx: Context, v: String) = sp(ctx).edit().putString("obd_device", v).apply()

    fun fuel(ctx: Context): String = sp(ctx).getString("fuel", "Benzina")!!
    fun setFuel(ctx: Context, v: String) = sp(ctx).edit().putString("fuel", v).apply()
}
