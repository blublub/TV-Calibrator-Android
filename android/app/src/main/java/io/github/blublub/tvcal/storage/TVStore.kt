// Port of TV Calibrator/Storage/TVStore.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin.
package io.github.blublub.tvcal.storage

import android.content.Context
import io.github.blublub.tvcal.engine.CameraProfile
import io.github.blublub.tvcal.engine.Measurements
import io.github.blublub.tvcal.engine.MeasurementsSerializer
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.engine.SimTV
import io.github.blublub.tvcal.engine.Vec3
import io.github.blublub.tvcal.engine.engineJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// MARK: JSON helpers (match Swift's JSONEncoder/Decoder with .iso8601 dates and UUID strings)

/** A date as Swift's `.iso8601` strategy writes it: "2025-01-31T12:00:00Z" (whole seconds, UTC). */
object Iso8601InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.blublub.tvcal.storage.Iso8601Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    override fun deserialize(decoder: Decoder): Instant =
        OffsetDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
}

/** A UUID as Swift's Codable writes it (`uuidString`, upper case). Reading ignores case. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.blublub.tvcal.storage.Uuid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.uuidString)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Swift's `UUID.uuidString`: upper case. */
val UUID.uuidString: String get() = toString().uppercase()

/** Reads a key leniently: missing, null or unreadable gives null (Swift's `try? decodeIfPresent`). */
private fun <T> JsonObject.opt(key: String, s: KSerializer<T>): T? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return runCatching { engineJson.decodeFromJsonElement(s, e) }.getOrNull()
}

/** Reads a key that must be there and valid when present (Swift's `try decode` / `try decodeIfPresent`). */
private fun <T> JsonObject.req(key: String, s: KSerializer<T>): T =
    engineJson.decodeFromJsonElement(s, this[key] ?: throw IllegalArgumentException("Missing key $key"))

private fun <T> JsonObject.strictOpt(key: String, s: KSerializer<T>): T? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return engineJson.decodeFromJsonElement(s, e)
}

/** Builds an object, leaving out null values (Swift's synthesized `encodeIfPresent`). */
private class ObjBuilder {
    val map = LinkedHashMap<String, JsonElement>()
    fun <T> put(key: String, s: KSerializer<T>, v: T?) {
        if (v != null) map[key] = engineJson.encodeToJsonElement(s, v)
    }
    fun build() = JsonObject(map)
}

private inline fun obj(f: ObjBuilder.() -> Unit): JsonObject = ObjBuilder().apply(f).build()

/** Base class for the lenient serializers below: encode to / decode from a JSON object. */
abstract class ObjectSerializer<T>(name: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)
    abstract fun toJson(v: T): JsonObject
    abstract fun fromJson(o: JsonObject): T
    override fun serialize(encoder: Encoder, value: T) = (encoder as JsonEncoder).encodeJsonElement(toJson(value))
    override fun deserialize(decoder: Decoder): T = fromJson((decoder as JsonDecoder).decodeJsonElement().jsonObject)
}

private val stringList = ListSerializer(String.serializer())

// MARK: TV

/** One TV the user calibrates. */
@Serializable(with = TVRecordSerializer::class)
data class TVRecord(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val brand: Brand = Brand.OTHER,
    val multipoint: Multipoint = Multipoint.TWENTY,
    val colourMenu: ColourMenu = ColourMenu.HSL,
    /**
     * False until the user has told us what the TV's menus look like ("Not sure" when adding the TV).
     * The app then asks when it reaches the step that needs it.
     */
    val multipointKnown: Boolean = true,
    val colourMenuKnown: Boolean = true,
    /** The kind of screen, which picks the camera's conversion. */
    val panel: PanelType = PanelType.UNKNOWN,
    /** A simulated TV for trying the app (and for App Review) without a real one. */
    val isDemo: Boolean = false,
    val created: Instant = Instant.now(),
)

/** Reads TVs saved by any version of the app (missing fields take their defaults). */
object TVRecordSerializer : ObjectSerializer<TVRecord>("io.github.blublub.tvcal.storage.TVRecord") {
    override fun toJson(v: TVRecord) = obj {
        put("id", UuidSerializer, v.id)
        put("name", String.serializer(), v.name)
        put("brand", Brand.serializer(), v.brand)
        put("multipoint", MultipointSerializer, v.multipoint)
        put("colourMenu", ColourMenu.serializer(), v.colourMenu)
        put("multipointKnown", Boolean.serializer(), v.multipointKnown)
        put("colourMenuKnown", Boolean.serializer(), v.colourMenuKnown)
        put("panel", PanelType.serializer(), v.panel)
        put("isDemo", Boolean.serializer(), v.isDemo)
        put("created", Iso8601InstantSerializer, v.created)
    }

    override fun fromJson(o: JsonObject) = TVRecord(
        id = o.req("id", UuidSerializer),
        name = o.req("name", String.serializer()),
        brand = o.opt("brand", Brand.serializer()) ?: Brand.OTHER,
        multipoint = o.opt("multipoint", MultipointSerializer) ?: Multipoint.TWENTY,
        colourMenu = o.opt("colourMenu", ColourMenu.serializer()) ?: ColourMenu.HSL,
        multipointKnown = o.strictOpt("multipointKnown", Boolean.serializer()) ?: true,
        colourMenuKnown = o.strictOpt("colourMenuKnown", Boolean.serializer()) ?: true,
        panel = o.opt("panel", PanelType.serializer()) ?: PanelType.UNKNOWN,
        isDemo = o.strictOpt("isDemo", Boolean.serializer()) ?: false,
        created = o.strictOpt("created", Iso8601InstantSerializer) ?: Instant.now(),
    )
}

// MARK: guided

/** The guided steps, in order. "fine" (multipoint fine-tune) is reached from the Check step. */
@Serializable
enum class GuideStep(val raw: String) {
    @SerialName("setup") SETUP("setup"),
    @SerialName("before") BEFORE("before"),
    @SerialName("bright") BRIGHT("bright"),
    @SerialName("dark") DARK("dark"),
    @SerialName("check") CHECK("check"),
    @SerialName("colours") COLOURS("colours"),
    @SerialName("after") AFTER("after"),
    @SerialName("fine") FINE("fine");

    val id: String get() = raw

    val label: String
        get() = when (this) {
            SETUP -> "Set up"
            BEFORE -> "Before"
            BRIGHT -> "Bright end"
            DARK -> "Dark end"
            CHECK -> "Check"
            COLOURS -> "Colours"
            AFTER -> "After"
            FINE -> "Fine-tune"
        }

    val next: GuideStep?
        get() {
            val i = stepper.indexOf(this)
            if (i < 0 || i + 1 >= stepper.size) return null
            return stepper[i + 1]
        }

    companion object {
        val stepper: List<GuideStep> = listOf(SETUP, BEFORE, BRIGHT, DARK, CHECK, COLOURS, AFTER)
        fun fromRaw(s: String): GuideStep? = entries.firstOrNull { it.raw == s }
    }
}

// Saved files must keep loading after an app update adds a field, so each of these reads what's there
// and takes the default for anything missing (the generated decoders would reject the whole file).

@Serializable(with = GuideProgressSerializer::class)
data class GuideProgress(
    val step: GuideStep = GuideStep.SETUP,
    val done: List<GuideStep> = emptyList(),
    /** Colours finished (or marked "as close as it gets"). */
    val colours: List<String> = emptyList(),
    /** Steps and colours that hit the TV's limit. */
    val limits: List<String> = emptyList(),
    val colour: String? = null,
    val finePid: String? = null,
)

object GuideProgressSerializer : ObjectSerializer<GuideProgress>("io.github.blublub.tvcal.storage.GuideProgress") {
    private val steps = ListSerializer(GuideStep.serializer())
    override fun toJson(v: GuideProgress) = obj {
        put("step", GuideStep.serializer(), v.step)
        put("done", steps, v.done)
        put("colours", stringList, v.colours)
        put("limits", stringList, v.limits)
        put("colour", String.serializer(), v.colour)
        put("finePid", String.serializer(), v.finePid)
    }

    override fun fromJson(o: JsonObject) = GuideProgress(
        step = o.opt("step", GuideStep.serializer()) ?: GuideStep.SETUP,
        done = o.opt("done", steps) ?: emptyList(),
        colours = o.opt("colours", stringList) ?: emptyList(),
        limits = o.opt("limits", stringList) ?: emptyList(),
        colour = o.opt("colour", String.serializer()),
        finePid = o.opt("finePid", String.serializer()),
    )
}

@Serializable(with = GuidedSessionSerializer::class)
data class GuidedSession(
    val started: Instant = Instant.now(),
    val before: Measurements? = null,
    val after: Measurements? = null,
    val finished: Instant? = null,
)

object GuidedSessionSerializer : ObjectSerializer<GuidedSession>("io.github.blublub.tvcal.storage.GuidedSession") {
    override fun toJson(v: GuidedSession) = obj {
        put("started", Iso8601InstantSerializer, v.started)
        put("before", MeasurementsSerializer, v.before)
        put("after", MeasurementsSerializer, v.after)
        put("finished", Iso8601InstantSerializer, v.finished)
    }

    override fun fromJson(o: JsonObject) = GuidedSession(
        started = o.opt("started", Iso8601InstantSerializer) ?: Instant.now(),
        before = o.opt("before", MeasurementsSerializer),
        after = o.opt("after", MeasurementsSerializer),
        finished = o.opt("finished", Iso8601InstantSerializer),
    )
}

@Serializable(with = FinderDataSerializer::class)
data class FinderData(
    val base: Measurements? = null,
    val nudged: Measurements? = null,
    /** Grey id -> TV control the user found by hand. */
    val overrides: Map<String, Int> = emptyMap(),
)

object FinderDataSerializer : ObjectSerializer<FinderData>("io.github.blublub.tvcal.storage.FinderData") {
    private val ints = MapSerializer(String.serializer(), Int.serializer())
    override fun toJson(v: FinderData) = obj {
        put("base", MeasurementsSerializer, v.base)
        put("nudged", MeasurementsSerializer, v.nudged)
        put("overrides", ints, v.overrides)
    }

    override fun fromJson(o: JsonObject) = FinderData(
        base = o.opt("base", MeasurementsSerializer),
        nudged = o.opt("nudged", MeasurementsSerializer),
        overrides = o.opt("overrides", ints) ?: emptyMap(),
    )
}

/** One TV's guided calibration for one signal mode. */
@Serializable(with = GuideStateSerializer::class)
data class GuideState(
    val sessions: List<GuidedSession> = emptyList(),
    /** The latest Check step measurement, so its result is still there after leaving the step. */
    val check: Measurements? = null,
    val finder: FinderData = FinderData(),
    val progress: GuideProgress = GuideProgress(),
) {
    /** This TV's own white and primaries (Swift: a labelled tuple). */
    data class Reference(val whiteY: Double?, val basis: List<Vec3>?)

    /** This TV's own white and primaries, from the latest Before/After check (for colour hints). */
    val reference: Reference
        get() {
            val checks = sessions.asReversed().flatMap { listOfNotNull(it.after, it.before) }
            val white = checks.asSequence().mapNotNull { it["W0"]?.y }.firstOrNull()
            val basis = checks.asSequence()
                .map { m -> listOf("RED", "GREEN", "BLUE").mapNotNull { m[it] } }
                .firstOrNull { it.size == 3 }
            return Reference(white, basis)
        }
}

object GuideStateSerializer : ObjectSerializer<GuideState>("io.github.blublub.tvcal.storage.GuideState") {
    private val sessions = ListSerializer(GuidedSessionSerializer)
    override fun toJson(v: GuideState) = obj {
        put("sessions", sessions, v.sessions)
        put("check", MeasurementsSerializer, v.check)
        put("finder", FinderDataSerializer, v.finder)
        put("progress", GuideProgressSerializer, v.progress)
    }

    override fun fromJson(o: JsonObject) = GuideState(
        sessions = o.opt("sessions", sessions) ?: emptyList(),
        check = o.opt("check", MeasurementsSerializer),
        finder = o.opt("finder", FinderDataSerializer) ?: FinderData(),
        progress = o.opt("progress", GuideProgressSerializer) ?: GuideProgress(),
    )
}

// MARK: store

/**
 * Everything is kept as JSON files in <base>/TVs/<id>/ (the iPhone app uses Application Support;
 * on Android, [Context.getFilesDir]). [tempDir] is where exports go (the cache directory on Android).
 */
class TVStore(base: File, private val tempDir: File) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    val root: File = File(base, "TVs").also { it.mkdirs() }

    /** Pretty printed, like the Swift encoder (which also sorts keys; key order doesn't matter to readers). */
    private val json = Json(from = engineJson) { prettyPrint = true }

    fun folder(tv: TVRecord): File = File(root, tv.id.uuidString).also { it.mkdirs() }

    private fun <T> read(s: KSerializer<T>, file: File): T? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(s, text) }.getOrNull()
    }

    private fun <T> write(s: KSerializer<T>, value: T, file: File) {
        val text = runCatching { json.encodeToString(s, value) }.getOrNull() ?: return
        writeAtomically(text, file)
    }

    /** Like Swift's `.atomic`: write a temporary file next to it, then rename it over. */
    private fun writeAtomically(text: String, file: File): Boolean = runCatching {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        }
        true
    }.getOrDefault(false)

    // MARK: TVs

    fun loadTVs(): List<TVRecord> {
        val dirs = root.listFiles()?.toList() ?: emptyList()
        // A folder with no tv.json is left over from a deleted screen: tidy it away.
        for (d in dirs) if (!File(d, "tv.json").exists()) d.deleteRecursively()
        return dirs.mapNotNull { read(TVRecordSerializer, File(it, "tv.json")) }.sortedBy { it.created }
    }

    fun save(tv: TVRecord) = write(TVRecordSerializer, tv, File(folder(tv), "tv.json"))

    fun delete(tv: TVRecord) {
        File(root, tv.id.uuidString).deleteRecursively()
    }

    // MARK: guided (per signal mode)

    fun guideState(tv: TVRecord, mode: SignalMode): GuideState =
        read(GuideStateSerializer, File(folder(tv), "guide-${mode.raw}.json")) ?: GuideState()

    fun save(st: GuideState, tv: TVRecord, mode: SignalMode) =
        write(GuideStateSerializer, st, File(folder(tv), "guide-${mode.raw}.json"))

    // MARK: demo TVs

    fun simState(tv: TVRecord, hdr: Boolean): SimTV.State? =
        read(SimTV.State.serializer(), File(folder(tv), if (hdr) "sim-hdr.json" else "sim-sdr.json"))

    fun saveSim(s: SimTV.State, tv: TVRecord, hdr: Boolean) =
        write(SimTV.State.serializer(), s, File(folder(tv), if (hdr) "sim-hdr.json" else "sim-sdr.json"))

    // MARK: readable log + export

    fun appendLog(tv: TVRecord, text: String) {
        runCatching { File(folder(tv), "log.md").appendText(text) }
    }

    @Serializable
    private data class Export(
        @Serializable(with = Iso8601InstantSerializer::class) val exported: Instant,
        val app: String,
        @Serializable(with = TVRecordSerializer::class) val tv: TVRecord,
        val phone: String,
        val profile: CameraProfile? = null,
        val guided: Map<String, @Serializable(with = GuideStateSerializer::class) GuideState>,
    )

    /**
     * One file with everything about this TV, to share for troubleshooting.
     *
     * TODO(port): the Swift version reads the app version from the bundle (CFBundleShortVersionString)
     * and the phone from `DeviceModel.identifier` (ProfileService.swift). Here the caller passes them,
     * e.g. `BuildConfig`/PackageInfo versionName and `Build.MODEL`, until DeviceModel is ported.
     */
    fun export(tv: TVRecord, profile: CameraProfile?, app: String = "?", phone: String = "?"): File? {
        val guided = LinkedHashMap<String, GuideState>()
        for (mode in SignalMode.entries) {
            val st = guideState(tv, mode)
            if (st.sessions.isNotEmpty() || st.finder.base != null) guided[mode.raw] = st
        }
        val e = Export(exported = Instant.now(), app = app, tv = tv, phone = phone, profile = profile, guided = guided)
        val safe = tv.name.replace("/", "-")
        tempDir.mkdirs()
        val file = File(tempDir, "$safe - TV Calibrator export.json")
        val text = runCatching { json.encodeToString(Export.serializer(), e) }.getOrNull() ?: return null
        writeAtomically(text, file)
        return file
    }
}
