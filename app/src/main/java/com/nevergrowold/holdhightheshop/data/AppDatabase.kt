package com.nevergrowold.holdhightheshop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "local_accounts")
data class LocalAccountEntity(
    @PrimaryKey val uid: String,
    val email: String,
    val name: String,
    val program: String,
    val year: String,
    val bio: String = "",
    val avatarRef: String? = null,
    val passwordHash: String,
    val emailVerified: Boolean = false,
    val suspended: Boolean = false,
    val suspendedReason: String = "",
    val warningCount: Int = 0,
    val showProgram: Boolean = true,
    val showActiveStatus: Boolean = true,
    val defaultMeetup: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "listing_drafts")
data class DraftEntity(
    @PrimaryKey val ownerUid: String,
    val title: String,
    val targetProgramsCsv: String,
    val condition: Int,
    val listingType: Int,
    val price: String,
    val wants: String,
    val maxDays: Int,
    val description: String,
    val meetup: Int,
    val meetupNote: String,
    val availabilityJson: String,
    val photoUrisJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val alarmKey: String,
    val requestCode: Int,
    val action: String,
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
    val targetType: String,
    val targetId: String,
    val recipientUid: String,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val recipientUid: String,
    val type: String,
    val title: String,
    val body: String,
    val targetType: String,
    val targetId: String,
    val extraArg: String? = null,
    val read: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "state_snapshots")
data class StateSnapshotEntity(
    @PrimaryKey val key: String,
    val jsonPayload: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface LocalAccountDao {
    @Query("SELECT * FROM local_accounts WHERE lower(email) = lower(:email) LIMIT 1")
    suspend fun findByEmail(email: String): LocalAccountEntity?

    @Query("SELECT * FROM local_accounts WHERE uid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): LocalAccountEntity?

    @Query("SELECT * FROM local_accounts ORDER BY createdAt DESC")
    suspend fun all(): List<LocalAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: LocalAccountEntity)

    @Query("UPDATE local_accounts SET emailVerified = :verified WHERE uid = :uid")
    suspend fun setVerified(uid: String, verified: Boolean)

    @Query("UPDATE local_accounts SET suspended = :suspended, suspendedReason = :reason WHERE uid = :uid")
    suspend fun setSuspended(uid: String, suspended: Boolean, reason: String)

    @Query("DELETE FROM local_accounts WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM listing_drafts WHERE ownerUid = :ownerUid LIMIT 1")
    suspend fun getDraft(ownerUid: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: DraftEntity)

    @Query("DELETE FROM listing_drafts WHERE ownerUid = :ownerUid")
    suspend fun clearDraft(ownerUid: String)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM scheduled_alarms WHERE recipientUid = :uid")
    suspend fun forUser(uid: String): List<ScheduledAlarmEntity>

    @Query("SELECT * FROM scheduled_alarms")
    suspend fun all(): List<ScheduledAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarms WHERE alarmKey = :alarmKey")
    suspend fun delete(alarmKey: String)

    @Query("DELETE FROM scheduled_alarms WHERE recipientUid = :uid")
    suspend fun deleteForUser(uid: String)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE recipientUid = :uid OR recipientUid = 'ALL' ORDER BY createdAt DESC LIMIT 100")
    suspend fun forUser(uid: String): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Query("UPDATE notifications SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE notifications SET read = 1 WHERE recipientUid = :uid OR recipientUid = 'ALL'")
    suspend fun markAllRead(uid: String)

    @Query("DELETE FROM notifications WHERE recipientUid = :uid")
    suspend fun deleteForUser(uid: String)
}

@Dao
interface StateSnapshotDao {
    @Query("SELECT * FROM state_snapshots WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): StateSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(snapshot: StateSnapshotEntity)

    @Query("DELETE FROM state_snapshots WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [
        LocalAccountEntity::class,
        DraftEntity::class,
        ScheduledAlarmEntity::class,
        NotificationEntity::class,
        StateSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao
    abstract fun draftDao(): DraftDao
    abstract fun alarmDao(): AlarmDao
    abstract fun notificationDao(): NotificationDao
    abstract fun stateSnapshotDao(): StateSnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "hold_high_the_shop.db",
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
        }

        internal fun resetForTests() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
