package dev.kevtr.hilightlink

import android.content.Context
import dev.kevtr.hilightlink.engine.Effect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Rule(val pkg: String, val enabled: Boolean = true, val effect: Effect = Effect())

data class ChargingConfig(
    val chargingOn: Boolean = false,
    val chargingColor: Int = 0xFFFF9100.toInt(),
    val chargedOn: Boolean = false,
    val chargedColor: Int = 0xFF00E676.toInt(),
    val brightness: Float = 0.3f,
    /** While charging, light as many LEDs as the battery is full instead of all of them. */
    val meter: Boolean = false,
    /** Blink red when the battery drops to 15% and to 5%. */
    val lowBattery: Boolean = false,
)

/** [usesColor] is false for the styles that bring their own colours. */
enum class MusicStyle(val label: String, val about: String, val usesColor: Boolean) {
    SPECTRUM("Spectrum", "Each LED follows one pitch range, bass to treble, in rainbow colours.", false),
    PULSE("Pulse", "All LEDs beat together with the bass, in one colour.", true),
    METER("Level meter", "The LEDs fill up with the loudness, green to red, and a dot marks the last peak.", false),
    BURST("Center burst", "The bass lights the middle and spreads outwards the harder it hits.", true),
    CHASE("Beat chase", "A light that jumps one LED further on every beat.", true),
    BEAT_COLOR("Beat colors", "All LEDs glow together and change colour on every beat.", false),
    SHUFFLE(
        "Shuffle",
        "Moves through all the styles in random order, changing on the beat every few bars and when the music " +
            "drops or comes back in.",
        true,
    ),
}

data class MusicConfig(
    val on: Boolean = false,
    val style: MusicStyle = MusicStyle.SPECTRUM,
    val color: Int = 0xFF00B0FF.toInt(),
    val brightness: Float = 0.6f,
    /** 0..1: how readily quiet passages light the LEDs. */
    val sensitivity: Float = 0.5f,
    /** Spread the rainbow across the LEDs instead of the style's usual colours. */
    val rainbow: Boolean = false,
)

data class TimerConfig(
    val color: Int = 0xFF00B0FF.toInt(),
    val minutes: Int = 5,
    /** Show countdowns that other apps, such as Clock, post as notifications. */
    val followClock: Boolean = true,
)

data class Config(
    val rules: List<Rule> = emptyList(),
    /** Only light up while the screen is off. */
    val onlyScreenOff: Boolean = false,
    val charging: ChargingConfig = ChargingConfig(),
    /** Replay a missed notification's pattern this often; 0 is off. */
    val reminderMinutes: Int = 0,
    /** Everything dark until switched back on. */
    val paused: Boolean = false,
    val music: MusicConfig = MusicConfig(),
    val timer: TimerConfig = TimerConfig(),
)

/** Process-wide rules, persisted as one JSON file. */
object RuleStore {
    /** Package name of the catch-all rule for apps without their own. */
    const val DEFAULT_PKG = "*"

    private val state = MutableStateFlow(Config())
    private var file: File? = null

    val config: StateFlow<Config> get() = state

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "rules.json")
        file = f
        if (f.exists()) runCatching { state.value = parse(JSONObject(f.readText())) }
    }

    fun ruleFor(pkg: String): Rule? {
        val rules = state.value.rules
        val own = rules.firstOrNull { it.pkg == pkg }
        if (own != null) return own.takeIf { it.enabled }
        return rules.firstOrNull { it.pkg == DEFAULT_PKG && it.enabled }
    }

    fun put(rule: Rule) = update { c ->
        if (c.rules.any { it.pkg == rule.pkg }) {
            c.copy(rules = c.rules.map { if (it.pkg == rule.pkg) rule else it })
        } else {
            c.copy(rules = c.rules + rule)
        }
    }

    fun remove(pkg: String) = update { c -> c.copy(rules = c.rules.filterNot { it.pkg == pkg }) }

    fun setOnlyScreenOff(on: Boolean) = update { it.copy(onlyScreenOff = on) }

    fun setCharging(charging: ChargingConfig) = update { it.copy(charging = charging) }

    fun setReminderMinutes(minutes: Int) = update { it.copy(reminderMinutes = minutes) }

    fun setPaused(paused: Boolean) = update { it.copy(paused = paused) }

    fun setMusic(music: MusicConfig) = update { it.copy(music = music) }

    fun setTimer(timer: TimerConfig) = update { it.copy(timer = timer) }

    /** Everything the app has saved, as text for a backup file. */
    fun exportJson(): String = serialize(state.value).toString(2)

    /** Replaces all settings with a backup made by [exportJson]. False if the text is not one. */
    fun importJson(text: String): Boolean {
        val restored = runCatching {
            val o = JSONObject(text)
            require(o.has("rules"))
            parse(o)
        }.getOrNull() ?: return false
        update { restored }
        return true
    }

    @Synchronized
    private fun update(change: (Config) -> Config) {
        val next = change(state.value)
        state.value = next
        val f = file ?: return
        // Written beside the real file and swapped in, so a crash half way cannot wipe the rules.
        val tmp = File(f.path + ".tmp")
        tmp.writeText(serialize(next).toString())
        if (!tmp.renameTo(f)) f.writeText(tmp.readText())
    }

    private fun serialize(c: Config) = JSONObject()
        .put("onlyScreenOff", c.onlyScreenOff)
        .put("reminderMinutes", c.reminderMinutes)
        .put("paused", c.paused)
        .put("charging", JSONObject()
            .put("chargingOn", c.charging.chargingOn)
            .put("chargingColor", c.charging.chargingColor)
            .put("chargedOn", c.charging.chargedOn)
            .put("chargedColor", c.charging.chargedColor)
            .put("brightness", c.charging.brightness.toDouble())
            .put("meter", c.charging.meter)
            .put("lowBattery", c.charging.lowBattery))
        .put("music", JSONObject()
            .put("on", c.music.on)
            .put("style", c.music.style.name)
            .put("color", c.music.color)
            .put("brightness", c.music.brightness.toDouble())
            .put("sensitivity", c.music.sensitivity.toDouble())
            .put("rainbow", c.music.rainbow))
        .put("timer", JSONObject()
            .put("color", c.timer.color)
            .put("minutes", c.timer.minutes)
            .put("followClock", c.timer.followClock))
        .put("rules", JSONArray(c.rules.map {
            JSONObject().put("pkg", it.pkg).put("enabled", it.enabled).put("effect", it.effect.toJson())
        }))

    private fun parse(o: JSONObject): Config {
        val arr = o.optJSONArray("rules") ?: JSONArray()
        val ch = o.optJSONObject("charging") ?: JSONObject()
        val mu = o.optJSONObject("music") ?: JSONObject()
        val ti = o.optJSONObject("timer") ?: JSONObject()
        val d = ChargingConfig()
        val dm = MusicConfig()
        val dt = TimerConfig()
        return Config(
            rules = (0 until arr.length()).map {
                val r = arr.getJSONObject(it)
                Rule(r.getString("pkg"), r.optBoolean("enabled", true), Effect.fromJson(r.getJSONObject("effect")))
            },
            onlyScreenOff = o.optBoolean("onlyScreenOff", false),
            reminderMinutes = o.optInt("reminderMinutes", 0),
            paused = o.optBoolean("paused", false),
            charging = ChargingConfig(
                chargingOn = ch.optBoolean("chargingOn", d.chargingOn),
                chargingColor = ch.optInt("chargingColor", d.chargingColor),
                chargedOn = ch.optBoolean("chargedOn", d.chargedOn),
                chargedColor = ch.optInt("chargedColor", d.chargedColor),
                brightness = ch.optDouble("brightness", d.brightness.toDouble()).toFloat(),
                meter = ch.optBoolean("meter", d.meter),
                lowBattery = ch.optBoolean("lowBattery", d.lowBattery),
            ),
            music = MusicConfig(
                on = mu.optBoolean("on", dm.on),
                style = runCatching { MusicStyle.valueOf(mu.optString("style")) }.getOrDefault(dm.style),
                color = mu.optInt("color", dm.color),
                brightness = mu.optDouble("brightness", dm.brightness.toDouble()).toFloat(),
                sensitivity = mu.optDouble("sensitivity", dm.sensitivity.toDouble()).toFloat(),
                rainbow = mu.optBoolean("rainbow", dm.rainbow),
            ),
            timer = TimerConfig(
                color = ti.optInt("color", dt.color),
                minutes = ti.optInt("minutes", dt.minutes),
                followClock = ti.optBoolean("followClock", dt.followClock),
            ),
        )
    }
}
