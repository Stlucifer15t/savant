package dev.savant.plugins

import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.SaveParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A definition that is installed on this device.
 *
 * [problems] is kept, not thrown away: a document that asks for a reader this build does not have
 * stays visible in the fixes screen with the reason, so its author can see exactly what is missing
 * instead of wondering why nothing happened.
 */
data class InstalledPlugin(
    val document: PluginDocument,
    val enabled: Boolean = true,
    val importedAtMillis: Long = 0L,
    val sourceName: String? = null,
    val problems: List<PluginProblem> = emptyList(),
) {
    val slug: String get() = document.slug

    val errors: List<PluginProblem> get() = problems.filter { it.severity == PluginSeverity.ERROR }
    val notes: List<PluginProblem> get() = problems.filter { it.severity == PluginSeverity.NOTE }

    /** "on", "off", or "inert" when something in the document cannot be honoured here. */
    val state: String
        get() = when {
            errors.isNotEmpty() -> "inert"
            !enabled -> "off"
            else -> "on"
        }

    /** Which parser this definition contributes today, if any. */
    val providesParser: Boolean get() = document.parser == PluginParserKind.BINARY

    val usable: Boolean get() = errors.isEmpty() && enabled && (providesParser || document.rename.isNotEmpty())
}

/** Result of importing json: either an installed definition, or the reasons it was not. */
data class PluginInstall(
    val plugin: InstalledPlugin?,
    val problems: List<PluginProblem>,
) {
    val accepted: Boolean get() = plugin != null
    val errors: List<PluginProblem> get() = problems.filter { it.severity == PluginSeverity.ERROR }
    val notes: List<PluginProblem> get() = problems.filter { it.severity == PluginSeverity.NOTE }
}

/**
 * The definitions this device knows about.
 *
 * In memory on purpose: persistence is a thin layer over [replaceAll] (see
 * `data/plugins/PluginStore`), which keeps every rule about plugins — reading, validating,
 * matching, refusing — in one class that runs on the plain JVM and is covered by unit tests.
 *
 * A document is read, validated and only then installed. Nothing is applied by halves: if the
 * validator objects, [import] comes back with the reasons and the library is left alone.
 */
class PluginLibrary {

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())

    /** Everything installed, usable or not, in the order it was read. */
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    val active: List<InstalledPlugin> get() = _installed.value.filter { it.usable }

    fun replaceAll(plugins: List<InstalledPlugin>) {
        _installed.value = plugins
    }

    /**
     * Reads [json] and installs it if it holds up.
     *
     * Installing a definition with the same slug as one already there *replaces* it — re-importing
     * a corrected document is the normal way to fix a typo, and ending up with two definitions that
     * both claim `save*.dat` would make detection depend on import order.
     */
    fun import(json: String, sourceName: String? = null, atMillis: Long = 0L): PluginInstall {
        val read = PluginReader.read(json)
        val problems = read.problems + PluginValidator.validate(read.document)
        if (problems.any { it.severity == PluginSeverity.ERROR }) {
            return PluginInstall(plugin = null, problems = problems)
        }
        val entry = InstalledPlugin(
            document = read.document,
            enabled = true,
            importedAtMillis = atMillis,
            sourceName = sourceName,
            problems = problems,
        )
        val existing = _installed.value
        val replaced = existing.firstOrNull { it.slug == entry.slug }
        _installed.value = if (replaced == null) {
            existing + entry
        } else {
            existing.map { if (it.slug == entry.slug) entry else it }
        }
        return PluginInstall(plugin = entry, problems = problems)
    }

    /** Installs an already validated definition (what the store loads from disk). */
    fun install(plugin: InstalledPlugin): InstalledPlugin {
        val withProblems = plugin.copy(problems = PluginValidator.validate(plugin.document))
        val existing = _installed.value
        _installed.value = if (existing.any { it.slug == withProblems.slug }) {
            existing.map { if (it.slug == withProblems.slug) withProblems else it }
        } else {
            existing + withProblems
        }
        return withProblems
    }

    fun remove(slug: String): Boolean {
        val existing = _installed.value
        val remaining = existing.filterNot { it.slug == slug }
        if (remaining.size == existing.size) return false
        _installed.value = remaining
        return true
    }

    fun setEnabled(slug: String, enabled: Boolean): Boolean {
        val existing = _installed.value
        if (existing.none { it.slug == slug }) return false
        _installed.value = existing.map { if (it.slug == slug) it.copy(enabled = enabled) else it }
        return true
    }

    fun bySlug(slug: String): InstalledPlugin? = _installed.value.firstOrNull { it.slug == slug }

    /**
     * Parsers the registry should ask about a file: one per usable binary definition.
     *
     * A definition that only renames contributes no parser — it decorates the tree a real parser
     * built, which is why it can never claim a file it cannot read.
     */
    fun parsers(): List<SaveParser> = active.mapNotNull { entry ->
        if (!entry.providesParser) null else PluginRuntime.parserFor(entry.document)
    }

    /** The format a definition would open a file as, for the formats screen. */
    fun formatOf(entry: InstalledPlugin): SaveFormat = PluginRuntime.formatOf(entry.document)

    /**
     * Display names from every usable definition whose globs match this file name.
     *
     * Only definitions with at least one glob can be matched, so a definition that is not about
     * this file cannot rename a field in it.
     */
    fun renamesFor(fileName: String?): Map<String, String> {
        if (fileName == null) return emptyMap()
        return active
            .filter { entry -> PluginMatch.firstMatch(entry.document.fileMatch, fileName) != null }
            .flatMap { entry -> entry.document.rename.entries }
            .associate { it.key to it.value }
    }

    /** The plugin that claims this file name, best first, for the "read by plugin x" line. */
    fun definitionsFor(fileName: String?): List<InstalledPlugin> {
        if (fileName == null) return emptyList()
        return active.filter { entry -> PluginMatch.firstMatch(entry.document.fileMatch, fileName) != null }
    }

    companion object {
        /** A library that never has anything — what a build without imports looks like. */
        val EMPTY = PluginLibrary()
    }
}
