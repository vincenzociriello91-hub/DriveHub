package it.vincenzo.drivehub.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "DriveHub-Android")
        }

    fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} su $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} su $url")
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) error("Impossibile salvare ${dest.name}")
        } finally { c.disconnect() }
    }
}
