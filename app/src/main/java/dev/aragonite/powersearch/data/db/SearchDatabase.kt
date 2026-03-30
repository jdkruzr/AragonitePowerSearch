package dev.aragonite.powersearch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [IndexedShape::class, IndexedShapeFts::class], version = 1)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun indexDao(): IndexDao

    companion object {
        @Volatile
        private var INSTANCE: SearchDatabase? = null

        fun create(context: Context): SearchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchDatabase::class.java,
                    "power_search.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
