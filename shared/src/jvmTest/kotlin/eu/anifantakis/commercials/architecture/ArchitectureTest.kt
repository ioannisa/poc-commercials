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
}
