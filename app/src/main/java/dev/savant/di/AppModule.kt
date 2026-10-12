package dev.savant.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SaveParser
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideParserRegistry(
        jsonParser: JsonSaveParser,
        xmlParser: XmlSaveParser,
    ): ParserRegistry = ParserRegistry(
        setOf<SaveParser>(jsonParser, xmlParser),
    )
}
