package eu.anifantakis.commercials.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Architectural fitness functions - the guard that keeps the conventions the
 * COMPILER can't enforce honest as the codebase and the team grow. Same spirit
 * as [eu.anifantakis.commercials.di.KoinGraphTest] (a hand-rolled check instead
 * of a plugin): each rule scans the Kotlin sources on disk and fails CI with a
 * precise file:line list, so "the architect noticing in review" becomes "the
 * build going red". JVM-only on purpose (uses java.io.File), so it lives in
 * shared/jvmTest, not commonTest.
 *
 * Add a rule = add a `@Test` that collects offenders and `fail(...)`s with them.
 * These cover the rules that are NOT already guaranteed by the module graph /
 * api-implementation hygiene (which the compiler handles for free).
 */
class ArchitectureTest {

    // ── source-tree helpers ────────────────────────────────────────────────

    /** Walk up from the test's working dir until the repo root (settings.gradle.kts). */
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    private fun dir(path: String): File? = File(repoRoot, path).takeIf { it.exists() }

    /** `feature/<name>/<layer>/src` for every feature that has that layer module. */
    private fun featureLayer(layer: String): List<File> =
        File(repoRoot, "feature").listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { feat -> File(feat, "$layer/src").takeIf { it.exists() } }

    private fun File.ktFiles(): List<File> =
        walkTopDown().filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }.toList()

    private fun List<File?>.roots(): List<File> = filterNotNull()

    // presentation cone (features + core:presentation) - excludes grids, which is
    // a separate leaf toolkit at core/presentation/grids/src, and reports-client.
    private val presentationRoots: List<File>
        get() = (featureLayer("presentation") + dir("core/presentation/src")).roots()

    private val dataRoots: List<File>
        get() = (featureLayer("data") + dir("core/data/src")).roots()

    // every UI-bearing module, for the icon single-door rule
    private val uiRoots: List<File>
        get() = (presentationRoots +
            listOf(dir("core/presentation/grids/src"), dir("reports-client/src"))).roots()

    private fun File.lineHits(predicate: (String) -> Boolean): List<String> =
        readLines().withIndex()
            .filter { (_, line) -> predicate(line) }
            .map { (i, line) -> "  ${relativeTo(repoRoot).path}:${i + 1}  ${line.trim()}" }

    // the shippable entry apps: they compose the app from :shared ONLY, never
    // reaching into a feature module directly (iosApp is Swift, so no Kotlin cone).
    private val entryAppRoots: List<File>
        get() = listOf(dir("androidApp/src"), dir("desktopApp/src"), dir("webApp/src")).roots()

    /** Every Gradle build script in the repo (excluding generated output). */
    private fun buildScripts(): List<File> =
        repoRoot.walkTopDown()
            .filter {
                it.isFile && it.name.endsWith(".gradle.kts") &&
                    "/build/" !in it.path && "/.gradle/" !in it.path
            }
            .toList()

    /** True for files under a test source set - excluded from production-code rules. */
    private fun File.isTestSource(): Boolean =
        listOf("/commonTest/", "/jvmTest/", "/androidHostTest/", "/iosTest/").any { it in path }

    // ── the rules ───────────────────────────────────────────────────────────

    /**
     * presentation ⊥ data (SOLID/DIP). The module graph already blocks a DIRECT
     * dep, but this catches the subtle re-introduction of a transitive `api`
     * leak (like the old reports-client one) with a readable message right here,
     * instead of a cryptic unresolved-reference somewhere downstream.
     */
    @Test
    fun `presentation must not import the data layer`() {
        val offenders = presentationRoots.flatMap { it.ktFiles() }.flatMap { f ->
            f.lineHits { it.trimStart().startsWith("import eu.anifantakis.commercials.core.data") }
        }
        if (offenders.isNotEmpty()) fail(
            "presentation must depend on domain abstractions, never :core:data " +
                "(likely a stray transitive api() dep). Offenders:\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Every icon goes through a single-door catalog (AppIcons / GridIcons /
     * ReportIcons) - no raw `Icons.*` at a call site. `(?<![A-Za-z])` keeps
     * `AppIcons.` from matching.
     */
    @Test
    fun `every icon is referenced through a single-door catalog`() {
        val catalogs = setOf("AppIcons.kt", "GridIcons.kt", "ReportIcons.kt")
        val rawIcon = Regex("(?<![A-Za-z])Icons\\.")
        val offenders = uiRoots.flatMap { it.ktFiles() }
            .filter { it.name !in catalogs }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && rawIcon.containsMatchIn(line)
                }
            }
        if (offenders.isNotEmpty()) fail(
            "raw Icons.* found - add a property to AppIcons/GridIcons/ReportIcons and " +
                "reference that instead:\n" + offenders.joinToString("\n")
        )
    }

    /**
     * The Root/private-Screen split: a `<Name>Screen.kt` that exposes a public
     * `<Name>ScreenRoot` must also declare a `private fun <Name>Screen(` (the
     * previewable, DI-free pure function).
     */
    @Test
    fun `every screen exposes a public Root and a private Screen`() {
        val offenders = presentationRoots.flatMap { it.ktFiles() }
            .filter { it.name.endsWith("Screen.kt") }
            .mapNotNull { f ->
                val base = f.name.removeSuffix(".kt")               // e.g. TimetableScreen
                val text = f.readText()
                val hasRoot = Regex("fun ${Regex.escape(base)}Root\\s*\\(").containsMatchIn(text)
                if (!hasRoot) return@mapNotNull null                 // not a screen-entry file
                val hasPrivate = Regex("private fun ${Regex.escape(base)}\\s*\\(").containsMatchIn(text)
                if (hasPrivate) null else "  ${f.relativeTo(repoRoot).path}  " +
                    "(has ${base}Root but no `private fun $base(`)"
            }
        if (offenders.isNotEmpty()) fail(
            "Screen files must pair a public <Name>ScreenRoot with a private <Name>Screen:\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * A repository is an ORGANIZER of DataSource interfaces - it must never
     * reach for the HTTP client itself (that transport concern lives in the
     * *DataSourceImpl). Enforced as: no *RepositoryImpl imports the network
     * package.
     */
    @Test
    fun `repositories never touch the HTTP client directly`() {
        val offenders = dataRoots.flatMap { it.ktFiles() }
            .filter { it.name.endsWith("RepositoryImpl.kt") }
            .flatMap { f ->
                f.lineHits { it.trimStart().startsWith("import eu.anifantakis.commercials.core.data.network") }
            }
        if (offenders.isNotEmpty()) fail(
            "RepositoryImpl must organize DataSource interfaces, never the HttpClient " +
                "(move the transport into the *DataSourceImpl):\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Build scripts declare module deps through the type-safe `projects.*`
     * accessors, never the stringly-typed `project(":core:data")`: a typo in the
     * latter surfaces late and cryptically, the accessor fails at configuration
     * with a name. (The compiler can't see build scripts, so only a scan guards this.)
     */
    @Test
    fun `build scripts declare deps via the projects accessors`() {
        val stringly = Regex("""project\(\s*(?:path\s*=\s*)?":""")
        val offenders = buildScripts().flatMap { f -> f.lineHits { stringly.containsMatchIn(it) } }
        if (offenders.isNotEmpty()) fail(
            "use the type-safe projects.<module> accessors, not project(\":...\"):\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * The entry apps (android/desktop/web) assemble the app from :shared ONLY -
     * they must never import a feature module's internals directly. That wiring
     * belongs in shared/navigation, which keeps the app modules thin and the
     * feature graph swappable.
     */
    @Test
    fun `entry apps never import feature internals`() {
        val offenders = entryAppRoots.flatMap { it.ktFiles() }.flatMap { f ->
            f.lineHits { it.trimStart().startsWith("import eu.anifantakis.commercials.feature.") }
        }
        if (offenders.isNotEmpty()) fail(
            "entry apps compose from :shared only - route feature access through " +
                "shared/navigation, don't import feature.* here:\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Screens and per-screen ViewModels depend on the narrow `<Feature>Common`
     * CONTRACT (an interface), never the concrete `<Feature>CommonViewModel` -
     * the star-topology guarantee that a screen physically cannot reach the
     * shared reducer. The ONLY sanctioned references are the flow host that
     * OWNS it (`Navigation<Feature>.kt`) and the class's own definition file.
     */
    @Test
    fun `only the flow host references the concrete CommonViewModel`() {
        val token = Regex("""\b\w+CommonViewModel\b""")
        val offenders = presentationRoots.flatMap { it.ktFiles() }
            .filter {
                !it.isTestSource() &&                             // the VM's own unit test builds it
                    !it.name.startsWith("Navigation") &&         // the flow host owns it
                    !it.name.endsWith("CommonViewModel.kt")      // the definition itself
            }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) return@lineHits false
                    token.findAll(line).any { it.value != "BaseCommonViewModel" }
                }
            }
        if (offenders.isNotEmpty()) fail(
            "screens/child ViewModels must use the <Feature>Common contract, not the " +
                "concrete <Feature>CommonViewModel (only Navigation<Feature>.kt owns it):\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * The presentation cone builds its UI from the App* facade, never raw
     * Material widgets: the facade is where platform visual tokens, the
     * interaction policy and a11y semantics are applied - a raw call site
     * silently opts out of all three. The design system itself (and the
     * showcase, which deliberately probes raw M3 limits) is the sanctioned
     * home of the raw calls. NOT banned (kept raw on purpose, too few sites
     * to earn a door): Slider, DropdownMenu(Item), HorizontalDivider,
     * Surface, Scaffold, CircularProgressIndicator (the full-size one; the
     * small busy spinner IS AppSpinner).
     *
     * The lookbehind excludes letters AND '.' so `AppButton(`, `Foo.Text(`
     * and `errorText(` never false-positive - the same trap class as the
     * icons rule's `AppIcons.`.
     */
    @Test
    fun `the presentation cone uses the App component facade`() {
        val banned = listOf(
            "Button", "OutlinedButton", "TextButton", "IconButton",
            "Text", "Icon",
            "OutlinedTextField", "TextField",
            "Card", "AlertDialog", "Dialog",
            "Checkbox", "RadioButton", "Switch",
        )
        val rx = Regex("(?<![A-Za-z.])(${banned.joinToString("|")})\\s*\\(")
        val offenders = presentationRoots.flatMap { it.ktFiles() }
            .filter { f ->
                !f.isTestSource() &&
                    "/design_system/" !in f.path &&      // the facade + showcase
                    "/scaffold/" !in f.path              // ApplicationScaffold
            }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && rx.containsMatchIn(line)
                }
            }
        if (offenders.isNotEmpty()) fail(
            "raw Material composable in the presentation cone - use the App* facade " +
                "(AppButton/AppText/AppIcon/AppTextField/AppCard/AppDialog/...):\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * Spacing names a rung of the UIConst ladder, never a dp literal.
     * Deliberately NARROW: only spacing verbs (padding/spacedBy/height/width)
     * with on-ladder values - arbitrary geometry (a 200.dp logo, a 42.dp
     * timetable column, weight()s) stays legal, which is what keeps this rule
     * shippable instead of a permanent exemption list. Domain/component
     * geometry that repeats earns a named `private val` at the owning site.
     */
    @Test
    fun `screens space themselves from the UIConst ladder`() {
        val rx = Regex("""\b(padding|spacedBy|height|width)\(\s*(?:[a-z]+\s*=\s*)?(2|4|8|12|16|24|32|48|64)\.dp\b""")
        val offenders = presentationRoots.flatMap { it.ktFiles() }
            .filter { f -> !f.isTestSource() && "/design_system/" !in f.path && "/scaffold/" !in f.path }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && rx.containsMatchIn(line)
                }
            }
        if (offenders.isNotEmpty()) fail(
            "on-ladder dp literal used for spacing - name the rung " +
                "(UIConst.paddingSmall/.paddingRegular/...):\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Platform and raw input capabilities never reach feature UI: the look
     * comes from AppTheme.visualTokens, interaction policy from
     * AppTheme.interaction, and per-gesture behaviour from the event's own
     * PointerType. (`internal` visibility already blocks cross-module use -
     * this rule turns the cryptic compiler error into a named convention,
     * and guards same-module drift inside core:presentation screens too.)
     */
    @Test
    fun `feature UI never branches on platform or raw capabilities`() {
        val rx = Regex("""\b(UiPlatform|InputCapabilities|EffectiveDensity|detectUiPlatform)\b""")
        val offenders = featureLayer("presentation").flatMap { it.ktFiles() }
            .filter { !it.isTestSource() }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && rx.containsMatchIn(line)
                }
            }
        if (offenders.isNotEmpty()) fail(
            "feature UI must not see UiPlatform/InputCapabilities - read " +
                "AppTheme.visualTokens / AppTheme.interaction, or gate gestures on the " +
                "event's PointerType:\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Exactly ONE OS-detection point in the client codebase: the jvm actual
     * of detectUiPlatform. Everything else consumes a capability derived from
     * it (DesktopPlatformCapabilities, tokens) - a second `os.name` read is a
     * second source of truth waiting to disagree.
     */
    @Test
    fun `os name is read exactly once`() {
        val sanctioned = "core/presentation/src/jvmMain/kotlin/eu/anifantakis/commercials/" +
            "core/presentation/design_system/platform/UiPlatform.jvm.kt"
        val clientRoots = (uiRoots + entryAppRoots + listOf(dir("shared/src"))).roots()
        val offenders = clientRoots.flatMap { it.ktFiles() }
            .filter { it.relativeTo(repoRoot).path != sanctioned }
            .flatMap { f -> f.lineHits { "System.getProperty(\"os.name\")" in it } }
        if (offenders.isNotEmpty()) fail(
            "os.name is read outside the sanctioned detection point " +
                "($sanctioned) - consume a derived capability instead:\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * No hardcoded Greek in the presentation cone: every operator-facing string
     * resolves through StringKey / LocalizationManager (the localization system,
     * fronted by UiText). The language ENDONYMS (`Language.kt`) and the El/En
     * providers are the sanctioned homes for literal Greek; a domain WIRE value
     * like ΡΟΗ is named once in the model (`FLOW_ROH`), never inlined in a screen.
     */
    @Test
    fun `no hardcoded Greek string literals in the presentation cone`() {
        val greek = Regex("\"[^\"]*[\\u0370-\\u03FF\\u1F00-\\u1FFF][^\"]*\"")
        val offenders = presentationRoots.flatMap { it.ktFiles() }
            .filter { f ->
                !f.isTestSource() &&
                    f.name != "Language.kt" &&                   // language endonyms
                    "/string_resources/lang/" !in f.path         // the El/En providers
            }
            .flatMap { f ->
                f.lineHits { line ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && greek.containsMatchIn(line)
                }
            }
        if (offenders.isNotEmpty()) fail(
            "hardcoded Greek in a cone file - route it through StringKey/Strings[] (or, for a " +
                "domain wire value, name a const in the model like FLOW_ROH):\n" +
                offenders.joinToString("\n")
        )
    }
}
