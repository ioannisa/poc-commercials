package eu.anifantakis.commercials.migration

import java.io.File

/**
 * server.yaml surgery for migrated stations. Text-based on purpose: a YAML
 * round-trip would destroy the file's comments, which document the operator's
 * deployment. Only the `stations:` list is ever touched.
 */

/** Same server.yaml resolution the server's config loading uses. */
fun stationsYamlFile(): File {
    val explicit = System.getProperty("server.config") ?: System.getenv("POC_SERVER")
    return File(explicit ?: "server.yaml")
}

/**
 * Appends a station entry at the end of the `stations:` block, preserving
 * every comment in the file. Refuses duplicate ids.
 */
fun appendStationToYaml(
    file: File,
    id: String,
    name: String,
    jdbcUrl: String,
    username: String,
    password: String,
) {
    require(file.isFile) { "server.yaml not found at ${file.path} (use --yaml <path> or --no-yaml)" }
    val text = file.readText()
    require(!Regex("^\\s*-\\s*id:\\s*$id\\s*$", RegexOption.MULTILINE).containsMatchIn(text)) {
        "Station id '$id' already exists in ${file.path}"
    }

    val entry = buildString {
        append("\n  - id: ").append(id)
        append("\n    name: \"").append(name).append('"')
        append("\n    jdbcUrl: \"").append(jdbcUrl).append('"')
        append("\n    username: ").append(username)
        append("\n    password: ").append(password)
        append('\n')
    }

    val lines = text.lines()
    val stationsIdx = lines.indexOfFirst { it.trimEnd() == "stations:" }
    val newText = if (stationsIdx < 0) {
        // No stations key yet - start the list at the end of the file.
        text.trimEnd('\n') + "\n\nstations:" + entry
    } else {
        // The block ends at the next top-level key or EOF.
        var end = lines.size
        for (i in stationsIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("#")) {
                end = i; break
            }
        }
        (lines.subList(0, end).joinToString("\n").trimEnd('\n') +
            entry +
            if (end < lines.size) "\n" + lines.subList(end, lines.size).joinToString("\n") else "\n")
    }
    file.writeText(newText)
}

/**
 * Removes a station's list entry, preserving everything else (comments
 * included). Matching starts at the entry's `- id:` line and ends before the
 * next `- ` list item or the end of the stations block. Returns false when
 * no such station exists in the file.
 */
fun removeStationFromYaml(file: File, id: String): Boolean {
    if (!file.isFile) return false
    val lines = file.readText().lines()
    val startIdx = lines.indexOfFirst { Regex("^\\s*-\\s*id:\\s*$id\\s*$").matches(it) }
    if (startIdx < 0) return false

    var endIdx = lines.size
    for (i in startIdx + 1 until lines.size) {
        val line = lines[i]
        val isNextItem = line.trimStart().startsWith("- ")
        val isTopLevelKey = line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("#")
        if (isNextItem || isTopLevelKey) {
            endIdx = i; break
        }
    }
    // Swallow the blank separator line before the entry, if any.
    val trimmedStart = if (startIdx > 0 && lines[startIdx - 1].isBlank()) startIdx - 1 else startIdx
    file.writeText((lines.subList(0, trimmedStart) + lines.subList(endIdx, lines.size)).joinToString("\n"))
    return true
}
