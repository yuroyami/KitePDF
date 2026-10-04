package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stack recovery must preserve the same tree when an ancestor is closed or restored. */
class HtmlParserRecoveryTest {

    private fun assertTree(expected: String, markup: String) {
        fun serialize(node: KiteXmlNode): String = when (node) {
            is KiteXmlNode.Text -> node.text
            is KiteXmlNode.Comment -> "<!--${node.text}-->"
            is KiteXmlNode.Element -> "<${node.tag}>" + node.children.joinToString("") { serialize(it) } + "</${node.tag}>"
        }
        val root = HtmlParser.parse(markup)
        assertEquals(expected, root.children.joinToString("") { serialize(it) }, markup)
    }

    @Test
    fun closing_repeated_tags_restores_each_outer_match() {
        assertTree(
            "<span>A<span>B<span>C</span>D</span>E<span>F</span>G</span>H",
            "<span>A<span>B<span>C</span>D</span>E<span>F</span>G</span>H",
        )
    }

    @Test
    fun mismatched_close_forgets_popped_tags_and_allows_them_to_reopen() {
        assertTree(
            "<section><div><span><em>A</em></span></div><span>B</span><div>C</div></section><em>D</em>",
            "<section><div><span><em>A</div><span>B</span></em><div>C</div></section><em>D</em>",
        )
    }

    @Test
    fun unmatched_close_leaves_the_current_parent_unchanged() {
        assertTree(
            "<body><section><span>AB</span></section>CD</body>",
            "<body><section><span>A</missing>B</section>C</span>D</body>",
        )
    }

    @Test
    fun every_list_container_blocks_the_outer_item_then_restores_it() {
        for (container in listOf("ul", "ol", "menu")) {
            assertTree(
                "<ul><li>A<$container><span><li>B</li><li>C</li></span></$container></li><li>D</li></ul>",
                "<ul><li>A<$container><span><li>B<li>C</$container><li>D</ul>",
            )
        }
    }

    @Test
    fun nested_definition_list_blocks_outer_items_and_restores_both_item_kinds() {
        assertTree(
            "<dl><dt>A<dl><span><dd>B</dd><dt>C</dt></span></dl></dt><dd>D</dd><dt>E</dt></dl>",
            "<dl><dt>A<dl><span><dd>B<dt>C</dl><dd>D<dt>E</dl>",
        )
    }

    @Test
    fun table_and_row_group_scopes_block_outer_cells_until_the_scope_closes() {
        for (scope in listOf("table", "thead", "tbody", "tfoot")) {
            assertTree(
                "<table><tr><td>A<$scope><span><th>B</th><td>C</td></span></$scope></td><th>D</th></tr><tr><td>E</td></tr></table>",
                "<table><tr><td>A<$scope><span><th>B<td>C</$scope><th>D<tr><td>E</table>",
            )
        }
    }

    @Test
    fun nested_table_blocks_the_outer_row_then_restores_it() {
        assertTree(
            "<table><tr><td>A<table><tbody><tr><td>B</td></tr><tr><th>C</th></tr></tbody></table></td></tr><tr><td>D</td></tr></table>",
            "<table><tr><td>A<table><tbody><tr><td>B<tr><th>C</table><tr><td>D</table>",
        )
    }

    @Test
    fun row_groups_do_not_block_recovery_of_an_outer_row() {
        for (group in listOf("thead", "tbody", "tfoot")) {
            assertTree(
                "<table><tr><td>A<$group><span></span></$group></td></tr><tr><td>B</td></tr></table>",
                "<table><tr><td>A<$group><span><tr><td>B</table>",
            )
        }
    }

    @Test
    fun implicit_item_close_removes_its_paragraph_before_paragraph_recovery() {
        assertTree(
            "<dl><dd><p>A<em>B</em></p></dd><dt>C<p>D</p></dt><dd>E</dd></dl>",
            "<dl><dd><p>A<em>B<dt>C<p>D<dd>E</dl>",
        )
    }

    @Test
    fun self_closing_and_void_elements_never_replace_an_open_match() {
        assertTree(
            "<div>A<div></div>B<span></span>CD<br></br>EF<img></img>G</div>H",
            "<div>A<div/>B<span/>C</span>D<br>E</br>F<img/>G</div>H",
        )
    }
}
