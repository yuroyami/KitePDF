package io.github.yuroyami.kitepdf.epub

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GPOS marks take the lookups of the language system of their text, and a mark-to-mark lookup
 * passes over the marks that its flag leaves out (#915). The expected places are what hb-shape
 * gives for the same font and text.
 */
@OptIn(ExperimentalEncodingApi::class)
class MarkLookupPlanTest {

    /**
     * Built with fontTools' feaLib: a base "a", top marks acute, grave and macron. The mark feature
     * puts them at (100, 500) for the default language system of latn and at (300, 600) for VIT.
     * The mkmk lookup, with MarkAttachmentType [acute grave], stacks a mark 200 units above an acute.
     */
    private val font = Base64.decode("AAEAAAAMAIAAAwBAR0RFRgAbACMAAAM0AAAAJEdQT1Njq2H+AAADWAAAAOxPUy8yQVdD6QAAAUgAAABgY21hcAbIA10AAAHAAAAAUGdseWbj5pGjAAACIAAAAHZoZWFkLo09mAAAAMwAAAA2aGhlYQTRALsAAAEEAAAAJGhtdHgE5P8kAAABqAAAABhsb2NhAIUAbQAAAhAAAAAObWF4cAAIAAYAAAEoAAAAIG5hbWXXH/PvAAACmAAAAGlwb3N0AJIBZwAAAwQAAAAuAAEAAAABAADWZSBxXw889QADA+gAAAAA5xZ8/gAAAADnFnz+/4gAAAHCArwAAAADAAIAAAAAAAAAAQAAA4T/OAAAAfT/iP+IAcIAAQAAAAAAAAAAAAAAAAAAAAYAAQAAAAYABAABAAAAAAACAAAAAAAAAAAAAAAAAAAAAAADAaEBkAAFAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEEAAAAAAAAAAAAAAAA/Pz8/AAAAIAMEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAH0ADIB9AAyAAD/nAAA/5wAAP+IAPoAAAAAAAIAAAADAAAAFAADAAEAAAAUAAQAPAAAAAoACAACAAIAIABhAwEDBP//AAAAIABhAwADBP///+X/oAAA/QAAAQAAAAAABgAAAAAAAwACAAAADQAaACUAMAA7ADsAAAABADIAAAHCArwAAwAAMyERITIBkP5wArwAAAEAMgAAAcIB9AADAAAzIREhMgGQ/nAB9AAAAf+cAAAAZACWAAMAACMzNSNkyMiWAAH/nAAAAGQAlgADAAAjMzUjZMjIlgAB/4gAAAB4ADwAAwAAIzM1I3jw8DwAAAAAAAQANgABAAAAAAABAAoAAAABAAAAAAACAAcACgADAAEECQABABQAEQADAAEECQACAA4AJUtpdGUgTWFya3NSZWd1bGFyAEsAaQB0AGUAIABNAGEAcgBrAHMAUgBlAGcAdQBsAGEAcgAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYAAABEAI0AQwDaAAMAAAABAAAADAAAAAAAGgABAAEABAABAAMAAwADAAEAAgACAAEAAQABAAAAEgAgAAoAAwAqADIAOgACREZMVAA6bGF0bgA+AANtYXJrADptYXJrAEBta21rAEYABAAAAAEAOAAEAAAAAQA8AAYBAAABAEAARAAAAEAAAVZJVCAASgAAAAEAAQAAAAEAAAAAAAEAAgABAFAARAABAFoAOAABAEQAOAABAE4AMAABADgAMgABAEIAKAAA//8AAgABAAIAAP//AAIAAAACAAEAMAABADIAAQA0AAEAAQABAAEAAQACAAEAAwACAAMABAADAAAAIAAAACAAAAAgAAEAZAH0AAEBLAJYAAEAAADIAAEAAAAA")

    private fun places(text: String, language: String? = null): List<Pair<Int, Int>> =
        KiteShaper.load(font).shape(text, rightToLeft = false, language = language).glyphs.map { kotlin.math.round(it.x).toInt() to kotlin.math.round(it.y).toInt() }

    @Test
    fun the_default_language_system_takes_its_own_lookup() {
        assertEquals(listOf(0 to 0, 100 to 500), places("á"))
        assertEquals(listOf(0 to 0, 100 to 500), places("á", "en"))
    }

    @Test
    fun vietnamese_takes_the_lookup_of_vit() {
        assertEquals(listOf(0 to 0, 300 to 600), places("á", "vi"))
        assertEquals(listOf(0 to 0, 300 to 600), places("á", "vi-VN"))
    }

    @Test
    fun a_mark_to_mark_lookup_passes_over_a_mark_of_another_class() {
        // hb-shape: the macron, outside the attachment class, sits on the base; the grave stacks on the acute.
        assertEquals(listOf(0 to 0, 100 to 500, 100 to 500, 100 to 700), places("á̄̀"))
    }
}
