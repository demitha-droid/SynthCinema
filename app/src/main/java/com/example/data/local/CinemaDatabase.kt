package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AiMovieEntity
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.WatchHistoryEntity

@Database(
    entities = [
        AiMovieEntity::class,
        WatchHistoryEntity::class,
        CreatorChannelEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CinemaDatabase : RoomDatabase() {
    abstract fun cinemaDao(): CinemaDao

    companion object {
        @Volatile
        private var INSTANCE: CinemaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `creator_channels` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `handleOrUrl` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `externalId` TEXT NOT NULL,
                        `avatarUrl` TEXT NOT NULL,
                        `defaultGenre` TEXT NOT NULL,
                        `moviesImportedCount` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): CinemaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CinemaDatabase::class.java,
                    "synth_cinema_vault.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
