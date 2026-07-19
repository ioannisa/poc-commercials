package eu.anifantakis.commercials.update

/**
 * Dotted-numeric version comparison: `"1.10.0" > "1.9.3"` (numeric per
 * segment, NOT lexicographic - the string sort would get that pair wrong).
 * Missing segments count as 0 ("1.2" == "1.2.0"); non-numeric segments count
 * as 0 too, so a malformed advertisement degrades to "no update" instead of
 * throwing in the middle of startup.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}
