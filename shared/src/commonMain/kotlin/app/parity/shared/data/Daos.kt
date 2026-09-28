package app.parity.shared.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface StoreDao {
    @Query("SELECT * FROM store ORDER BY createdAt")
    suspend fun all(): List<StoreEntity>

    @Query("SELECT * FROM store ORDER BY createdAt")
    fun observeAll(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM store WHERE id = :id")
    suspend fun get(id: String): StoreEntity?

    @Upsert
    suspend fun upsert(store: StoreEntity)

    @Upsert
    suspend fun upsertAll(stores: List<StoreEntity>)

    @Query("DELETE FROM store")
    suspend fun deleteAll()
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun get(id: String): ProductEntity?

    @Query("SELECT * FROM product WHERE id = :id")
    fun observe(id: String): Flow<ProductEntity?>

    @Query("SELECT * FROM product WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): ProductEntity?

    @Query(
        "SELECT * FROM product WHERE normalizedName = :normalizedName AND " +
            "(storeId = :storeId OR (:storeId IS NULL AND storeId IS NULL)) LIMIT 1",
    )
    suspend fun byName(normalizedName: String, storeId: String?): ProductEntity?

    @Query("SELECT * FROM product WHERE storeId = :storeId OR (:storeId IS NULL AND storeId IS NULL)")
    suspend fun inStore(storeId: String?): List<ProductEntity>

    @Query("SELECT * FROM product ORDER BY createdAt")
    suspend fun all(): List<ProductEntity>

    /** Names not yet translated into [language], e.g. read while the language pack downloaded. */
    @Query(
        "SELECT * FROM product WHERE originalName IS NOT NULL AND userEditedName IS NULL AND " +
            "(translatedLang IS NULL OR translatedLang != :language) AND " +
            "(originalLang IS NULL OR originalLang != :language) ORDER BY createdAt DESC LIMIT 200",
    )
    suspend fun untranslated(language: String): List<ProductEntity>

    @Upsert
    suspend fun upsert(product: ProductEntity)

    @Upsert
    suspend fun upsertAll(products: List<ProductEntity>)

    @Query("DELETE FROM product")
    suspend fun deleteAll()
}

@Dao
interface ObservationDao {
    @Query("SELECT * FROM price_observation WHERE id = :id")
    suspend fun get(id: String): PriceObservationEntity?

    /** The previous sighting used by the ▲/▼ indicator (design §9). */
    @Query(
        "SELECT * FROM price_observation WHERE productId = :productId AND localCurrency = :localCurrency " +
            "AND baseCurrency = :baseCurrency AND id != :excludeId AND fxRate IS NOT NULL " +
            "ORDER BY observedAt DESC LIMIT 1",
    )
    suspend fun previousFor(productId: String, localCurrency: String, baseCurrency: String, excludeId: String): PriceObservationEntity?

    @Query("SELECT * FROM price_observation WHERE productId = :productId AND observedAt >= :since ORDER BY observedAt DESC")
    suspend fun recentFor(productId: String, since: Long): List<PriceObservationEntity>

    @Query("SELECT * FROM price_observation WHERE productId = :productId ORDER BY observedAt")
    fun observeForProduct(productId: String): Flow<List<PriceObservationEntity>>

    @Query("SELECT * FROM price_observation ORDER BY observedAt")
    suspend fun all(): List<PriceObservationEntity>

    @Query("SELECT * FROM price_observation ORDER BY observedAt")
    fun observeAll(): Flow<List<PriceObservationEntity>>

    @Upsert
    suspend fun upsert(observation: PriceObservationEntity)

    @Upsert
    suspend fun upsertAll(observations: List<PriceObservationEntity>)

    @Query("DELETE FROM price_observation")
    suspend fun deleteAll()

    @Query("DELETE FROM price_observation WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CartDao {
    /** Joins the product and observation tables so edits to either re-emit the cart. */
    @Query(
        "SELECT cart_line.* FROM cart_line " +
            "JOIN price_observation ON price_observation.id = cart_line.observationId " +
            "JOIN product ON product.id = price_observation.productId ORDER BY cart_line.addedAt",
    )
    fun observe(): Flow<List<CartLineEntity>>

    @Query("SELECT * FROM cart_line ORDER BY addedAt")
    suspend fun all(): List<CartLineEntity>

    @Query("SELECT * FROM cart_line WHERE observationId = :observationId LIMIT 1")
    suspend fun byObservation(observationId: String): CartLineEntity?

    @Upsert
    suspend fun upsert(line: CartLineEntity)

    @Delete
    suspend fun delete(line: CartLineEntity)

    @Query("DELETE FROM cart_line")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM shopping_session WHERE finalizedAt BETWEEN :from AND :to ORDER BY finalizedAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<ShoppingSessionEntity>>

    @Query("SELECT * FROM shopping_session WHERE id = :id")
    suspend fun get(id: String): ShoppingSessionEntity?

    @Query("SELECT * FROM shopping_session ORDER BY finalizedAt")
    suspend fun all(): List<ShoppingSessionEntity>

    @Query("SELECT * FROM purchase_line WHERE sessionId = :sessionId")
    suspend fun lines(sessionId: String): List<PurchaseLineEntity>

    @Query("SELECT * FROM purchase_line")
    suspend fun allLines(): List<PurchaseLineEntity>

    /** Fills in a translation that arrived after purchases of the product were saved without one. */
    @Query(
        "UPDATE purchase_line SET nameTranslatedAtPurchase = :translated WHERE productId = :productId AND " +
            "(nameTranslatedAtPurchase IS NULL OR nameTranslatedAtPurchase = nameAtPurchase)",
    )
    suspend fun fillTranslation(productId: String, translated: String)

    @Upsert
    suspend fun upsert(session: ShoppingSessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<ShoppingSessionEntity>)

    @Upsert
    suspend fun upsertLines(lines: List<PurchaseLineEntity>)

    @Query("DELETE FROM shopping_session")
    suspend fun deleteAll()

    @Query("DELETE FROM purchase_line")
    suspend fun deleteAllLines()
}

@Dao
interface ListDao {
    @Query("SELECT * FROM shopping_list ORDER BY createdAt DESC")
    fun observeLists(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_list ORDER BY createdAt DESC")
    suspend fun allLists(): List<ShoppingListEntity>

    @Query("SELECT * FROM shopping_list_item WHERE listId = :listId ORDER BY position, createdAt")
    fun observeItems(listId: String): Flow<List<ShoppingListItemEntity>>

    @Query("SELECT * FROM shopping_list_item WHERE listId = :listId ORDER BY position, createdAt")
    suspend fun items(listId: String): List<ShoppingListItemEntity>

    @Query("SELECT * FROM shopping_list_item")
    suspend fun allItems(): List<ShoppingListItemEntity>

    @Upsert
    suspend fun upsertList(list: ShoppingListEntity)

    @Upsert
    suspend fun upsertLists(lists: List<ShoppingListEntity>)

    @Upsert
    suspend fun upsertItem(item: ShoppingListItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<ShoppingListItemEntity>)

    @Delete
    suspend fun deleteItem(item: ShoppingListItemEntity)

    @Query("DELETE FROM shopping_list_item WHERE listId = :listId")
    suspend fun deleteItemsOf(listId: String)

    @Query("DELETE FROM shopping_list WHERE id = :listId")
    suspend fun deleteList(listId: String)

    @Query("DELETE FROM shopping_list")
    suspend fun deleteAllLists()

    @Query("DELETE FROM shopping_list_item")
    suspend fun deleteAllItems()
}

@Dao
interface OverrideDao {
    @Query("SELECT * FROM user_category_override WHERE normalizedText = :text")
    suspend fun get(text: String): CategoryOverrideEntity?

    @Query("SELECT * FROM user_category_override")
    suspend fun all(): List<CategoryOverrideEntity>

    @Upsert
    suspend fun upsert(entity: CategoryOverrideEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CategoryOverrideEntity>)

    @Query("DELETE FROM user_category_override")
    suspend fun deleteAll()
}

@Dao
interface RateCacheDao {
    @Query("SELECT * FROM rate_cache WHERE base = :base AND quote = :quote AND provider = :provider")
    suspend fun get(base: String, quote: String, provider: String): RateCacheEntity?

    @Query("SELECT * FROM rate_cache WHERE base = :base AND quote = :quote ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun latestAnyProvider(base: String, quote: String): RateCacheEntity?

    @Query("SELECT * FROM rate_cache")
    suspend fun all(): List<RateCacheEntity>

    @Upsert
    suspend fun upsert(entity: RateCacheEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RateCacheEntity>)

    @Query("DELETE FROM rate_cache")
    suspend fun deleteAll()
}
