// Tests for the port of TV Calibrator/Storage (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0).
package io.github.blublub.tvcal.storage

import io.github.blublub.tvcal.engine.Measurements
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.engine.Vec3
import io.github.blublub.tvcal.engine.engineJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class StorageTests {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = TVStore(tmp.newFolder("files"), tmp.newFolder("cache"))

    @Test fun tvRecordRoundTripsThroughTheStore() {
        val s = store()
        val tv = TVRecord(
            name = "Living room", brand = Brand.SONY, multipoint = Multipoint.TEN,
            colourMenu = ColourMenu.RGB_SLIDERS, multipointKnown = false, panel = PanelType.QDOLED,
            isDemo = true, created = Instant.parse("2025-03-04T05:06:07Z"),
        )
        s.save(tv)
        assertTrue(File(s.root, "${tv.id.toString().uppercase()}/tv.json").exists())
        val loaded = TVStore(File(tmp.root, "files"), File(tmp.root, "cache")).loadTVs()
        assertEquals(listOf(tv), loaded)
    }

    @Test fun loadSortsByCreatedAndTidiesEmptyFolders() {
        val s = store()
        val b = TVRecord(name = "B", created = Instant.parse("2025-02-01T00:00:00Z"))
        val a = TVRecord(name = "A", created = Instant.parse("2025-01-01T00:00:00Z"))
        s.save(b); s.save(a)
        val stray = File(s.root, "LEFTOVER").also { it.mkdirs() }
        assertEquals(listOf("A", "B"), s.loadTVs().map { it.name })
        assertFalse(stray.exists())
        s.delete(a)
        assertEquals(listOf("B"), s.loadTVs().map { it.name })
    }

    @Test fun readsSwiftJsonWithMissingAndUnknownFields() {
        val text = """{"id":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F","name":"Old TV","brand":"martian",
            "multipoint":11,"created":"2024-12-31T23:59:59Z","future":1}"""
        val tv = engineJson.decodeFromString(TVRecordSerializer, text)
        assertEquals(UUID.fromString("e621e1f8-c36c-495a-93fc-0c247a3e6e5f"), tv.id)
        assertEquals(Brand.OTHER, tv.brand)
        assertEquals(Multipoint.TEN, tv.multipoint)
        assertEquals(ColourMenu.HSL, tv.colourMenu)
        assertEquals(PanelType.UNKNOWN, tv.panel)
        assertTrue(tv.multipointKnown)
        assertFalse(tv.isDemo)
        assertEquals(Instant.parse("2024-12-31T23:59:59Z"), tv.created)
    }

    @Test fun writesSwiftKeysAndRawValues() {
        val tv = TVRecord(
            id = UUID.fromString("e621e1f8-c36c-495a-93fc-0c247a3e6e5f"), name = "X", brand = Brand.SAMSUNG,
            colourMenu = ColourMenu.RGB_SLIDERS, multipoint = Multipoint.NONE,
            created = Instant.parse("2025-01-01T00:00:00.789Z"),
        )
        val text = engineJson.encodeToString(TVRecordSerializer, tv)
        assertTrue(text, text.contains("\"id\":\"E621E1F8-C36C-495A-93FC-0C247A3E6E5F\""))
        assertTrue(text, text.contains("\"brand\":\"samsung\""))
        assertTrue(text, text.contains("\"colourMenu\":\"samsung\""))
        assertTrue(text, text.contains("\"multipoint\":0"))
        assertTrue(text, text.contains("\"created\":\"2025-01-01T00:00:00Z\""))
    }

    @Test fun guideStateRoundTripsAndToleratesBadFields() {
        val s = store()
        val tv = TVRecord(name = "G").also { s.save(it) }
        assertEquals(GuideState(), s.guideState(tv, SignalMode.HDR10))
        val m = Measurements(mutableMapOf("W0" to Vec3(95.0, 100.0, 108.0)))
        val st = GuideState(
            sessions = listOf(GuidedSession(started = Instant.parse("2025-01-01T00:00:00Z"), before = m)),
            finder = FinderData(overrides = mapOf("G50" to 3)),
            progress = GuideProgress(step = GuideStep.DARK, done = listOf(GuideStep.SETUP), colour = "RED"),
        )
        s.save(st, tv, SignalMode.HDR10)
        val back = s.guideState(tv, SignalMode.HDR10)
        assertEquals(st.progress, back.progress)
        assertEquals(st.finder, back.finder)
        assertEquals(100.0, back.reference.whiteY!!, 1e-9)
        assertNull(back.reference.basis)

        val lenient = engineJson.decodeFromString(
            GuideStateSerializer, """{"progress":{"step":"nope","done":["setup","later"],"colours":["RED"]}}""",
        )
        assertEquals(GuideStep.SETUP, lenient.progress.step)
        assertEquals(emptyList<GuideStep>(), lenient.progress.done)
        assertEquals(listOf("RED"), lenient.progress.colours)
    }

    @Test fun guideStepOrder() {
        assertEquals(GuideStep.BRIGHT, GuideStep.BEFORE.next)
        assertNull(GuideStep.AFTER.next)
        assertNull(GuideStep.FINE.next)
    }

    @Test fun exportWritesOneFile() {
        val s = store()
        val tv = TVRecord(name = "a/b").also { s.save(it) }
        s.save(GuideState(sessions = listOf(GuidedSession())), tv, SignalMode.SDR)
        s.appendLog(tv, "one\n"); s.appendLog(tv, "two\n")
        assertEquals("one\ntwo\n", File(s.folder(tv), "log.md").readText())
        val f = s.export(tv, null, app = "0.1", phone = "test")
        assertNotNull(f)
        assertEquals("a-b - TV Calibrator export.json", f!!.name)
        val text = f.readText()
        assertTrue(text, text.contains("\"sdr\""))
        assertFalse(text, text.contains("\"hdr10\""))
    }

    @Test fun everyBrandHasItsMenuPaths() {
        assertEquals(12, Brand.entries.size)
        for (b in Brand.entries) {
            assertTrue(b.name, b.whiteBalancePath.isNotBlank())
            assertTrue(b.name, b.colourMenuPath.isNotBlank())
            assertTrue(b.name, b.gamutSetting.isNotBlank())
            assertTrue(b.name, b.label.isNotBlank())
            assertEquals(b, Brand.fromRaw(b.raw))
            for (mode in SignalMode.entries) {
                val setup = b.setup(mode)
                assertTrue(setup.first().startsWith("Picture mode: "))
                assertTrue(setup.size >= 2)
            }
        }
        assertEquals("Picture › Expert Settings › White Balance (2 Point)", Brand.SAMSUNG.whiteBalancePath)
        assertEquals(
            "★ button › Advanced picture settings › Expert settings › Color tuning", Brand.ROKU.colourMenuPath,
        )
    }

    @Test fun brandDetails() {
        assertEquals("Bias", Brand.SONY.offsetName)
        assertEquals("Cutoff", Brand.PANASONIC.offsetName)
        assertEquals(ColourMenu.RGB_SLIDERS, Brand.SAMSUNG.colourMenu)
        assertEquals(Multipoint.NONE, Brand.ROKU.multipoint)
        assertEquals("Tint / Saturation / Luminance", Brand.LG.cmsMenuLabel)
        assertEquals("Calibrated Dark", Brand.VIZIO.pictureMode(SignalMode.HDR10))
        assertEquals("Professional (or Custom)", Brand.SONY.pictureMode(SignalMode.HDR10P))
        assertEquals("Dolby Vision Dark (or Dolby Vision Cinema)", Brand.SONY.pictureMode(SignalMode.DV))
        assertEquals("Movie", Brand.ROKU.pictureMode(SignalMode.SDR))
        assertEquals(
            "HDR10+ mode: the plain setting, not Adaptive / Dynamic", Brand.VIZIO.extras(SignalMode.HDR10P).last(),
        )
        assertTrue(Brand.TCL.extras(SignalMode.SDR).contains("Local dimming: Low or Medium"))
        assertNull(PanelType.UNKNOWN.profileKey)
        assertEquals("woled", PanelType.WOLED.profileKey)
        assertEquals(20, Multipoint.TWENTY.raw)
    }
}
