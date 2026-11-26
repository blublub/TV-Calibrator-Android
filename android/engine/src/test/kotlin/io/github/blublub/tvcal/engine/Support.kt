// Port of CalibrationKit/Tests/CalibrationKitTests/Support.swift (TV Calibrator by Slidebean, CC BY-NC 4.0).
package io.github.blublub.tvcal.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlin.math.abs
import kotlin.test.fail

/** Fixtures are written by tools/make_fixtures.py from the Mac app's Python engine. */
object Fixture {
    private val dir: File by lazy {
        File(System.getProperty("fixtures") ?: error("system property 'fixtures' not set"))
    }

    fun load(name: String): JsonElement {
        val f = File(dir, "$name.json")
        if (!f.exists()) error("Missing fixture $name.json in $dir")
        return Json.parseToJsonElement(f.readText())
    }
}

fun num(e: JsonElement?): Double? = when (e) {
    null, JsonNull -> null
    is JsonPrimitive -> if (e.isString) null else e.doubleOrNull
    else -> null
}

fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun vec3(e: JsonElement?): Vec3 {
    val a = e as JsonArray
    return Vec3(num(a[0])!!, num(a[1])!!, num(a[2])!!)
}

fun point(e: JsonElement?): Point2 {
    val a = e as JsonArray
    return Point2(num(a[0])!!, num(a[1])!!)
}

fun dict(e: JsonElement?): JsonObject = e as? JsonObject ?: JsonObject(emptyMap())
fun list(e: JsonElement?): List<JsonElement> = e as? JsonArray ?: emptyList()

/** Equal within a relative tolerance (and an absolute one near zero). null == null; NaN matches null. */
fun assertClose(a0: Double?, b0: Double?, tol: Double = 1e-6, message: String = "") {
    val a = if (a0?.isNaN() == true) null else a0
    val b = if (b0?.isNaN() == true) null else b0
    if (a == null && b == null) return
    if (a == null || b == null) fail("$message: $a vs $b")
    if (abs(a - b) > tol * maxOf(1.0, abs(a), abs(b))) fail("$message: $a vs $b")
}

fun assertClose(a: Vec3, b: Vec3, tol: Double = 1e-6, message: String = "") {
    for (i in 0 until 3) assertClose(a[i], b[i], tol, "$message[$i]")
}

fun assertClose(a: Point2, b: Point2, tol: Double = 1e-6, message: String = "") {
    assertClose(a.x, b.x, tol, "$message.x")
    assertClose(a.y, b.y, tol, "$message.y")
}
