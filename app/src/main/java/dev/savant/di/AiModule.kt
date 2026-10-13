package dev.savant.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.savant.ai.AssistantEngine
import dev.savant.ai.LocalAssistant
import dev.savant.ai.SelectingAssistant
import dev.savant.ai.ToolExecutor
import dev.savant.ai.provider.HttpTransport
import dev.savant.data.ai.UrlConnectionTransport
import dev.savant.data.settings.EncryptedSecretStore
import dev.savant.data.settings.SecretStore
import javax.inject.Singleton

/**
 * The assistant bindings.
 *
 * [AssistantEngine] is bound to [SelectingAssistant], which is on device until a provider profile is
 * configured and usable. Note what is provided and what is not: a transport and a secret store, and
 * no http client library, no serialization plugin and no key anywhere in a module.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun localAssistant(executor: ToolExecutor): LocalAssistant = LocalAssistant(executor)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiBindings {

    @Binds
    @Singleton
    abstract fun assistantEngine(assistant: SelectingAssistant): AssistantEngine

    @Binds
    @Singleton
    abstract fun secretStore(store: EncryptedSecretStore): SecretStore

    @Binds
    @Singleton
    abstract fun httpTransport(transport: UrlConnectionTransport): HttpTransport
}
