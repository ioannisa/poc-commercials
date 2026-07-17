package eu.anifantakis.commercials.server.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthPagesTest {

    @Test
    fun escapesEveryHtmlMetaCharacter() {
        assertEquals(
            "&amp; &lt;b&gt; &quot;x&quot; &#39;y&#39;",
            htmlEscape("""& <b> "x" 'y'"""),
        )
    }

    /**
     * client_name comes from OPEN dynamic registration - attacker-controlled.
     * A script tag in it must render inert, in both the heading and the note.
     */
    @Test
    fun authorizePageNeutralizesHostileClientName() {
        val page = renderAuthorizePage("""<script>alert(1)</script>""", emptyMap())
        assertFalse(page.contains("<script>alert(1)</script>"))
        assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }

    /** Hidden params are query values - escaped in both attribute positions. */
    @Test
    fun hiddenFieldsEscapeNamesAndValues() {
        val page = renderAuthorizePage(
            "App",
            mapOf("state" to """"><script>alert(2)</script>"""),
        )
        assertFalse(page.contains("<script>alert(2)</script>"))
        assertTrue(page.contains("&quot;&gt;&lt;script&gt;alert(2)&lt;/script&gt;"))
    }

    @Test
    fun errorMessageRendersEscaped() {
        val page = renderAuthorizePage("App", emptyMap(), error = "<img onerror=x>")
        assertFalse(page.contains("<img onerror=x>"))
        assertTrue(page.contains("class=\"error\""))
    }

    @Test
    fun errorPageEscapesBothSlots() {
        val page = renderOAuthErrorPage("<b>T</b>", "<i>M</i>")
        assertFalse(page.contains("<b>T</b>"))
        assertFalse(page.contains("<i>M</i>"))
    }
}
