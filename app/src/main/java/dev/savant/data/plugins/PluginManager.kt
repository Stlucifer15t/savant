package dev.savant.data.plugins

import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SaveParser
import dev.savant.parsers.ini.IniSaveParser
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import dev.savant.plugins.BinaryPluginParser
import dev.savant.plugins.GamePlugin
import dev.savant.plugins.MappedParser
import dev.savant.plugins.PluginParserKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the parser registry in step with the installed plugins.
 *
 * One collector, running for the life of the process: the store changes, and every plugin gets a
 * parser registered beside the ones savant ships. A plugin never replaces a built in parser, and
 * re-importing an edited plugin replaces its own previous parser rather than adding a second copy.
 *
 * A parser that fails to construct is skipped rather than fatal: one bad plugin must not take the
 * file picker down with it.
 */
@Singleton
class PluginManager @Inject constructor(
    private val store: PluginStore,
    private val registry: ParserRegistry,
    private val jsonParser: JsonSaveParser,
    private val xmlParser: XmlSaveParser,
    private val iniParser: IniSaveParser,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _applied = MutableStateFlow<List<GamePlugin>>(emptyList())

    /** The plugins that are actually registered, which is what the formats screen lists. */
    val applied: StateFlow<List<GamePlugin>> = _applied.asStateFlow()

    init {
        scope.launch { store.plugins.collect { plugins -> apply(plugins) } }
    }

    /**
     * Registers the installed plugins now, without waiting for the store's flow to emit.
     *
     * Called once from [dev.savant.SavantApplication]: a file can be shared in before any screen
     * exists, so detection has to see the plugins on the very first parse. Idempotent — the collector
     * will do the same work again when the store emits, and the registry ends up in the same state.
     */
    fun start() {
        scope.launch { apply(store.current()) }
    }

    private fun apply(plugins: List<GamePlugin>) {
        registry.clearPlugins()
        plugins.forEach { plugin -> runCatching { registry.register(parserFor(plugin)) } }
        _applied.value = plugins
    }

    /** The parser a plugin describes: binary offsets get their own, text formats get a decorator. */
    fun parserFor(plugin: GamePlugin): SaveParser = when (plugin.parser) {
        PluginParserKind.BINARY -> BinaryPluginParser(plugin)
        PluginParserKind.JSON -> MappedParser(plugin, baseFor(SaveFormat.JSON) ?: jsonParser)
        PluginParserKind.XML -> MappedParser(plugin, baseFor(SaveFormat.XML) ?: xmlParser)
        PluginParserKind.INI -> MappedParser(plugin, baseFor(SaveFormat.INI) ?: iniParser)
    }

    private fun baseFor(format: SaveFormat): SaveParser? =
        registry.installed.firstOrNull { it.format == format && it.pluginId == null }

    /** Registers a plugin immediately, without waiting for the store round trip. */
    fun applyNow(plugin: GamePlugin) {
        runCatching { registry.register(parserFor(plugin)) }
    }

    fun removeNow(pluginId: String) {
        registry.unregister(pluginId)
    }
}
