package com.sjpickard.pcloudmusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ArtistEntity::class, AlbumEntity::class, TrackEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    companion object {
        @Volatile private var instance: MusicDatabase? = null

        fun get(context: Context): MusicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "pcloudmusic.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
