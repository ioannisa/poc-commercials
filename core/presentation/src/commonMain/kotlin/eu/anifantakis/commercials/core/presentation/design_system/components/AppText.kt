package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.collection.LruCache
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.FallbackText
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The app's semantic text roles (dealer-totem pattern): screens pick a ROLE,
 * never a size - the type system (and the user's font-size preference)
 * decides how the role renders. Add a role when a real recurring need
 * appears; resist one-off styles.
 */
enum class AppTextStyle {
    /** Screen header next to the back arrow. */
    SCREEN_TITLE,
    /** Card/section/dialog headers. */
    SECTION_TITLE,
    /** Prominent title of a list item / banner (station name, user name). */
    ITEM_TITLE,
    /** Emphasised inline text (bold body). */
    BODY_STRONG,
    /** The default reading text of the app. */
    BODY,
    /** Secondary/explanatory text under fields and tables. */
    NOTE,
    /** The smallest text (chips, footnotes, dense meta). */
    TINY,
    /** Column header of a dense data table (finder console, placements). */
    TABLE_HEADER,
    /** Cell of a dense data table. */
    TABLE_CELL,
    /** Emphasised (e.g. selected-row) cell of a dense data table. */
    TABLE_CELL_STRONG,
    /** Console/log lines - monospaced, dense. */
    LOG_LINE,
    /** Monospaced emphasis (recovery codes, tokens, ids). */
    MONO,
    /** Big number of a stat header. */
    STAT_VALUE,
    /** The label beside/above a stat value. */
    STAT_LABEL,
    /** Inline error/validation text. */
    ERROR_NOTE,
    /** Title slot of an AlertDialog (keeps the Material dialog-title look). */
    DIALOG_TITLE,
    /**
     * Label inside a Button/TextButton/DropdownMenuItem: the component's own
     * content color passes through (enabled/disabled/error states keep
     * working), the size is the Material button label - scaled.
     */
    BUTTON,
    /** Emphasised (bold) button label - the dialog's primary action. */
    BUTTON_STRONG,
    /**
     * Label/placeholder slot of a TextField: inherits BOTH the slot's
     * animated style (resting <-> floating) and its state color
     * (focused/unfocused/error) - AppText only routes it through the system.
     */
    FIELD_LABEL,
}

@Composable
private fun resolveAppTextStyle(style: AppTextStyle): Pair<TextStyle, Color> {
    val t = AppTheme.typography
    val actualStyle: TextStyle = when (style) {
        AppTextStyle.SCREEN_TITLE -> t.screenTitle
        AppTextStyle.SECTION_TITLE -> t.sectionTitle
        AppTextStyle.ITEM_TITLE -> t.material.titleMedium
        AppTextStyle.BODY_STRONG -> t.material.bodyMedium.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.BODY -> t.material.bodyMedium
        AppTextStyle.NOTE -> t.material.bodySmall
        AppTextStyle.TINY -> t.material.labelSmall
        AppTextStyle.TABLE_HEADER -> t.material.labelSmall.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.TABLE_CELL -> t.material.bodySmall
        AppTextStyle.TABLE_CELL_STRONG -> t.material.bodySmall.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.LOG_LINE -> t.logLine
        AppTextStyle.MONO -> t.mono
        AppTextStyle.STAT_VALUE -> t.statValue
        AppTextStyle.STAT_LABEL -> t.statLabel
        AppTextStyle.ERROR_NOTE -> t.material.bodySmall
        AppTextStyle.DIALOG_TITLE -> t.material.headlineSmall
        AppTextStyle.BUTTON -> t.material.labelLarge
        AppTextStyle.BUTTON_STRONG -> t.material.labelLarge.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.FIELD_LABEL -> LocalTextStyle.current
    }
    val defaultColor: Color = when (style) {
        AppTextStyle.SCREEN_TITLE,
        AppTextStyle.SECTION_TITLE,
        AppTextStyle.ITEM_TITLE,
        AppTextStyle.BODY_STRONG,
        AppTextStyle.BODY,
        AppTextStyle.TABLE_HEADER,
        AppTextStyle.TABLE_CELL,
        AppTextStyle.TABLE_CELL_STRONG,
        AppTextStyle.LOG_LINE,
        AppTextStyle.MONO,
        AppTextStyle.STAT_VALUE,
        AppTextStyle.DIALOG_TITLE -> MaterialTheme.colorScheme.onSurface

        AppTextStyle.NOTE,
        AppTextStyle.TINY,
        AppTextStyle.STAT_LABEL -> MaterialTheme.colorScheme.onSurfaceVariant

        AppTextStyle.ERROR_NOTE -> MaterialTheme.colorScheme.error

        // Slot roles: the enclosing component decides (button states,
        // focused-label color) - never override with a theme constant.
        AppTextStyle.BUTTON,
        AppTextStyle.BUTTON_STRONG,
        AppTextStyle.FIELD_LABEL -> LocalContentColor.current
    }
    return actualStyle to defaultColor
}

/**
 * Semantic text. The three call shapes, simplest first:
 * ```
 * // 1. Role only - the normal case (~95% of call sites)
 * AppText("Crete TV", AppTextStyle.ITEM_TITLE)
 *
 * // 2. Role + overrides - a local deviation, visible at the call site
 * AppText("CTV-2026-014", AppTextStyle.MONO, letterSpacing = 2.sp)
 *
 * // 3. Rich text - auto-detected markup; links tap through onUrlClick
 * AppText(
 *     "See the <b>terms</b> at <a href=\"https://example.com\">example.com</a>",
 *     AppTextStyle.BODY,
 *     onUrlClick = { url -> /* navigate / open */ },
 * )
 * ```
 *
 * Two layers of capability:
 *
 * 1. ROLE (mandatory): [style] resolves through [AppTheme.typography], so the
 *    user's font-size preference scales every call site. The role is never
 *    optional - a deviation starts FROM a role, it does not replace it.
 * 2. INFLECTION overrides (optional): [fontWeight] / [fontStyle] /
 *    [letterSpacing] / [lineHeight] ride Material Text's own params, merged
 *    via `TextStyle.fastMerge` - when everything is Unspecified the resolved
 *    role style passes through UNTOUCHED, so plain call sites pay nothing.
 *    These axes tune EMPHASIS and DENSITY - they cannot contradict what the
 *    role IS (a bold BODY is still body text). The IDENTITY axes are
 *    deliberately absent: no `fontSize` (size IS the hierarchy, and an
 *    override would silently bypass the user's text-size preference - on
 *    desktop the app slider is the ONLY scaling) and no `fontFamily` (family
 *    is semantics - a mono-faced BODY is a MONO in disguise). Need a
 *    different size or face? That is a NEW ROLE (one enum entry + two
 *    resolver branches); a genuine one-off goes through the AnnotatedString
 *    overload with an explicit SpanStyle.
 *
 * RICH TEXT, auto-detected: when [text] contains markup (see [looksLikeHtml] -
 * plain strings never touch the parser), it is parsed ONCE per
 * (text, linkColor) into a process-wide LRU cache and rendered as styled
 * spans. The parser is a deliberate SUBSET, one implementation for every
 * platform (`AnnotatedString.fromHtml` is Android-only): `<b>/<strong>`,
 * `<i>/<em>`, `<u>`, `<a href="…">`, `<br>`, character entities. Unknown tags
 * are stripped, their content kept. NOTE: the rich path renders an
 * AnnotatedString, so it bypasses the glyph FALLBACK (same as the
 * AnnotatedString overload) - Hebrew inside markup needs FontFallback work.
 *
 * Links are deliberately STATIC: clickable `LinkAnnotation`s make BasicText
 * install a TextLinkScope that re-measures link bounds every frame while the
 * item moves, which janks scrolling lists - so links render as
 * color+underline spans and taps are resolved manually against the glyph
 * bounds ([tappedCharOffset]). The gesture is claimed only when the finger
 * lands ON a link glyph: taps elsewhere reach an enclosing clickable, and
 * drags cancel (scroll keeps working). [onUrlClick] handles link taps; null
 * opens via [LocalUriHandler]. Link color is the theme primary (cache-keyed:
 * the palette changes it).
 *
 * A11Y trade-off, stated honestly: static spans are NOT individually
 * focusable by screen readers (LinkAnnotation's semantics are exactly what
 * this path removes). Fine for footnote-style links inside running text; if
 * a link is the PRIMARY action of a screen, give it a real AppButton.
 */
@Composable
fun AppText(
    text: String,
    style: AppTextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration = TextDecoration.None,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val (resolvedStyle, defaultColor) = resolveAppTextStyle(style)
    val resolvedColor = if (color != Color.Unspecified) color else defaultColor

    if (!text.looksLikeHtml()) {
        // Roboto has no Hebrew (nor Chinese, nor Arabic). FallbackText hands
        // anything it cannot draw to a face that can - and keeps Compose's
        // plain-string fast path for everything else. See FontFallback.kt.
        // Overrides merge via fastMerge - free when everything is Unspecified.
        FallbackText(
            text = text,
            modifier = modifier,
            color = resolvedColor,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            textAlign = textAlign,
            textDecoration = textDecoration,
            softWrap = softWrap,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout,
            style = resolvedStyle,
        )
        return
    }

    // Rich path: parsed once per (text, linkColor) - see parsedHtmlCache.
    val linkColor = MaterialTheme.colorScheme.primary
    val parsed = remember(text, linkColor) { parseHtmlCached(text, linkColor) }

    val layoutHolder = remember { TextLayoutHolder() }
    val currentOnUrlClick by rememberUpdatedState(onUrlClick)
    val uriHandler = LocalUriHandler.current

    val linkTapModifier = if (parsed.links.isEmpty()) {
        Modifier
    } else {
        // awaitEachGesture instead of detectTapGestures so the gesture is
        // claimed ONLY when the finger lands on a link glyph: taps elsewhere
        // stay unconsumed and reach an enclosing clickable, and scroll drags
        // cancel via waitForUpOrCancellation.
        Modifier.pointerInput(parsed.links) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val layout = layoutHolder.value ?: return@awaitEachGesture
                val tappedChar = layout.tappedCharOffset(down.position) ?: return@awaitEachGesture
                val link = parsed.links.firstOrNull { tappedChar >= it.start && tappedChar < it.end }
                    ?: return@awaitEachGesture
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                val handler = currentOnUrlClick
                if (handler != null) {
                    handler(link.url)
                } else {
                    try {
                        uriHandler.openUri(link.url)
                    } catch (_: IllegalArgumentException) {
                        // nothing can handle the URI - ignore, like TextLinkScope does
                    }
                }
            }
        }
    }

    Text(
        text = parsed.annotatedString,
        style = resolvedStyle,
        modifier = modifier.then(linkTapModifier),
        color = resolvedColor,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        textDecoration = textDecoration,
        textAlign = textAlign,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { result ->
            layoutHolder.value = result
            onTextLayout?.invoke(result)
        },
    )
}

/**
 * The AnnotatedString overload: the caller already owns the spans (mixed
 * weights, coloured words), so there is no HTML detection here - only the
 * role plus the same optional overrides.
 */
@Composable
fun AppText(
    text: AnnotatedString,
    style: AppTextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val (resolvedStyle, defaultColor) = resolveAppTextStyle(style)
    Text(
        text = text,
        style = resolvedStyle,
        modifier = modifier,
        color = if (color != Color.Unspecified) color else defaultColor,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        textAlign = textAlign,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout ?: {},
    )
}

// ── rich-text machinery (internal for the parser tests) ─────────────────────

/** A link range extracted from parsed markup: text span [start, end) that opens [url]. */
internal data class HtmlLink(val start: Int, val end: Int, val url: String)

internal class ParsedHtml(
    val annotatedString: AnnotatedString,
    val links: List<HtmlLink>,
)

/**
 * Plain holder instead of MutableState: read only from the gesture handler,
 * never from composition, so snapshot bookkeeping on every text layout is
 * wasted work.
 */
private class TextLayoutHolder {
    var value: TextLayoutResult? = null
}

private const val MAX_ENTITY_LENGTH = 16

/**
 * Cheap allocation-free check. A '&' only counts as HTML when it starts an
 * entity ("&amp;", "&#39;", ...), so plain text like "AEK & PAOK" stays on
 * the fast path instead of going through the parser.
 */
internal fun String.looksLikeHtml(): Boolean {
    if (indexOf('<') >= 0) return true
    var amp = indexOf('&')
    while (amp >= 0) {
        var i = amp + 1
        val limit = minOf(length, i + MAX_ENTITY_LENGTH)
        while (i < limit) {
            val c = this[i]
            if (c == ';') {
                if (i > amp + 1) return true
                break
            }
            if (!c.isLetterOrDigit() && c != '#') break
            i++
        }
        amp = indexOf('&', amp + 1)
    }
    return false
}

private data class HtmlCacheKey(val text: String, val linkColor: Color)

// Process-wide cache: LazyColumn items lose their remember slots when scrolled
// off-screen, so without this every scroll-in re-runs the parse. Keyed on the
// link color too - the palette (light/dark) changes it. Both fields of the
// value are immutable; sharing instances is safe.
private val parsedHtmlCache = LruCache<HtmlCacheKey, ParsedHtml>(128)

private fun parseHtmlCached(text: String, linkColor: Color): ParsedHtml {
    val key = HtmlCacheKey(text, linkColor)
    parsedHtmlCache.get(key)?.let { return it }
    val result = parseHtmlSubset(text, linkColor)
    parsedHtmlCache.put(key, result)
    return result
}

private val HREF_REGEX = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val STYLED_TAGS = setOf("b", "strong", "i", "em", "u", "a")

private class OpenTag(val name: String, val start: Int, val href: String?)
private class SpanRange(val style: SpanStyle, val start: Int, val end: Int)

/**
 * Minimal markup parser, ONE implementation for every platform
 * (`AnnotatedString.fromHtml` exists only on Android; an expect/actual would
 * fragment behavior per platform). The supported subset IS the contract:
 * `<b>/<strong>`, `<i>/<em>`, `<u>`, `<a href="…">`, `<br>`, and the
 * entities `&amp; &lt; &gt; &quot; &apos; &#39; &nbsp;` plus numeric
 * (`&#..;`/`&#x..;`). Unknown tags are STRIPPED, their content kept;
 * malformed markup degrades to literal text; unclosed tags close at the end.
 */
internal fun parseHtmlSubset(text: String, linkColor: Color): ParsedHtml {
    val out = StringBuilder(text.length)
    val spans = mutableListOf<SpanRange>()
    val links = mutableListOf<HtmlLink>()
    val stack = mutableListOf<OpenTag>()

    fun close(open: OpenTag, end: Int) {
        if (end <= open.start) return
        when (open.name) {
            "b", "strong" -> spans += SpanRange(SpanStyle(fontWeight = FontWeight.Bold), open.start, end)
            "i", "em" -> spans += SpanRange(SpanStyle(fontStyle = FontStyle.Italic), open.start, end)
            "u" -> spans += SpanRange(SpanStyle(textDecoration = TextDecoration.Underline), open.start, end)
            "a" -> open.href?.takeIf { it.isNotEmpty() }?.let { links += HtmlLink(open.start, end, it) }
        }
    }

    var i = 0
    while (i < text.length) {
        when (val c = text[i]) {
            '<' -> {
                val gt = text.indexOf('>', i + 1)
                if (gt < 0) {
                    out.append(c); i++                      // stray '<' - literal
                } else {
                    val raw = text.substring(i + 1, gt).trim()
                    val isEnd = raw.startsWith('/')
                    val body = if (isEnd) raw.drop(1).trim() else raw
                    val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
                    when {
                        name == "br" -> out.append('\n')
                        isEnd -> {
                            val idx = stack.indexOfLast { it.name == name }
                            if (idx >= 0) close(stack.removeAt(idx), out.length)
                        }
                        name in STYLED_TAGS -> {
                            val href = if (name == "a") HREF_REGEX.find(body)?.groupValues?.get(1) else null
                            stack += OpenTag(name, out.length, href)
                        }
                        // unknown tag: stripped, content continues
                    }
                    i = gt + 1
                }
            }
            '&' -> {
                val semi = text.indexOf(';', i + 1)
                val decoded = if (semi in (i + 2)..(i + MAX_ENTITY_LENGTH)) {
                    decodeEntity(text.substring(i + 1, semi))
                } else null
                if (decoded != null) {
                    out.append(decoded); i = semi + 1
                } else {
                    out.append(c); i++                      // literal '&'
                }
            }
            else -> {
                out.append(c); i++
            }
        }
    }
    while (stack.isNotEmpty()) close(stack.removeAt(stack.lastIndex), out.length)

    val annotated = buildAnnotatedString {
        append(out.toString())
        spans.forEach { addStyle(it.style, it.start, it.end) }
        links.forEach {
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                it.start,
                it.end,
            )
        }
    }
    return ParsedHtml(annotated, links)
}

private fun decodeEntity(name: String): String? = when {
    name == "amp" -> "&"
    name == "lt" -> "<"
    name == "gt" -> ">"
    name == "quot" -> "\""
    name == "apos" -> "'"
    name == "nbsp" -> " "
    name.startsWith("#x") || name.startsWith("#X") ->
        name.drop(2).toIntOrNull(16)?.let(::codePointToString)
    name.startsWith("#") ->
        name.drop(1).toIntOrNull()?.let(::codePointToString)
    else -> null
}

/** Common-code code point → String (no java.lang.Character in commonMain). */
private fun codePointToString(cp: Int): String? = when (cp) {
    in 1..0xD7FF, in 0xE000..0xFFFF -> cp.toChar().toString()
    in 0x10000..0x10FFFF -> {
        val v = cp - 0x10000
        charArrayOf(((v shr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
    else -> null
}

/**
 * Maps a tap position to the CHARACTER under the finger, or null for taps in
 * empty space. getOffsetForPosition alone returns the nearest CARET (tapping
 * the right half of a glyph yields the next offset, and taps past the line end
 * snap to the last character), so the result is verified against the glyph
 * bounding boxes on both sides of the caret. Glyph bounds are visual, so this
 * stays correct under RTL.
 */
private fun TextLayoutResult.tappedCharOffset(position: Offset): Int? {
    val length = layoutInput.text.length
    if (length == 0) return null
    val caret = getOffsetForPosition(position)
    if (caret < length && getBoundingBox(caret).contains(position)) return caret
    val prev = caret - 1
    if (prev in 0 until length && getBoundingBox(prev).contains(position)) return prev
    return null
}

// ── previews ────────────────────────────────────────────────────────────────

/**
 * The whole type scale in one column. This is the ONLY place the roles can be
 * compared against each other - and a role whose size or colour has quietly
 * collapsed into its neighbour's is invisible anywhere else.
 *
 * BUTTON / BUTTON_STRONG / FIELD_LABEL are deliberately absent: they are SLOT
 * roles that inherit the enclosing component's colour, so rendering them loose
 * on a Surface would show a picture the app never draws. See AppButton's and
 * AppField's previews for those.
 */
@Composable
private fun AppTextScale() {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
        AppText("Timetable", AppTextStyle.SCREEN_TITLE)
        AppText("Breaks for Wednesday", AppTextStyle.SECTION_TITLE)
        AppText("Crete TV", AppTextStyle.ITEM_TITLE)
        AppText("Aegean Foods - 30s", AppTextStyle.BODY_STRONG)
        AppText("Contract CTV-2026-014 covers 120 spots until 31 August.", AppTextStyle.BODY)
        AppText("Spots are billed per break, not per second.", AppTextStyle.NOTE)
        AppText("Last synced 2 minutes ago", AppTextStyle.TINY)
        AppText("STATION", AppTextStyle.TABLE_HEADER)
        AppText("Radio 984", AppTextStyle.TABLE_CELL)
        AppText("Radio 984 (selected)", AppTextStyle.TABLE_CELL_STRONG)
        AppText("10:02:05  WARN  break 21:00 is at 174s of 180s", AppTextStyle.LOG_LINE)
        AppText("CTV-2026-014", AppTextStyle.MONO)
        AppText("38", AppTextStyle.STAT_VALUE)
        AppText("Spots today", AppTextStyle.STAT_LABEL)
        AppText("Break time is required", AppTextStyle.ERROR_NOTE)
        AppText("Delete the 21:00 break?", AppTextStyle.DIALOG_TITLE)
    }
}

@Preview
@Composable
private fun AppTextPreview() = AppPreview { AppTextScale() }

// Half the roles differ ONLY by colour (NOTE and TINY drop to onSurfaceVariant,
// ERROR_NOTE to error) - and colour is exactly what the dark palette changes.
@Preview
@Composable
private fun AppTextDarkPreview() = AppPreview(dark = true) { AppTextScale() }

// Overflow and alignment: a station name too long for its column MUST ellipsize
// rather than push the layout, which is only visible under a real width cap.
@Preview
@Composable
private fun AppTextOverflowPreview() = AppPreview {
    Column(
        modifier = Modifier.width(160.dp),
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
    ) {
        AppText(
            "Crete TV - main transmitter, Heraklion",
            AppTextStyle.BODY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppText(
            "Crete TV - main transmitter, Heraklion",
            AppTextStyle.BODY,
        )
        AppText(
            "Centred caption",
            AppTextStyle.NOTE,
            modifier = Modifier.width(160.dp),
            textAlign = TextAlign.Center,
        )
        AppText(
            "Superseded contract",
            AppTextStyle.NOTE,
            textDecoration = TextDecoration.LineThrough,
        )
    }
}

// The AnnotatedString overload: the case the plain-String one cannot express -
// mixed weight and a coloured span inside ONE line of running text.
@Preview
@Composable
private fun AppTextAnnotatedPreview() = AppPreview {
    val warning = buildAnnotatedString {
        append("The 21:00 break on ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Crete TV") }
        append(" is ")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)) {
            append("overbooked")
        }
        append(" by 14 seconds.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
        AppText(warning, AppTextStyle.BODY)
        AppText(warning, AppTextStyle.NOTE)
        // Same string, capped: the spans must survive the ellipsis.
        AppText(
            warning,
            AppTextStyle.BODY,
            modifier = Modifier.width(180.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Rich text (auto-detected) + the override layer. The link renders as a
// static primary-coloured span - taps resolve against glyph bounds, so this
// preview is exactly what ships.
@Preview
@Composable
private fun AppTextRichPreview() = AppPreview {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall)) {
        AppText(
            "Plain body with <b>bold</b>, <i>italic</i> and an " +
                "<a href=\"https://example.com\">inline link</a>.",
            AppTextStyle.BODY,
        )
        AppText("Fish &amp; chips &#8212; entities decode", AppTextStyle.NOTE)
        AppText(
            "Overrides ride the role",
            AppTextStyle.BODY,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
    }
}
