package dev.kevtr.hilightlink

import android.content.Context
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A light pattern for an app's notifications. A rule with a [channel] or a [title] covers only
 * that part of them and goes before the app's own rule; switched off, it keeps that part dark.
 */
data class Rule(
    val pkg: String,
    val enabled: Boolean = true,
    val effect: Effect = Effect(),
    /** Only this notification channel of the app; null is any. */
    val channel: String? = null,
    /** The channel's name as the app shows it, for display. */
    val channelName: String? = null,
    /** Only notifications whose title contains this, such as a contact or a group; null is any. */
    val title: String? = null,
    /** The app's own rule goes by its package name; the narrower ones each get an id of their own. */
    val id: String = pkg,
) {
    /** Covers part of the app's notifications rather than all of them. */
    val scoped: Boolean get() = id != pkg

    /** Narrower rules go first: a name beats a channel, and both together beat either. */
    val reach: Int get() = (if (title.isNullOrBlank()) 0 else 2) + (if (channel == null) 0 else 1)

    fun fits(channel: String?, title: () -> String): Boolean {
        // A narrow rule that has not been told what to look for yet matches nothing.
        if (scoped && reach == 0) return false
        if (this.channel != null && this.channel != channel) return false
        return this.title.isNullOrBlank() || title().contains(this.title.trim(), ignoreCase = true)
    }
}

data class CallConfig(
    val on: Boolean = false,
    /** Loops while the phone rings, so its length is not used. */
    val effect: Effect = Effect(Style.CHASE, listOf(0xFF00E676.toInt()), periodMs = 900),
)

data class QuietConfig(
    val on: Boolean = false,
    /** Minutes after midnight. An end before the start runs through the night. */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    /** Play at low brightness during quiet hours instead of staying dark. */
    val dim: Boolean = false,
    /** A ringing call lights up as usual during quiet hours. */
    val calls: Boolean = true,
)

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
    val call: CallConfig = CallConfig(),
    val quiet: QuietConfig = QuietConfig(),
    /** Light nothing for a notification that Do Not Disturb is silencing. */
    val respectDnd: Boolean = true,
)

/** Process-wide rules, persisted as one JSON file. */
object RuleStore {
    /** Package name of the catch-all rule for apps without their own. */
    const val DEFAULT_PKG = "*"

    private val state = MutableStateFlow(Config())
    private var file: File? = null

    val config: StateFlow<Config> get() = state

    /** Told the settings, as JSON text, after every change. */
    @Volatile
    private var onChange: ((String) -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        if (!open(File(app.filesDir, "rules.json"))) return
        // The engine inside the system, if there is one, works from a copy that it is sent.
        onChange = { Leds.config(app, it) }
        Leds.config(app, serialize(state.value).toString())
    }

    /** For the engine inside the system: its copy of the settings lives in [dir]. */
    @Synchronized
    fun init(dir: File) {
        dir.mkdirs()
        open(File(dir, "rules.json"))
    }

    /** False if the settings were loaded before. */
    private fun open(f: File): Boolean {
        if (file != null) return false
        file = f
        if (f.exists()) runCatching { state.value = parse(JSONObject(f.readText())) }
        return true
    }

    /**
     * The rule for a notification from [pkg] on [channel]. [title] is only asked for when one
     * of the app's rules looks at titles.
     */
    fun ruleFor(pkg: String, channel: String? = null, title: () -> String = { "" }): Rule? {
        val rules = state.value.rules
        val text by lazy(title)
        // The narrowest rule that fits decides, even when it is off: that is how one chat is kept dark.
        val own = rules.filter { it.pkg == pkg && it.fits(channel) { text } }.maxByOrNull { it.reach }
        if (own != null) return own.takeIf { it.enabled }
        return rules.firstOrNull { it.pkg == DEFAULT_PKG && it.enabled }
    }

    fun put(rule: Rule) = update { c ->
        if (c.rules.any { it.id == rule.id }) {
            c.copy(rules = c.rules.map { if (it.id == rule.id) rule else it })
        } else {
            c.copy(rules = c.rules + rule)
        }
    }

    /** A new rule for part of [app]'s notifications, starting out with the app's own pattern. */
    fun addScoped(app: Rule): Rule {
        val rule = Rule(app.pkg, effect = app.effect, id = app.pkg + "/" + UUID.randomUUID())
        put(rule)
        return rule
    }

    /** Removes a rule; an app's own rule takes its narrower ones with it. */
    fun remove(rule: Rule) = update { c ->
        c.copy(rules = c.rules.filterNot { if (rule.scoped) it.id == rule.id else it.pkg == rule.pkg })
    }

    fun setOnlyScreenOff(on: Boolean) = update { it.copy(onlyScreenOff = on) }

    fun setCharging(charging: ChargingConfig) = update { it.copy(charging = charging) }

    fun setReminderMinutes(minutes: Int) = update { it.copy(reminderMinutes = minutes) }

    fun setPaused(paused: Boolean) = update { it.copy(paused = paused) }

    fun setMusic(music: MusicConfig) = update { it.copy(music = music) }

    fun setTimer(timer: TimerConfig) = update { it.copy(timer = timer) }

    fun setCall(call: CallConfig) = update { it.copy(call = call) }

    fun setQuiet(quiet: QuietConfig) = update { it.copy(quiet = quiet) }

    fun setRespectDnd(on: Boolean) = update { it.copy(respectDnd = on) }

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
        val text = serialize(next).toString()
        tmp.writeText(text)
        if (!tmp.renameTo(f)) f.writeText(text)
        onChange?.invoke(text)
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
        .put("call", JSONObject()
            .put("on", c.call.on)
            .put("effect", c.call.effect.toJson()))
        .put("quiet", JSONObject()
            .put("on", c.quiet.on)
            .put("startMinute", c.quiet.startMinute)
            .put("endMinute", c.quiet.endMinute)
            .put("dim", c.quiet.dim)
            .put("calls", c.quiet.calls))
        .put("respectDnd", c.respectDnd)
        // putOpt leaves out what is null: all four, for an app's own rule.
        .put("rules", JSONArray(c.rules.map { r ->
            JSONObject().put("pkg", r.pkg).put("enabled", r.enabled).put("effect", r.effect.toJson())
                .putOpt("channel", r.channel)
                .putOpt("channelName", r.channelName)
                .putOpt("title", r.title)
                .putOpt("id", r.id.takeIf { r.scoped })
        }))

    private fun parse(o: JSONObject): Config {
        val arr = o.optJSONArray("rules") ?: JSONArray()
        val ch = o.optJSONObject("charging") ?: JSONObject()
        val mu = o.optJSONObject("music") ?: JSONObject()
        val ti = o.optJSONObject("timer") ?: JSONObject()
        val ca = o.optJSONObject("call") ?: JSONObject()
        val qu = o.optJSONObject("quiet") ?: JSONObject()
        val dc = CallConfig()
        val dq = QuietConfig()
        val d = ChargingConfig()
        val dm = MusicConfig()
        val dt = TimerConfig()
        return Config(
            rules = (0 until arr.length()).map {
                val r = arr.getJSONObject(it)
                val pkg = r.getString("pkg")
                Rule(
                    pkg = pkg,
                    enabled = r.optBoolean("enabled", true),
                    effect = Effect.fromJson(r.getJSONObject("effect")),
                    channel = r.optString("channel").ifEmpty { null },
                    channelName = r.optString("channelName").ifEmpty { null },
                    title = r.optString("title").ifEmpty { null },
                    id = r.optString("id").ifEmpty { pkg },
                )
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
            call = CallConfig(
                on = ca.optBoolean("on", dc.on),
                effect = ca.optJSONObject("effect")?.let(Effect::fromJson) ?: dc.effect,
            ),
            quiet = QuietConfig(
                on = qu.optBoolean("on", dq.on),
                startMinute = qu.optInt("startMinute", dq.startMinute).coerceIn(0, 1439),
                endMinute = qu.optInt("endMinute", dq.endMinute).coerceIn(0, 1439),
                dim = qu.optBoolean("dim", dq.dim),
                calls = qu.optBoolean("calls", dq.calls),
            ),
            respectDnd = o.optBoolean("respectDnd", true),
        )
    }
}
