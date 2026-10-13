package dev.savant.plugins

/**
 * The glob a plugin uses to recognise a file name.
 *
 * Deliberately small and total: `*` matches any run of characters, `?` matches exactly one, and
 * everything else is literal. Case insensitive, because save files travel between operating
 * systems. There is no negation, no brace expansion and no regex — a pattern that looks like a
 * regex is matched literally, so a plugin can never turn out to be a matcher for something its
 * author did not write down.
 */
object PluginMatch {

    fun matches(glob: String, fileName: String?): Boolean {
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        if (name.isNullOrEmpty() || glob.isBlank()) return false
        return matches(glob, name, 0, 0)
    }

    /** First glob that matches, for the chat line that says why a plugin claimed a file. */
    fun firstMatch(globs: List<String>, fileName: String?): String? =
        globs.firstOrNull { matches(it, fileName) }

    private fun matches(glob: String, name: String, from: Int, at: Int): Boolean {
        var g = from
        var n = at
        while (g < glob.length) {
            when (val c = glob[g]) {
                '*' -> {
                    // Try the shortest expansion first so `save*.dat` prefers the first `.dat`.
                    for (skip in n..name.length) {
                        if (matches(glob, name, g + 1, skip)) return true
                    }
                    return false
                }

                '?' -> if (n >= name.length) return false

                else -> {
                    if (n >= name.length) return false
                    if (!c.equals(name[n], ignoreCase = true)) return false
                }
            }
            g++
            n++
        }
        return n == name.length
    }
}
