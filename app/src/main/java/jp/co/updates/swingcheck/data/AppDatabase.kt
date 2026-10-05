package jp.co.updates.swingcheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * マイグレーションの方針：スキーマを変えるときは version を上げ、Migration（または AutoMigration）を書く。
 * fallbackToDestructiveMigration は使わない（動画と結びついた記録を黙って消さないため）。
 * 各版のスキーマは app/schemas/ に出力され、git で管理する。
 */
@Database(entities = [SwingEntity::class, PositionMarkEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun swingDao(): SwingDao

    companion object {
        const val NAME = "swingcheck.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
