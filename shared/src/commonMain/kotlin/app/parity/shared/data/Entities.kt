package app.parity.shared.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables from design §13. IDs are UUID strings so CSV restores can merge without collisions.
// Money and rates are stored as decimal strings (never floating point).

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val baseCurrency: String,
    val language: String,
    val localCurrencyOverride: String?,
    val manualCountry: String?,
    val detectedCountry: String?,
    val rateProvider: String,
    val rateApiKey: String?,
    val trueBlack: Boolean,
    val onboarded: Boolean,
)

@Entity(tableName = "store")
data class StoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val countryCode: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "product",
    indices = [Index("barcode"), Index("normalizedName"), Index("storeId")],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val barcode: String?,
    val originalName: String?,
    val normalizedName: String?,
    val originalLang: String?,
    val translatedName: String?,
    val translatedLang: String?,
    val userEditedName: String?,
    val category: String?,
    val storeId: String?,
    val createdAt: Long,
) {
    /** Name shown to the user: their own edit, then the translation, then what was read. */
    val displayName: String? get() = userEditedName ?: translatedName ?: originalName
}

@Entity(
    tableName = "price_observation",
    indices = [Index("productId"), Index("observedAt"), Index("sessionId")],
)
data class PriceObservationEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val storeId: String?,
    val sessionId: String?,
    val observedAt: Long,
    val countryCode: String?,
    val localCurrency: String,
    val price: String,
    val regularPrice: String?,
    val isPromo: Boolean,
    /** AUTO or USER (design §6.3). */
    val promoSource: String,
    val baseCurrency: String,
    /** Local units per 1 base unit at scan time; null when no rate was available. */
    val fxRate: String?,
    /** When the provider published the rate. */
    val fxRateAt: Long?,
    val fxFetchedAt: Long?,
    val fxProvider: String?,
    val fxPivot: String?,
)

@Entity(tableName = "cart_line")
data class CartLineEntity(
    @PrimaryKey val id: String,
    val observationId: String,
    val quantity: String,
    val addedAt: Long,
)

@Entity(tableName = "shopping_session", indices = [Index("finalizedAt")])
data class ShoppingSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finalizedAt: Long,
    val storeId: String?,
    val countryCode: String?,
    val baseCurrency: String,
    val totalBase: String,
    /** JSON object of local-currency totals, e.g. {"GEL":"113.40"}. */
    val totalsLocalJson: String,
)

@Entity(tableName = "purchase_line", indices = [Index("sessionId"), Index("productId")])
data class PurchaseLineEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val productId: String,
    val observationId: String,
    val quantity: String,
    val unitPriceLocal: String,
    val localCurrency: String,
    val unitPriceBase: String?,
    val baseCurrency: String,
    val fxRate: String?,
    val isPromo: Boolean,
    val nameAtPurchase: String?,
    val nameTranslatedAtPurchase: String?,
)

@Entity(tableName = "shopping_list")
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "shopping_list_item", indices = [Index("listId")])
data class ShoppingListItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val text: String,
    val category: String,
    val checked: Boolean,
    val checkedAt: Long?,
    val matchedObservationId: String?,
    val position: Int,
    val createdAt: Long,
)

@Entity(tableName = "user_category_override")
data class CategoryOverrideEntity(
    @PrimaryKey val normalizedText: String,
    val category: String,
)

@Entity(tableName = "rate_cache", primaryKeys = ["base", "quote", "provider"])
data class RateCacheEntity(
    val base: String,
    val quote: String,
    val provider: String,
    val rate: String,
    val publishedAt: Long?,
    val fetchedAt: Long,
    val pivot: String?,
)
