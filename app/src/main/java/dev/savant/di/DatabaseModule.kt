package dev.savant.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.savant.data.db.BackupDao
import dev.savant.data.db.EntryDao
import dev.savant.data.db.ImportDao
import dev.savant.data.db.NamedFieldDao
import dev.savant.data.db.SavantDatabase
import dev.savant.data.db.SessionDao
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import dev.savant.data.storage.RoomBackupStore
import dev.savant.data.storage.RoomImportStore
import javax.inject.Singleton

/**
 * The database and the stores in front of it.
 *
 * Version 1 has no migrations: nothing shipped before it. From version 2 every change gets a real
 * `Migration` — the schema is exported to `app/schemas` so a forgotten one fails the build instead
 * of failing on somebody's phone.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SavantDatabase =
        Room.databaseBuilder(context, SavantDatabase::class.java, SavantDatabase.NAME)
            .build()

    @Provides
    fun sessionDao(db: SavantDatabase): SessionDao = db.sessionDao()

    @Provides
    fun entryDao(db: SavantDatabase): EntryDao = db.entryDao()

    @Provides
    fun backupDao(db: SavantDatabase): BackupDao = db.backupDao()

    @Provides
    fun importDao(db: SavantDatabase): ImportDao = db.importDao()

    @Provides
    fun namedFieldDao(db: SavantDatabase): NamedFieldDao = db.namedFieldDao()
}

/**
 * Interface bindings.
 *
 * The UI depends on [BackupStore] and [ImportIndex], not on where the rows live, which is what let
 * step 4 replace both implementations without touching a screen.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindBackupStore(store: RoomBackupStore): BackupStore

    @Binds
    @Singleton
    abstract fun bindImportIndex(store: RoomImportStore): ImportIndex
}
