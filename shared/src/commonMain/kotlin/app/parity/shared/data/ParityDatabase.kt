package app.parity.shared.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        SettingsEntity::class, StoreEntity::class, ProductEntity::class, PriceObservationEntity::class,
        CartLineEntity::class, ShoppingSessionEntity::class, PurchaseLineEntity::class,
        ShoppingListEntity::class, ShoppingListItemEntity::class, CategoryOverrideEntity::class,
        RateCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
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

// Room's compiler generates the actual implementation for each platform.
@Suppress("KotlinNoActualForExpect")
expect object ParityDatabaseConstructor : RoomDatabaseConstructor<ParityDatabase> {
    override fun initialize(): ParityDatabase
}
