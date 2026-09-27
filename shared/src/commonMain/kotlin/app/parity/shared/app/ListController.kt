package app.parity.shared.app

import app.parity.core.list.Category
import app.parity.shared.data.ShoppingListEntity
import app.parity.shared.data.ShoppingListItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shopping list screen and the list strip on Home (design §8). */
@OptIn(ExperimentalCoroutinesApi::class)
class ListController(private val graph: AppGraph) {
    private val scope = graph.scope
    private val selectedId = MutableStateFlow<String?>(null)

    val lists: StateFlow<List<ShoppingListEntity>> = graph.lists.lists.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeList: StateFlow<ShoppingListEntity?> = combine(lists, selectedId) { all, id ->
        all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val items: StateFlow<List<ShoppingListItemEntity>> = activeList
        .flatMapLatest { list -> list?.let { graph.lists.items(it.id) } ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun activeListIdOrNull(): String? = activeList.value?.id

    fun select(listId: String) {
        selectedId.value = listId
    }

    fun add(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val listId = activeList.value?.id ?: graph.lists.ensureList().id.also { selectedId.value = it }
            val added = graph.lists.addFromText(listId, text)
            if (added == 0) graph.messages.show("Those items are already on the list")
        }
    }

    fun toggle(item: ShoppingListItemEntity) {
        scope.launch {
            graph.lists.setChecked(item, !item.checked)
            if (!item.checked) graph.platform.haptics.tick()
        }
    }

    fun setCategory(item: ShoppingListItemEntity, category: Category) {
        scope.launch { graph.lists.setCategory(item, category) }
    }

    fun delete(item: ShoppingListItemEntity) {
        scope.launch {
            graph.lists.delete(item)
            graph.messages.show("Removed “${item.text}”", "Undo") { graph.lists.restore(item) }
        }
    }

    fun newList(name: String) {
        scope.launch { selectedId.value = graph.lists.createList(name).id }
    }

    fun rename(list: ShoppingListEntity, name: String) {
        scope.launch { graph.lists.renameList(list, name) }
    }

    fun deleteList(list: ShoppingListEntity) {
        scope.launch {
            graph.lists.deleteList(list.id)
            if (selectedId.value == list.id) selectedId.value = null
        }
    }
}
