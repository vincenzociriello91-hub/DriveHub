package it.vincenzo.drivehub.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.vincenzo.drivehub.R
import it.vincenzo.drivehub.core.Locator
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.fuel.FuelRepo
import it.vincenzo.drivehub.fuel.Offer
import it.vincenzo.drivehub.obd.ObdManager
import it.vincenzo.drivehub.weather.Forecast
import it.vincenzo.drivehub.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DriveHubCarService : CarAppService() {
    // App personale installata da fonti sconosciute: si accetta qualsiasi host
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = DriveHubSession()
}

class DriveHubSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Reports.log("Android Auto: sessione aperta")
                ObdManager.start(carContext)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                ObdManager.stop()
                Reports.log("Android Auto: sessione chiusa")
                Reports.flush("auto")
            }
        })
    }
    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
}

private fun fmt(v: Double?, digits: Int = 0) = v?.let { String.format(Locale.ITALY, "%.${digits}f", it) } ?: "—"
private fun icon(ctx: CarContext, res: Int) = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()
private fun message(text: String) = MessageTemplate.Builder(text).setTitle("DriveHub").setHeaderAction(Action.BACK).build()

class HomeScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        fun item(title: String, res: Int, open: () -> Screen) = GridItem.Builder()
            .setTitle(title).setImage(icon(carContext, res))
            .setOnClickListener { Reports.log("Auto: apro $title"); screenManager.push(open()) }.build()
        val list = ItemList.Builder()
            .addItem(item("Cruscotto", R.drawable.ic_gauge) { ObdScreen(carContext) })
            .addItem(item("Carburante", R.drawable.ic_fuel) { FuelScreen(carContext) })
            .addItem(item("Meteo", R.drawable.ic_weather) { WeatherScreen(carContext) })
            .addItem(item("Errori motore", R.drawable.ic_warning) { DtcScreen(carContext) })
            .build()
        return GridTemplate.Builder().setTitle("DriveHub").setHeaderAction(Action.APP_ICON).setSingleList(list).build()
    }
}

/** Schermata che si ridisegna a ogni nuovo dato OBD */
abstract class ObdBoundScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ObdManager.state.collect { invalidate() } }
        }
    }
}

class ObdScreen(ctx: CarContext) : ObdBoundScreen(ctx) {
    override fun onGetTemplate(): Template {
        val s = ObdManager.state.value
        val v = s.values
        val pane = Pane.Builder()
        if (!s.connected && v.isEmpty()) {
            pane.addRow(Row.Builder().setTitle(s.status).build())
        } else {
            pane.addRow(Row.Builder().setTitle("${fmt(v["0D"])} km/h · ${fmt(v["0C"])} giri/min").build())
            pane.addRow(Row.Builder().setTitle("Consumo ${fmt(v["L100"], 1)} l/100 km · ${fmt(v["LH"], 1)} l/h").build())
            pane.addRow(Row.Builder().setTitle("Liquido ${fmt(v["05"])}°C · Aria ${fmt(v["0F"])}°C").build())
            pane.addRow(Row.Builder().setTitle("Batteria ${fmt(v["V"], 1)} V · Carico ${fmt(v["04"])}% · Serbatoio ${fmt(v["2F"])}%")
                .addText(if (s.connected) "Collegato" else s.status).build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("Cruscotto").setHeaderAction(Action.BACK).build()
    }
}

class DtcScreen(ctx: CarContext) : ObdBoundScreen(ctx) {
    init { ObdManager.requestDtc() }

    override fun onGetTemplate(): Template {
        val s = ObdManager.state.value
        if (!s.connected) return message("Adattatore OBD non collegato.\n${s.status}")
        val codes = s.dtcs ?: return ListTemplate.Builder().setTitle("Errori motore").setHeaderAction(Action.BACK).setLoading(true).build()
        if (codes.isEmpty()) return message("Nessun codice di errore memorizzato.")
        val list = ItemList.Builder()
        codes.take(6).forEach { list.addItem(Row.Builder().setTitle(it).addText("Dettagli nell'app sul telefono, a veicolo fermo").build()) }
        return ListTemplate.Builder().setTitle("Errori motore (${codes.size})").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

class FuelScreen(ctx: CarContext) : Screen(ctx) {
    private var offers: List<Offer>? = null
    private var failure: String? = null
    private val fuel = Prefs.fuel(ctx)

    init {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loc = Locator.current(carContext) ?: error("Posizione non disponibile: concedi il permesso nell'app sul telefono")
                    FuelRepo.search(carContext, fuel, loc.latitude, loc.longitude)
                }
            }
            result.onSuccess { offers = it }.onFailure {
                failure = it.message ?: "Errore"
                Reports.log("Carburante: errore ${it.javaClass.simpleName}: ${it.message}")
            }
            Reports.flush("carburante")
            invalidate()
        }
    }

    private fun limit() = try {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } catch (_: Exception) { 6 }

    override fun onGetTemplate(): Template {
        failure?.let { return message(it) }
        val list = offers ?: return ListTemplate.Builder().setTitle("$fuel vicino a te").setHeaderAction(Action.BACK).setLoading(true).build()
        if (list.isEmpty()) return message("Nessun distributore con prezzo $fuel entro 10 km.")
        val items = ItemList.Builder()
        list.take(limit()).forEach { o ->
            val name = listOf(o.station.brand, o.station.name).filter { it.isNotBlank() }.joinToString(" ")
            items.addItem(
                Row.Builder()
                    .setTitle("€ ${String.format(Locale.ITALY, "%.3f", o.price)} · $name")
                    .addText("${fmt(o.km, 1)} km · ${o.station.address}, ${o.station.town}${if (o.self) " · self" else " · servito"}")
                    .setOnClickListener {
                        Reports.log("Carburante: navigo verso $name a ${o.price}")
                        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${o.station.lat},${o.station.lon}")))
                    }.build()
            )
        }
        return ListTemplate.Builder().setTitle("$fuel: i più economici").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}

class WeatherScreen(ctx: CarContext) : Screen(ctx) {
    private var forecast: Forecast? = null
    private var failure: String? = null

    init {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loc = Locator.current(carContext) ?: error("Posizione non disponibile: concedi il permesso nell'app sul telefono")
                    Weather.load(loc.latitude, loc.longitude)
                }
            }
            result.onSuccess { forecast = it }.onFailure {
                failure = it.message ?: "Errore"
                Reports.log("Meteo: errore ${it.javaClass.simpleName}: ${it.message}")
            }
            Reports.flush("meteo")
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        failure?.let { return message(it) }
        val f = forecast ?: return PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).setTitle("Meteo").setHeaderAction(Action.BACK).build()
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Ora ${fmt(f.temp)}°C · ${Weather.describe(f.code)}").addText("Vento ${fmt(f.wind)} km/h").build())
        f.next.chunked(2).take(3).forEach { pair ->
            pane.addRow(Row.Builder().setTitle(pair.joinToString(" · ") { "${it.time} ${fmt(it.temp)}° ${Weather.describe(it.code)} (${it.rainProb}% pioggia)" }).build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("Meteo").setHeaderAction(Action.BACK).build()
    }
}
