package app.parity.shared.data

import app.parity.core.list.Category
import app.parity.core.list.CategoryClassifier
import app.parity.core.list.ListMatcher
import app.parity.core.list.ShoppingListText
import app.parity.core.scan.Names
import app.parity.shared.util.newId
import app.parity.shared.util.now
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shopping lists with classification, check-off and purchase matching (design §8). */
class ListRepository(private val db: ParityDatabase) {
    private val dao = db.lists()
    private val overrides = db.overrides()

    val lists: Flow<List<ShoppingListEntity>> = dao.observeLists()

    fun items(listId: String): Flow<List<ShoppingListItemEntity>> = dao.observeItems(listId)

    suspend fun ensureList(): ShoppingListEntity =
        dao.allLists().firstOrNull() ?: createList("Shopping list")

    suspend fun createList(name: String): ShoppingListEntity {
        val list = ShoppingListEntity(newId(), name.trim().ifEmpty { "Shopping list" }, now())
        dao.upsertList(list)
        return list
    }

    suspend fun renameList(list: ShoppingListEntity, name: String) = dao.upsertList(list.copy(name = name.trim().ifEmpty { list.name }))

    suspend fun deleteList(listId: String) {
        db.useWriterConnectionTransaction {
            dao.deleteItemsOf(listId)
            dao.deleteList(listId)
        }
    }

    suspend fun classify(text: String): Category {
        overrides.get(Names.normalize(text))?.let { return Category.fromName(it.category) }
        return CategoryClassifier.classify(text)
    }

    /** Parses free text into items and adds the ones not already on the list. */
    suspend fun addFromText(listId: String, input: String): Int {
        val existing = dao.items(listId)
        val known = existing.map { Names.tokens(it.text).joinToString(" ") }.toMutableSet()
        var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        var added = 0
        for (text in ShoppingListText.parse(input)) {
            if (!known.add(Names.tokens(text).joinToString(" "))) continue
            dao.upsertItem(
                ShoppingListItemEntity(newId(), listId, text, classify(text).name, false, null, null, position++, now()),
            )
            added++
        }
        return added
    }

    suspend fun setChecked(item: ShoppingListItemEntity, checked: Boolean) =
        dao.upsertItem(item.copy(checked = checked, checkedAt = if (checked) now() else null, matchedObservationId = if (checked) item.matchedObservationId else null))

    /** Moves an item to another category and remembers the choice for next time (design §8.2 tier 3). */
    suspend fun setCategory(item: ShoppingListItemEntity, category: Category) {
        dao.upsertItem(item.copy(category = category.name))
        overrides.upsert(CategoryOverrideEntity(Names.normalize(item.text), category.name))
    }

    suspend fun delete(item: ShoppingListItemEntity) = dao.deleteItem(item)

    suspend fun restore(item: ShoppingListItemEntity) = dao.upsertItem(item)

    /** Crosses off the list item matching a bought product, if any (design §8.3). */
    suspend fun checkOffPurchase(listId: String, productNames: List<String>, observationId: String): ShoppingListItemEntity? {
        val open = dao.items(listId).filter { !it.checked }
        val match = ListMatcher.bestMatch(open, { it.text }, productNames) ?: return null
        val checked = match.copy(checked = true, checkedAt = now(), matchedObservationId = observationId)
        dao.upsertItem(checked)
        return checked
    }

    /** Un-crosses items that were auto-checked by a purchase that left the cart. */
    suspend fun uncheckPurchase(observationId: String) {
        dao.allItems().filter { it.matchedObservationId == observationId }.forEach {
            dao.upsertItem(it.copy(checked = false, checkedAt = null, matchedObservationId = null))
        }
    }

    suspend fun openItems(listId: String): List<ShoppingListItemEntity> = dao.items(listId).filter { !it.checked }

    suspend fun toPayload(listId: String): ListPayload {
        val list = dao.allLists().firstOrNull { it.id == listId } ?: ensureList()
        return ListPayload(name = list.name, items = dao.items(list.id).map { ListPayloadItem(it.text, it.category, it.checked) })
    }

    /** Imports a received list, either merged into [intoListId] or as a new list. Returns the list ID. */
    suspend fun import(payload: ListPayload, intoListId: String?): String {
        val listId = intoListId ?: createList(payload.name).id
        val existing = dao.items(listId)
        val known = existing.map { Names.tokens(it.text).joinToString(" ") }.toMutableSet()
        var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        payload.items.forEach { item ->
            if (!known.add(Names.tokens(item.t).joinToString(" "))) return@forEach
            val category = item.c?.let(Category::fromName)?.takeIf { it != Category.OTHER } ?: classify(item.t)
            dao.upsertItem(ShoppingListItemEntity(newId(), listId, item.t, category.name, item.x, if (item.x) now() else null, null, position++, now()))
        }
        return listId
    }
}

/** Everything that can travel phone-to-phone by QR (design §8.4–8.5). */
@Serializable
sealed class TransferPayload

@Serializable
@SerialName("parity.list")
data class ListPayload(val v: Int = 1, val name: String, val items: List<ListPayloadItem>) : TransferPayload()

@Serializable
data class ListPayloadItem(val t: String, val c: String? = null, val x: Boolean = false)

/** A full backup: the same CSV files as the ZIP export, by file name. */
@Serializable
@SerialName("parity.backup")
data class BackupPayload(val v: Int = 1, val files: Map<String, String>) : TransferPayload()
