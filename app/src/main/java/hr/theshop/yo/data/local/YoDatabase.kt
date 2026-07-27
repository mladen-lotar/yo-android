package hr.theshop.yo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [YoEntity::class, GroupEntity::class, GroupMemberEntity::class],
    version = 2,
)
abstract class YoDatabase : RoomDatabase() {
    abstract fun yoDao(): YoDao

    abstract fun groupDao(): GroupDao
}
