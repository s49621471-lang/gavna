package com.unique.core.vpm.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * UNIQUE's state database.
 *
 * Room rather than JSON files, because this is the data whose consistency decides whether
 * a user loses an instance: which packages exist, which instances point at which package
 * version, which device profile each instance owns. A JSON file that is half-written when
 * the process dies takes an instance with it.
 *
 * Foreign keys are declared and enforced so an instance cannot outlive its package, and
 * indices exist for the two access patterns the UI actually has: "list instances" and
 * "find the instances of this package".
 */

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "packages",
    indices = [Index(value = ["packageName"], unique = true)],
)
data class PackageEntity(
    @PrimaryKey val packageName: String,
    val label: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val apkDir: String,
    val nativeLibraryDir: String?,
    /** Comma-separated split file names, in manifest order. */
    val splits: String,
    val importedAtMillis: Long,
    /** versionCode of the host's installed copy when it is newer; null when it is not. */
    val hostUpdateVersionCode: Long?,
)

@Entity(
    tableName = "instances",
    foreignKeys = [
        ForeignKey(
            entity = PackageEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["packageName"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("packageName"), Index("spaceId")],
)
data class InstanceEntity(
    @PrimaryKey val vuid: Int,
    val packageName: String,
    val spaceId: String,
    val displayName: String,
    val createdAtMillis: Long,
    val lastLaunchedAtMillis: Long?,
    @Embedded(prefix = "profile_") val profile: DeviceProfileRecord,
)

/**
 * The persisted device profile.
 *
 * Embedded in the instance row rather than joined: a profile has exactly one owner and
 * reading them separately would let an instance load with a stale or missing profile,
 * which is precisely the inconsistency the design forbids.
 */
data class DeviceProfileRecord(
    val profileId: String,
    val displayName: String,
    val androidId: String,
    val instanceId: String,
    val installId: String,
    val gsfId: String,
    val mediaDrmId: String,
    val wifiMac: String,
    val bluetoothMac: String,
    val serial: String,
    val locale: String?,
    val timeZone: String?,
    val generation: Int,
)

@Entity(
    tableName = "permissions",
    primaryKeys = ["vuid", "permission"],
    foreignKeys = [
        ForeignKey(
            entity = InstanceEntity::class,
            parentColumns = ["vuid"],
            childColumns = ["vuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PermissionEntity(
    val vuid: Int,
    val permission: String,
    /** Ordinal of PermissionState. */
    @ColumnInfo(name = "state") val state: Int,
)

data class InstanceWithPackage(
    @Embedded val instance: InstanceEntity,
    @Embedded(prefix = "pkg_") val pkg: PackageEntity,
)

@Dao
interface UniqueDao {

    @Query("SELECT * FROM spaces ORDER BY createdAtMillis")
    fun spaces(): Flow<List<SpaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpace(space: SpaceEntity)

    @Query("SELECT * FROM packages ORDER BY label")
    fun packages(): Flow<List<PackageEntity>>

    @Query("SELECT * FROM packages WHERE packageName = :packageName")
    suspend fun packageOf(packageName: String): PackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackage(entity: PackageEntity)

    @Query("DELETE FROM packages WHERE packageName = :packageName")
    suspend fun deletePackage(packageName: String)

    @Query("SELECT * FROM instances ORDER BY createdAtMillis")
    fun instances(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances WHERE packageName = :packageName ORDER BY createdAtMillis")
    suspend fun instancesOf(packageName: String): List<InstanceEntity>

    @Query("SELECT * FROM instances WHERE vuid = :vuid")
    suspend fun instance(vuid: Int): InstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstance(entity: InstanceEntity)

    @Update
    suspend fun updateInstance(entity: InstanceEntity)

    @Query("DELETE FROM instances WHERE vuid = :vuid")
    suspend fun deleteInstance(vuid: Int)

    /**
     * The next free virtual user id.
     *
     * Reuses gaps rather than always incrementing, because a vuid is a directory name and
     * letting it grow without bound turns the users/ tree into a sparse mess after a user
     * has added and removed a few hundred instances.
     */
    @Query(
        "SELECT MIN(v) FROM (SELECT 0 AS v UNION SELECT vuid + 1 FROM instances) " +
            "WHERE v NOT IN (SELECT vuid FROM instances)"
    )
    suspend fun nextVuid(): Int

    @Query("SELECT * FROM permissions WHERE vuid = :vuid")
    suspend fun permissions(vuid: Int): List<PermissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermission(entity: PermissionEntity)

    @Transaction
    suspend fun removeInstanceAndOrphanPackage(vuid: Int) {
        val instance = instance(vuid) ?: return
        deleteInstance(vuid)
        if (instancesOf(instance.packageName).isEmpty()) deletePackage(instance.packageName)
    }
}

@Database(
    entities = [
        SpaceEntity::class,
        PackageEntity::class,
        InstanceEntity::class,
        PermissionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UniqueDatabase : RoomDatabase() {
    abstract fun dao(): UniqueDao

    companion object {
        const val NAME = "unique-state.db"

        /**
         * Migrations are appended here as the schema changes.
         *
         * Deliberately never `fallbackToDestructiveMigration`: destroying the state
         * database destroys the mapping from instances to their data directories, and the
         * data would still be on disk with nothing pointing at it.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
