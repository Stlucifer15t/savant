package dev.savant.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.savant.ai.AssistantEngine
import dev.savant.ai.LocalAssistant
import dev.savant.ai.ToolExecutor
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.FileBackupStore
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}

/**
 * The deterministic core.
 *
 * Every parser savant ships is registered here; step 7 adds plugin-defined parsers to the same
 * list. Note what is *not* provided: no LLM writes bytes. Parsers, codecs and the export verifier
 * are plain Kotlin objects, and the assistant can only propose [dev.savant.domain.model.FieldEdit]s
 * that the editor validates and the verifier re-reads.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun parserRegistry(): ParserRegistry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser()))

    @Provides
    @Singleton
    fun exportVerifier(registry: ParserRegistry): ExportVerifier = ExportVerifier(registry)

    @Provides
    @Singleton
    fun saveEditor(registry: ParserRegistry, verifier: ExportVerifier): SaveEditor = SaveEditor(registry, verifier)

    @Provides
    @Singleton
    fun toolExecutor(editor: SaveEditor): ToolExecutor = ToolExecutor(editor)

    /**
     * The on-device assistant. Step 6 replaces this binding with a provider-backed engine that
     * talks to an LLM and routes tool calls through the very same [ToolExecutor].
     */
    @Provides
    @Singleton
    fun assistantEngine(executor: ToolExecutor): AssistantEngine = LocalAssistant(executor)
}

/**
 * Interface bindings.
 *
 * The UI depends on [BackupStore], not on the file layout behind it: step 4 swaps in a Room backed
 * implementation by changing this one line, and no screen has to know.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindBackupStore(store: FileBackupStore): BackupStore
}
