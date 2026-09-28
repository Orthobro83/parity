package app.parity.shared.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        SettingsEntity::class, StoreEntity::class, ProductEntity::class, PriceObservationEntity::class,
        CartLineEntity::class, ShoppingSessionEntity::class, PurchaseLineEntity::class,
        ShoppingListEntity::class, ShoppingListItemEntity::class, CategoryOverrideEntity::class,
        RateCacheEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // v2: multi-buy deals on observations, cart lines and purchase lines. v3: translation settings.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3, spec = RetryFailedTranslations::class)],
)
@ConstructedBy(ParityDatabaseConstructor::class)
abstract class ParityDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun stores(): StoreDao
    abstract fun products(): ProductDao
    abstract fun observations(): ObservationDao
    abstract fun cart(): CartDao
    abstract fun sessions(): SessionDao
    abstract fun lists(): ListDao
    abstract fun overrides(): OverrideDao
    abstract fun rates(): RateCacheDao
}

/**
 * 0.3.0-beta.3 marked a name as having nothing to translate when a translation failed (a pack still
 * loading, or the text coming back unchanged); those are translated again.
 */
class RetryFailedTranslations : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(
            "UPDATE product SET translatedLang = NULL WHERE translatedName IS NULL AND userEditedName IS NULL " +
                "AND originalLang IS NOT NULL AND translatedLang IS NOT NULL AND translatedLang != originalLang",
        )
    }
}

// Room's compiler generates the actual implementation for each platform.
@Suppress("KotlinNoActualForExpect")
expect object ParityDatabaseConstructor : RoomDatabaseConstructor<ParityDatabase> {
    override fun initialize(): ParityDatabase
}
