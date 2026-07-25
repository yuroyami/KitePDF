<#--
  Kite: Dokka header override.

  Same as Dokka 2.2.0's default header, plus a link group that gets you back to
  the guide site and the repository. Without it the API reference is a dead end:
  you can go deeper, but never back out to prose.

  https://yuroyami.github.io/KitePDF/ and https://github.com/yuroyami/KitePDF are substituted per repository by
  _kite-docs/sync.sh. Edit the source in _kite-docs/, not the copies.
-->
<#import "source_set_selector.ftl" as source_set_selector>
<#macro display>
    <header class="navigation theme-dark" id="navigation-wrapper" role="banner">
        <@template_cmd name="pathToRoot">
            <a class="library-name--link" href="${pathToRoot}index.html" tabindex="1">
                <@template_cmd name="projectName">
                    ${projectName}
                </@template_cmd>
            </a>
        </@template_cmd>
        <button class="navigation-controls--btn navigation-controls--btn_toc ui-kit_mobile-only" id="toc-toggle"
                type="button">Toggle table of contents
        </button>
        <div class="navigation-controls--break ui-kit_mobile-only"></div>
        <div class="library-version" id="library-version">
            <#-- This can be handled by the versioning plugin -->
            <@version/>
        </div>
        <nav class="kite-nav-links" aria-label="Kite documentation">
            <a href="https://yuroyami.github.io/KitePDF/">Guide</a>
            <a href="https://github.com/yuroyami/KitePDF">GitHub</a>
        </nav>
        <div class="navigation-controls">
            <@source_set_selector.display/>
            <#if homepageLink?has_content>
                <a class="navigation-controls--btn navigation-controls--btn_homepage" id="homepage-link"
                   href="${homepageLink}"></a>
            </#if>
            <button class="navigation-controls--btn navigation-controls--btn_theme" id="theme-toggle-button"
                    type="button">Switch theme
            </button>
            <div class="navigation-controls--btn navigation-controls--btn_search" id="searchBar" role="button">Search in
                API
            </div>
        </div>
    </header>
</#macro>
