package io.github.yuroyami.kitepdf.epub.css

/**
 * The EPUB user-agent default stylesheet -- the lowest cascade layer, under all
 * author CSS. Adapted from MuPDF's `html_default_css` (the difftest oracle) and
 * trimmed to the properties this engine honours. This is what makes headings big
 * and bold, lists indented and bulleted, `<b>`/`<em>` styled, `<pre>` monospace,
 * etc., with no hard-coded tag logic in the layout walker.
 *
 * Dropped vs MuPDF: `@page`, `content` (`q:before`), `direction`/`unicode-bidi`
 * (bidi is Phase 4), and detailed border painting (Phase 3) -- those rules or
 * properties are ignored, not honoured, yet.
 */
internal object UaStylesheet {

    val rules: List<StyleRule> by lazy { CssParser.parse(CSS, Origin.UA) }

    private const val CSS = """
        a:link{color:blue;text-decoration:underline}
        address{display:block;font-style:italic}
        article,aside,footer,header,hgroup,main,nav,section{display:block}
        b,strong{font-weight:bold}
        blockquote{display:block;margin:1em 40px}
        body{display:block;margin:1em}
        caption,figcaption{display:block;text-align:center}
        cite,dfn,em,i,var{font-style:italic}
        code,kbd,samp,tt{font-family:monospace}
        dd{display:block;margin:0 0 0 40px}
        div{display:block}
        dl{display:block;margin:1em 0}
        dt{display:block;font-weight:bold}
        figure{display:block;margin:1em 40px}
        h1{display:block;font-size:2em;font-weight:bold;margin:0.67em 0}
        h2{display:block;font-size:1.5em;font-weight:bold;margin:0.83em 0}
        h3{display:block;font-size:1.17em;font-weight:bold;margin:1em 0}
        h4{display:block;font-size:1em;font-weight:bold;margin:1.33em 0}
        h5{display:block;font-size:0.83em;font-weight:bold;margin:1.67em 0}
        h6{display:block;font-size:0.67em;font-weight:bold;margin:2.33em 0}
        head{display:none}
        hr{display:block;margin:0.5em 0;text-align:center}
        html{display:block}
        li{display:list-item}
        mark{background-color:yellow}
        ol{display:block;list-style-type:decimal;margin:1em 0;padding:0 0 0 30pt}
        p{display:block;margin:1em 0}
        pre{display:block;font-family:monospace;margin:1em 0;white-space:pre}
        rp{display:none}
        rt{display:none}
        script{display:none}
        small{font-size:0.83em}
        style{display:none}
        sub{font-size:0.83em;vertical-align:sub}
        sup{font-size:0.83em;vertical-align:super}
        table{display:table}
        tbody{display:table-row-group}
        td{display:table-cell;padding:1px}
        tfoot{display:table-footer-group}
        th{display:table-cell;font-weight:bold;padding:1px;text-align:center}
        thead{display:table-header-group}
        title{display:none}
        tr{display:table-row}
        ul{display:block;list-style-type:disc;margin:1em 0;padding:0 0 0 30pt}
        ul ul{list-style-type:circle}
        ul ul ul{list-style-type:square}
    """
}
