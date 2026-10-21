package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordedAdEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordedAdDao(): RecordedAdDao

    companion object {
        private const val DB_NAME = "spotad_database"

        /**
         * v2 adds capture provenance. The old implementation used destructive migrations, which
         * silently wiped every recording the moment the schema changed. Real migrations only.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recorded_ads ADD COLUMN sampleRate INTEGER NOT NULL DEFAULT 44100")
                db.execSQL("ALTER TABLE recorded_ads ADD COLUMN source TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE recorded_ads ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recorded_ads_timestamp ON recorded_ads(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recorded_ads_isFavorite ON recorded_ads(isFavorite)")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
