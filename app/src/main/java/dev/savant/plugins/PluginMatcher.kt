package dev.savant.plugins

import dev.savant.domain.model.Confidence

/**
 * Which files a plugin claims.
 *
 * A plugin has to say what it is for: a name pattern, a magic header, or both. One that says neither
 * can never match, and savant will not guess — a plugin that claims every binary file would win
 * detection against a save it has never seen and produce a field list of nonsense.
 */
object PluginMatcher {

    fun globToRegex(pattern: String): Regex {
        val body = buildString {
            for (char in pattern.trim()) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
                        append('\\')
                        append(char)
                    }

                    else -> append(char)
                }
            }
        }
        return Regex(body, RegexOption.IGNORE_CASE)
    }

    fun matchesName(plugin: GamePlugin, fileName: String?): Boolean {
        if (fileName.isNullOrBlank() || plugin.fileMatch.isEmpty()) return false
        val name = fileName.substringAfterLast('/')
        return plugin.fileMatch.any { pattern ->
            val regex = runCatching { globToRegex(pattern) }.getOrNull() ?: return@any false
            regex.matches(name) || regex.matches(name.substringBeforeLast('.'))
        }
    }

    /** Magic bytes at the start of the payload — decoded first when the plugin says there is a codec. */
    fun matchesMagic(plugin: GamePlugin, bytes: ByteArray): Boolean {
        val magic = PluginCodec.parseHex(plugin.magic)
        if (magic.isEmpty()) return false
        if (startsWith(bytes, magic)) return true
        if (plugin.codec == "none") return false
        val decoded = runCatching { PluginCodec.codecFor(plugin).first?.decode(bytes) }.getOrNull() ?: return false
        return startsWith(decoded, magic)
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (prefix.isEmpty() || bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    fun score(plugin: GamePlugin, fileName: String?, bytes: ByteArray): Confidence {
        val hasPattern = plugin.fileMatch.isNotEmpty()
        val hasMagic = PluginCodec.parseHex(plugin.magic).isNotEmpty()
        if (!hasPattern && !hasMagic) {
            return Confidence.none("plugin ${plugin.gameName} declares no fileMatch and no magic")
        }
        val nameMatched = hasPattern && matchesName(plugin, fileName)
        val magicMatched = hasMagic && matchesMagic(plugin, bytes)
        return when {
            nameMatched && magicMatched -> Confidence(0.98f, "plugin ${plugin.gameName}: name and header match")
            magicMatched -> Confidence(0.8f, "plugin ${plugin.gameName}: header matches")
            nameMatched -> Confidence(0.86f, "plugin ${plugin.gameName}: ${plugin.fileMatch.joinToString(" ")}")
            else -> Confidence.none("plugin ${plugin.gameName} does not claim this file")
        }
    }
}
