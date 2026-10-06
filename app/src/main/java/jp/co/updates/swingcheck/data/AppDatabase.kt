package jp.co.updates.swingcheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * マイグレーションの方針：スキーマを変えるときは version を上げ、Migration（または AutoMigration）を書く。
 * fallbackToDestructiveMigration は使わない（動画と結びついた記録を黙って消さないため）。
 * 各版のスキーマは app/schemas/ に出力され、git で管理する。
 */
@Database(entities = [SwingEntity::class, PositionMarkEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun swingDao(): SwingDao

    companion object {
        const val NAME = "swingcheck.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** 取り込み元（source）と時刻の補正倍率（timeScale）を追加。既存のスイングはすべてアプリ内撮影。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE swing ADD COLUMN source TEXT NOT NULL DEFAULT 'CAPTURED'")
                db.execSQL("ALTER TABLE swing ADD COLUMN timeScale REAL NOT NULL DEFAULT 1.0")
            }
        }
    }
}
