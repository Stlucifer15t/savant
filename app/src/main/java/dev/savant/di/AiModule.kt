package dev.savant.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.savant.ai.AssistantEngine
import dev.savant.ai.AssistantRouter
import dev.savant.ai.NetworkLog
import dev.savant.ai.ToolExecutor
import dev.savant.ai.net.HttpTransport
import dev.savant.ai.net.UrlConnectionTransport
import dev.savant.data.settings.KeystoreSecretStore
import dev.savant.data.settings.ProviderProfileRepository
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import javax.inject.Singleton

/**
 * The provider half of step 6.
 *
 * Nothing here decides *when* a request happens — that is the chat's job — it only decides what a
 * request would be made with: an encrypted place for keys, one transport, one request log, and the
 * router that picks between the provider and the on-device parser.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun httpTransport(): HttpTransport = UrlConnectionTransport()

    /** Aes-gcm in `files/secrets.vault`, or memory only if this device has no usable keystore. */
    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    /**
     * Off unless the user turned logging on, and in memory only: a request log that survives a
     * restart is a record of what someone was doing with their save files.
     */
    @Provides
    @Singleton
    fun networkLog(settings: SettingsRepository): NetworkLog =
        NetworkLog(enabled = { settings.current.value.logNetworkRequests })

    @Provides
    @Singleton
    fun assistantEngine(
        executor: ToolExecutor,
        transport: HttpTransport,
        profiles: ProviderProfileRepository,
        settings: SettingsRepository,
        log: NetworkLog,
    ): AssistantEngine = AssistantRouter(
        executor = executor,
        transport = transport,
        profiles = profiles.profiles,
        keyOf = { profile -> profiles.keyOf(profile) },
        settings = { settings.current.value },
        log = log,
    )
}
