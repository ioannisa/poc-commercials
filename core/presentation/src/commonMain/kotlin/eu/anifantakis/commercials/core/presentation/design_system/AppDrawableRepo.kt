package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import eu.anifantakis.commercials.core.presentation.design_system.components.LocalAuthImageHttpClient
import eu.anifantakis.commercials.core.presentation.design_system.components.LocalServerBaseUrl
import eu.anifantakis.commercials.core.presentation.design_system.components.RemoteImage
import commercials_manager.core.presentation.generated.resources.Res
import commercials_manager.core.presentation.generated.resources.login_background_00
import commercials_manager.core.presentation.generated.resources.login_background_02
import commercials_manager.core.presentation.generated.resources.login_background_06
import commercials_manager.core.presentation.generated.resources.login_background_08
import commercials_manager.core.presentation.generated.resources.login_background_10
import commercials_manager.core.presentation.generated.resources.login_background_12
import commercials_manager.core.presentation.generated.resources.login_background_14
import commercials_manager.core.presentation.generated.resources.login_background_16
import commercials_manager.core.presentation.generated.resources.login_background_18
import commercials_manager.core.presentation.generated.resources.login_background_20
import commercials_manager.core.presentation.generated.resources.login_background_22
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.DrawableResource
import kotlin.time.Clock

/**
 * The single door for every icon in the app (kmp-developer core-presentation
 * convention): call sites name a semantic property here instead of reaching
 * for a raw `Icons.Default.*` / `painterResource(...)` inline - exactly like
 * type goes through `AppText` and colours through the theme. One place to see,
 * rename, re-theme, or swap every asset; zero scattered icon literals across
 * features.
 *
 * Most entries are Material [ImageVector]s exposed as plain `@Composable` vals;
 * bundled image resources (under composeResources/drawable) live here too as
 * [DrawableResource]s, drawn via `AppImage`. The door is asset-flavour-agnostic
 * on purpose - one place to see, rename or swap EVERY asset, icon or image.
 * Icon names mirror the Material leaf name
 * 1:1 (predictable, collision-free); the semantic meaning of a use lives in
 * each call site's localized `contentDescription`.
 *
 * Entries are MINI-REPOSITORIES: an entry may embed selection logic and decide
 * WHICH asset answers a semantic name (the time-of-day [loginBackground]).
 * Two wings, one door, split by what an entry can PROMISE:
 *
 * - LOCAL wing - sync + total + dependency-free: returns [ImageVector] /
 *   [DrawableResource], drawn via `AppIcon` / `AppImage`. Can never load,
 *   fail, or be absent - which is exactly what those renderers assume.
 * - REMOTE wing - anything fetched over the network (auth or not), which can
 *   therefore be loading / missing / failed: a `@Composable fun` returning
 *   [RemoteImage] (absolute url + transport, resolved from
 *   [LocalServerBaseUrl] / [LocalAuthImageHttpClient]), rendered via
 *   `AppAsyncImage(source = …)` whose placeholder slots own those states.
 *   NEVER a url string pretending to be a drawable - the type says which
 *   renderer (and which failure model) an entry belongs to.
 */
object AppDrawableRepo {

    // ── Material AutoMirrored (flip in RTL) ────────────────────────────────
    val arrowBack: ImageVector @Composable get() = Icons.AutoMirrored.Filled.ArrowBack
    val arrowForward: ImageVector @Composable get() = Icons.AutoMirrored.Filled.ArrowForward
    val chat: ImageVector @Composable get() = Icons.AutoMirrored.Filled.Send
    val keyboardArrowLeft: ImageVector @Composable get() = Icons.AutoMirrored.Filled.KeyboardArrowLeft
    val keyboardArrowRight: ImageVector @Composable get() = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val logout: ImageVector @Composable get() = Icons.AutoMirrored.Filled.Logout
    val openInNew: ImageVector @Composable get() = Icons.AutoMirrored.Filled.OpenInNew
    /** "Dock back into the app" - the AI window's attach action. */
    val closeFullscreen: ImageVector @Composable get() = Icons.Default.CloseFullscreen

    // ── Material Filled ────────────────────────────────────────────────────
    val add: ImageVector @Composable get() = Icons.Default.Add
    val arrowDownward: ImageVector @Composable get() = Icons.Default.ArrowDownward
    val arrowDropDown: ImageVector @Composable get() = Icons.Default.ArrowDropDown
    val arrowUpward: ImageVector @Composable get() = Icons.Default.ArrowUpward
    val check: ImageVector @Composable get() = Icons.Default.Check
    val clear: ImageVector @Composable get() = Icons.Default.Clear
    val close: ImageVector @Composable get() = Icons.Default.Close
    val contentCopy: ImageVector @Composable get() = Icons.Default.ContentCopy
    val contentCut: ImageVector @Composable get() = Icons.Default.ContentCut
    val contentPaste: ImageVector @Composable get() = Icons.Default.ContentPaste
    val delete: ImageVector @Composable get() = Icons.Default.Delete
    val description: ImageVector @Composable get() = Icons.Default.Description
    val dns: ImageVector @Composable get() = Icons.Default.Dns
    val edit: ImageVector @Composable get() = Icons.Default.Edit
    val email: ImageVector @Composable get() = Icons.Default.Email
    val folder: ImageVector @Composable get() = Icons.Default.Folder
    val history: ImageVector @Composable get() = Icons.Default.History
    val info: ImageVector @Composable get() = Icons.Default.Info
    val key: ImageVector @Composable get() = Icons.Default.Key
    val keyboardArrowDown: ImageVector @Composable get() = Icons.Default.KeyboardArrowDown
    val keyboardArrowUp: ImageVector @Composable get() = Icons.Default.KeyboardArrowUp
    val lock: ImageVector @Composable get() = Icons.Default.Lock
    /** Floating-window title bar: park into the taskbar strip. */
    val minimize: ImageVector @Composable get() = Icons.Default.Minimize
    val lockReset: ImageVector @Composable get() = Icons.Default.LockReset
    val manageAccounts: ImageVector @Composable get() = Icons.Default.ManageAccounts
    val moreVert: ImageVector @Composable get() = Icons.Default.MoreVert
    val numbers: ImageVector @Composable get() = Icons.Default.Numbers
    val person: ImageVector @Composable get() = Icons.Default.Person
    val playArrow: ImageVector @Composable get() = Icons.Default.PlayArrow
    val print: ImageVector @Composable get() = Icons.Default.Print
    val refresh: ImageVector @Composable get() = Icons.Default.Refresh
    val save: ImageVector @Composable get() = Icons.Default.Save
    val settings: ImageVector @Composable get() = Icons.Default.Settings
    val systemUpdate: ImageVector @Composable get() = Icons.Default.SystemUpdateAlt
    /** The AI "sparkles" mark - the chat assistant's launcher icon. */
    val autoAwesome: ImageVector @Composable get() = Icons.Default.AutoAwesome
    val storage: ImageVector @Composable get() = Icons.Default.Storage
    val timer: ImageVector @Composable get() = Icons.Default.Timer
    val visibility: ImageVector @Composable get() = Icons.Default.Visibility
    val visibilityOff: ImageVector @Composable get() = Icons.Default.VisibilityOff

    // ── Bundled image resources (drawn via AppImage, never AppIcon) ─────────
    /** Login screen background image (composeResources/drawable/login_background.png). */
    val loginBackground: DrawableResource get() {
        val time00 = LocalTime(0, 0)
        val time02 = LocalTime(2, 0)
        val time04 = LocalTime(4, 0)
        val time06 = LocalTime(6, 0)
        val time08 = LocalTime(8, 0)
        val time10 = LocalTime(10, 0)
        val time12 = LocalTime(12, 0)
        val time14 = LocalTime(14, 0)
        val time16 = LocalTime(16, 0)
        val time18 = LocalTime(18, 0)
        val time20 = LocalTime(20, 0)
        val time22 = LocalTime(22, 0)

        val now: LocalTime = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .time

        return when (now) {
            in time00..<time02 -> Res.drawable.login_background_00
            in time02..<time04 -> Res.drawable.login_background_02
            in time04..<time06 -> Res.drawable.login_background_06
            in time06..<time08 -> Res.drawable.login_background_08
            in time08..<time10 -> Res.drawable.login_background_10
            in time10..<time12 -> Res.drawable.login_background_12
            in time12..<time14 -> Res.drawable.login_background_14
            in time14..<time16 -> Res.drawable.login_background_16
            in time16..<time18 -> Res.drawable.login_background_18
            in time18..<time20 -> Res.drawable.login_background_20
            in time20..<time22 -> Res.drawable.login_background_22
            else -> Res.drawable.login_background_00
        }
    }

    // ── Remote entries (rendered via AppAsyncImage, never AppIcon/AppImage) ─

    /**
     * The logo the server keeps for [stationId] (`/api/reports/logo`, an
     * auth-gated endpoint: bearer + station grant) - or null until the app
     * root has provided [LocalServerBaseUrl]. The id is embedded IN the url
     * on purpose: Coil keys its cache by the url string, so the embedded id
     * is what gives every station its own cache entry (the client's
     * auto-stamped `?station=` lands after the key is computed - too late).
     * The authenticated client rides along from [LocalAuthImageHttpClient];
     * an `AppAsyncImage` call site may still override it.
     */
    @Composable
    fun stationLogo(stationId: String): RemoteImage? =
        LocalServerBaseUrl.current?.let { base ->
            RemoteImage(
                url = base.trimEnd('/') + "/api/reports/logo?station=" + stationId,
                httpClient = LocalAuthImageHttpClient.current,
            )
        }
}
