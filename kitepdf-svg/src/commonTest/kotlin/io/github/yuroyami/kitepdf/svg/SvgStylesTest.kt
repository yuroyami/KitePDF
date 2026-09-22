package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CSS expected values come from SVG 1.1, 6.4/6.6 and CSS 2.2, 5/6, not mutool (#89). */
class SvgStylesTest {
    private val red = RgbColor(1.0, 0.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    private fun calls(body: String): List<RecordingCanvas.Call> {
        val image = assertNotNull(SvgImage.parse("<svg width='100' height='100'>$body</svg>".encodeToByteArray()))
        val canvas = RecordingCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        return canvas.calls
    }

    private fun fills(body: String) = calls(body).filterIsInstance<RecordingCanvas.Call.Fill>()
    private fun rect(attrs: String = "") = "<rect width='10' height='10' $attrs/>"

    @Test
    fun embedded_class_rule_paints_the_issue_fixture_green() {
        val fill = fills("<style>.cls-1 { fill: #00ff00; }</style>" + rect("class='cls-1'")).single()
        assertEquals(green, fill.color)
    }

    @Test
    fun selectors_match_type_id_and_whole_case_sensitive_class_tokens() {
        val drawn = fills("""
            <style>rect { fill: red } .color { fill: lime } #chosen { fill: blue }</style>
            ${rect("class='other color another'")}
            ${rect("class='colored'")}
            ${rect("class='COLOR'")}
            ${rect("id='chosen' class='color'")}
            <circle r='4'/>
        """.trimIndent())
        assertEquals(listOf(green, red, red, blue, RgbColor.BLACK), drawn.map { it.color })
    }

    @Test
    fun comma_lists_and_compound_selectors_require_all_components() {
        val drawn = fills("""
            <style>.a, circle { fill: red } rect.a.b#chosen { fill: lime }</style>
            ${rect("id='chosen' class='b a'")}
            ${rect("id='chosen' class='a'")}
            <circle class='b' r='4'/>
            ${rect("class='b'")}
        """.trimIndent())
        assertEquals(listOf(green, red, red, RgbColor.BLACK), drawn.map { it.color })
    }

    @Test
    fun specificity_precedes_source_order_and_only_matching_list_members_count() {
        val drawn = fills("""
            <style>
              #chosen { fill: lime } rect.a.b { fill: blue } .a { fill: red } rect { fill: black }
              #not-this-one, .a { stroke: red } .a.b { stroke: blue }
            </style>
            ${rect("id='chosen' class='a b'")}
            ${rect("class='a b'")}
        """.trimIndent())
        assertEquals(listOf(green, blue), drawn.map { it.color })
        val stroke = calls("""
            <style>#absent, .a { stroke: red } .a.b { stroke: blue }</style>
            ${rect("class='a b'")}
        """.trimIndent()).filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(blue, stroke.color)
    }

    @Test
    fun presentation_hints_lose_to_universal_rules_and_inline_beats_normal_rules() {
        val drawn = fills("""
            <style>* { fill: lime } #chosen { fill: blue }</style>
            ${rect("fill='red'")}
            ${rect("id='chosen' fill='red' style='fill:lime'")}
        """.trimIndent())
        assertEquals(listOf(green, green), drawn.map { it.color })
    }

    @Test
    fun later_rules_sheets_and_duplicate_declarations_win_equal_specificity() {
        val drawn = fills("""
            <style>.a { fill: red; fill: lime } .a { fill: blue }</style>
            ${rect("class='a'")}
            <style>.a { fill: red; fill: lime }</style>
            ${rect("style='fill:red; fill:blue'")}
        """.trimIndent())
        assertEquals(listOf(green, blue), drawn.map { it.color }, "all style elements apply to the whole SVG")
    }

    @Test
    fun important_precedes_inline_normal_but_inline_important_wins() {
        val drawn = fills("""
            <style>#chosen { fill: red } .a { fill: lime ! important; fill: blue }</style>
            ${rect("id='chosen' class='a' style='fill:red'")}
            ${rect("class='a' style='fill:blue !IMPORTANT; fill:red'")}
            ${rect("class='a' style='fill:red !important; fill:blue !important'")}
        """.trimIndent())
        assertEquals(listOf(green, blue, blue), drawn.map { it.color })
    }

    @Test
    fun importance_is_compared_per_property_and_does_not_inherit_as_priority() {
        val drawn = calls("""
            <style>.parent { fill: blue !important; stroke: red } rect { fill: lime; stroke: blue !important }</style>
            <g class='parent'>${rect("style='stroke:red; stroke-width:3'")}</g>
        """.trimIndent())
        assertEquals(green, drawn.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        val stroke = drawn.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(blue, stroke.color)
        assertEquals(3.0, stroke.lineWidth)
    }

    @Test
    fun inherited_paint_and_explicit_inherit_keep_computed_lengths() {
        val drawn = calls("""
            <style>.parent { fill: lime; stroke: blue; font-size: 10px; stroke-width: 2em } .child { fill: inherit; stroke-width: inherit; font-size: 30px }</style>
            <g class='parent'>${rect("class='child' fill='red' stroke-width='1'")}</g>
        """.trimIndent())
        assertEquals(green, drawn.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        val stroke = drawn.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(blue, stroke.color)
        assertEquals(20.0, stroke.lineWidth, "inherit uses the parent's computed width, not 2em at the child's font size")
    }

    @Test
    fun explicit_inherit_keeps_the_parents_gradient_reference() {
        val drawn = fills("""
            <style>.parent { fill: url(#gradient) } rect { fill: inherit }</style>
            <defs><linearGradient id='gradient'><stop stop-color='lime'/></linearGradient></defs>
            <g class='parent'>${rect("fill='red'")}</g>
        """.trimIndent())
        assertEquals(green, drawn.single().color)
    }

    @Test
    fun styles_reach_tspans_and_inherited_current_color() {
        val drawn = calls("""
            <style>text { color: blue; fill: currentColor; font-size: 12px } .word { fill: lime; font-weight: bold }</style>
            <text y='20'>one<tspan class='word'>two</tspan></text>
        """.trimIndent()).filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(listOf(blue, green), drawn.map { it.color })
        assertEquals(listOf(12.0, 12.0), drawn.map { it.fontSize })
        assertTrue(drawn[1].fontSpec.bold)
    }

    @Test
    fun display_hides_the_subtree_but_visibility_can_be_overridden() {
        val drawn = fills("""
            <style>.gone { display:none } .hidden { visibility:hidden } .shown { visibility:visible; fill:lime }</style>
            <g class='gone'>${rect("class='shown'")}</g>
            <g class='hidden'>${rect()}${rect("class='shown'")}</g>
        """.trimIndent())
        assertEquals(listOf(green), drawn.map { it.color })
    }

    @Test
    fun definitions_gradient_stops_transform_and_clip_use_the_cascade() {
        val drawn = calls("""
            <style>.ink { fill: url(#gradient); transform: translate(7,9); clip-path: url(#clip) } stop { stop-color: lime }</style>
            <defs>
              <linearGradient id='gradient'><stop offset='0' stop-color='red'/><stop offset='1' stop-color='blue'/></linearGradient>
              <clipPath id='clip'>${rect()}</clipPath>
              <rect id='shape' class='ink' width='10' height='10'/>
            </defs>
            <use href='#shape'/>
        """.trimIndent())
        assertEquals(green, drawn.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        val clip = drawn.filterIsInstance<RecordingCanvas.Call.PushClip>().single()
        val bounds = clip.path.segments.filterIsInstance<KitePath.Segment.MoveTo>().first()
        assertEquals(7.0, bounds.x)
        assertEquals(9.0, bounds.y)
    }

    @Test
    fun comments_cdata_and_property_case_preserve_declaration_boundaries() {
        val drawn = calls("""
            <defs><style type='text/css'><![CDATA[
              /* before */ .a { FiLl: rgb(0, 255, 0); /* inside */ STROKE: blue; stroke-width: 2 }
            ]]></style></defs>
            ${rect("class='a' style='font-family: &quot;a;b:c&quot;; fill:lime'")}
        """.trimIndent())
        assertEquals(green, drawn.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        assertEquals(blue, drawn.filterIsInstance<RecordingCanvas.Call.Stroke>().single().color)
    }

    @Test
    fun quoted_semicolons_colons_and_comment_markers_stay_inside_a_value() {
        val glyphs = calls("""
            <style>text { font-family: "a;b:c/*d*/"; fill: lime }</style>
            <text y='20'>word</text>
        """.trimIndent()).filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
        assertEquals("\"a;b:c/*d*/\"", glyphs.fontSpec.name)
        assertEquals(green, glyphs.color)
    }

    @Test
    fun unsupported_selectors_and_nested_at_rules_cannot_leak_styles() {
        val drawn = fills("""
            <style>
              @import url('anything.css');
              @media all { rect { fill: red !important } @supports (display:grid) { .a { fill: red } } }
              g rect { fill:red } g > rect { fill:red } rect + rect { fill:red }
              [class=a] { fill:red } .a:hover { fill:red } svg|rect { fill:red }
              .a, :unknown { fill:red } .a, { fill:red } .a\:b { fill:red }
              .a { fill:lime }
            </style>
            <g>${rect("class='a'")}${rect()}</g>
        """.trimIndent())
        assertEquals(listOf(green, RgbColor.BLACK), drawn.map { it.color })
    }

    @Test
    fun unsupported_stylesheet_types_and_media_do_not_apply() {
        val drawn = fills("""
            <style type='text/other'>rect { fill:red }</style>
            <style media='print'>rect { fill:red }</style>
            <style media='screen and (min-width:1px)'>rect { fill:red }</style>
            <style media='screen, print'>rect { fill:lime }</style>
            ${rect()}
        """.trimIndent())
        assertEquals(green, drawn.single().color)
    }

    @Test
    fun invalid_declarations_do_not_displace_valid_fallbacks_or_hide_siblings() {
        val drawn = fills("""
            <style>.a { fill:lime; fill:nonsense; fill:; fill:blue !not-important; unknown:x } .b { fill:blue }</style>
            ${rect("class='a'")}${rect("class='b'")}
        """.trimIndent())
        assertEquals(listOf(green, blue), drawn.map { it.color })
    }
}
