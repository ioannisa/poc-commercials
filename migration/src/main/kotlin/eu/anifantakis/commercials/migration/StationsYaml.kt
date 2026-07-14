package eu.anifantakis.commercials.migration

import com.charleskorn.kaml.Yaml
import eu.anifantakis.commercials.server.stations.HostingConfig
import java.io.File

/**
 * server.yaml surgery for migrated groups and stations. Text-based on purpose:
 * a YAML round-trip would destroy the file's comments, which document the
 * operator's deployment. Only the `groups:` list is ever touched.
 *
 * The list is two levels deep now - a group owns the database, its stations sit
 * inside it - so the edits are indentation-aware rather than line-regex:
 *
 *     groups:
 *       - id: crete-group        <- group item, indent 2
 *         jdbcUrl: ...
 *         stations:              <- indent 4
 *           - id: crete-tv       <- station item, indent 6
 *             name: "Crete TV"
 *
 * Every writer re-parses the result with kaml before touching the disk (see
 * [writeVerified]): a mangled server.yaml would take the server down at its
 * next boot, and a hand-indented file is exactly the kind of input that makes
 * text surgery go quietly wrong.
 */

private const val GROUP_INDENT = 2
private const val STATION_INDENT = 6

/** Same server.yaml resolution the server's config loading uses. */
fun stationsYamlFile(): File {
    val explicit = System.getProperty("server.config") ?: System.getenv("COMMERCIALS_SERVER")
    return File(explicit ?: "server.yaml")
}

/** The station block as it appears under a group's `stations:` key. */
private fun stationEntry(id: String, name: String, logo: String?): String = buildString {
    append("      - id: ").append(id).append('\n')
    append("        name: \"").append(name).append("\"\n")
    if (!logo.isNullOrBlank()) append("        logo: \"").append(logo).append("\"\n")
}

/**
 * Appends a whole GROUP (database + its stations) at the end of the `groups:`
 * block, preserving every comment. Refuses a duplicate group id, or a station
 * id already used anywhere in the file.
 */
fun appendGroupToYaml(
    file: File,
    id: String,
    name: String,
    jdbcUrl: String,
    username: String,
    password: String,
    stations: List<Triple<String, String, String?>>,
) {
    require(file.isFile) { "server.yaml not found at ${file.path} (use --yaml <path> or --no-yaml)" }
    val text = file.readText()
    val lines = text.lines()
    require(findItem(lines, "groups", GROUP_INDENT, id) == null) {
        "Group id '$id' already exists in ${file.path}"
    }
    for ((stationId, _, _) in stations) {
        require(!hasStationAnywhere(lines, stationId)) {
            "Station id '$stationId' already exists in ${file.path} (station ids are unique across ALL groups)"
        }
    }

    val entry = buildString {
        append("\n  - id: ").append(id).append('\n')
        append("    name: \"").append(name).append("\"\n")
        append("    jdbcUrl: \"").append(jdbcUrl).append("\"\n")
        append("    username: ").append(username).append('\n')
        append("    password: ").append(password).append('\n')
        append("    stations:\n")
        stations.forEach { (sid, sname, logo) -> append(stationEntry(sid, sname, logo)) }
    }

    val groupsIdx = lines.indexOfFirst { it.trimEnd() == "groups:" }
    val newText = if (groupsIdx < 0) {
        // No groups key yet - start the list at the end of the file.
        text.trimEnd('\n') + "\n\ngroups:" + entry
    } else {
        val end = blockEnd(lines, groupsIdx + 1, minIndent = 1)
        joinAround(lines, end, entry)
    }
    writeVerified(file, newText) { cfg ->
        require(cfg.groups.any { it.id == id }) { "group '$id' missing after the edit" }
    }
}

/**
 * Appends one station INSIDE an existing group's `stations:` list. Creates the
 * `stations:` key if the group has none yet.
 */
fun appendStationToGroup(
    file: File,
    groupId: String,
    id: String,
    name: String,
    logo: String? = null,
) {
    require(file.isFile) { "server.yaml not found at ${file.path} (use --yaml <path> or --no-yaml)" }
    val lines = file.readText().lines()
    require(!hasStationAnywhere(lines, id)) {
        "Station id '$id' already exists in ${file.path} (station ids are unique across ALL groups)"
    }
    val groupStart = requireNotNull(findItem(lines, "groups", GROUP_INDENT, groupId)) {
        "Group '$groupId' not found in ${file.path}"
    }
    val groupEnd = blockEnd(lines, groupStart + 1, minIndent = GROUP_INDENT + 1)

    // The group's own `stations:` key, if it has one.
    val stationsIdx = (groupStart + 1 until groupEnd).firstOrNull { lines[it].trimEnd() == "    stations:" }
    val entry = stationEntry(id, name, logo)
    val newText = if (stationsIdx == null) {
        // No stations yet: open the list at the end of the group block.
        joinAround(lines, groupEnd, "    stations:\n" + entry.trimEnd('\n') + "\n")
    } else {
        val listEnd = blockEnd(lines, stationsIdx + 1, minIndent = STATION_INDENT)
        joinAround(lines, listEnd, entry.trimEnd('\n') + "\n")
    }
    writeVerified(file, newText) { cfg ->
        require(cfg.groups.firstOrNull { it.id == groupId }?.stations?.any { it.id == id } == true) {
            "station '$id' missing from group '$groupId' after the edit"
        }
    }
}

/**
 * Removes a station's entry from whichever group holds it, preserving
 * everything else (comments included). Returns false when no such station
 * exists in the file.
 */
fun removeStationFromYaml(file: File, id: String): Boolean {
    if (!file.isFile) return false
    val lines = file.readText().lines()
    val startIdx = findStationAnywhere(lines, id) ?: return false
    val endIdx = blockEnd(lines, startIdx + 1, minIndent = STATION_INDENT + 1)

    // Removing the group's LAST station would leave a dangling `stations:` key
    // with nothing under it - which YAML reads as null, not as an empty list, so
    // the server would refuse to boot. Take the key with it.
    val onlyStation = lines.getOrNull(startIdx - 1)?.trimEnd() == "    stations:" &&
        lines.getOrNull(endIdx)?.let { !it.startsWith(" ".repeat(STATION_INDENT) + "- ") } ?: true
    val cutFrom = if (onlyStation) startIdx - 1 else startIdx

    val newText = (lines.subList(0, cutFrom) + lines.subList(endIdx, lines.size)).joinToString("\n")
    writeVerified(file, newText) { cfg ->
        require(cfg.groups.none { g -> g.stations.any { it.id == id } }) {
            "station '$id' still present after the removal"
        }
    }
    return true
}

/** Removes a whole group entry (and therefore all its stations). */
fun removeGroupFromYaml(file: File, groupId: String): Boolean {
    if (!file.isFile) return false
    val lines = file.readText().lines()
    val startIdx = findItem(lines, "groups", GROUP_INDENT, groupId) ?: return false
    val endIdx = blockEnd(lines, startIdx + 1, minIndent = GROUP_INDENT + 1)
    // Swallow the blank separator line before the entry, if any.
    val trimmedStart = if (startIdx > 0 && lines[startIdx - 1].isBlank()) startIdx - 1 else startIdx
    val newText = (lines.subList(0, trimmedStart) + lines.subList(endIdx, lines.size)).joinToString("\n")
    writeVerified(file, newText) { cfg ->
        require(cfg.groups.none { it.id == groupId }) { "group '$groupId' still present after the removal" }
    }
    return true
}

// ───────────────────────────────────────────────────────────── internals ──

/**
 * Parses [newText] before writing it, and only writes if [check] passes too.
 *
 * The check is the point: text surgery on a file a human also edits can produce
 * something that still parses but lost the entry we just added. Failing here
 * costs an error message; failing at the next boot costs the server.
 */
private fun writeVerified(file: File, newText: String, check: (HostingConfig) -> Unit) {
    val parsed = try {
        Yaml.default.decodeFromString(HostingConfig.serializer(), newText)
    } catch (e: Exception) {
        throw IllegalStateException(
            "Editing ${file.path} would have produced YAML the server cannot read (${e.message}). " +
                "The file was NOT modified - check its indentation and edit it by hand.",
            e
        )
    }
    check(parsed)
    file.writeText(newText)
}

/** The index of the `- id: <id>` line of a list item at [indent], under [key]. */
private fun findItem(lines: List<String>, key: String, indent: Int, id: String): Int? {
    val keyIdx = lines.indexOfFirst { it.trimEnd() == "$key:" }
    if (keyIdx < 0) return null
    val end = blockEnd(lines, keyIdx + 1, minIndent = 1)
    val pattern = Regex("^ {$indent}-\\s*id:\\s*[\"']?$id[\"']?\\s*$")
    return (keyIdx + 1 until end).firstOrNull { pattern.matches(lines[it]) }
}

/** A station's `- id:` line index, in whichever group it lives. */
private fun findStationAnywhere(lines: List<String>, id: String): Int? {
    // Indent-anchored: a GROUP whose id happens to equal a station id must not
    // match here (the old scanner would have eaten it).
    val pattern = Regex("^ {$STATION_INDENT}-\\s*id:\\s*[\"']?$id[\"']?\\s*$")
    return lines.indices.firstOrNull { pattern.matches(lines[it]) }
}

private fun hasStationAnywhere(lines: List<String>, id: String): Boolean =
    findStationAnywhere(lines, id) != null

/**
 * The first index at/after [from] that leaves the block: a line whose
 * indentation is below [minIndent] (blank lines and comments don't end it).
 */
private fun blockEnd(lines: List<String>, from: Int, minIndent: Int): Int {
    var end = lines.size
    for (i in from until lines.size) {
        val line = lines[i]
        if (line.isBlank()) continue
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < minIndent) { end = i; break }
    }
    // Don't drag trailing blank lines into the block.
    var last = end
    while (last > from && lines[last - 1].isBlank()) last--
    return last
}

/** Splices [entry] into [lines] at [at], keeping the tail. */
private fun joinAround(lines: List<String>, at: Int, entry: String): String {
    val head = lines.subList(0, at).joinToString("\n").trimEnd('\n')
    val tail = lines.subList(at, lines.size).joinToString("\n")
    return head + "\n" + entry.trimEnd('\n') + "\n" + (if (tail.isBlank()) "" else tail)
}
