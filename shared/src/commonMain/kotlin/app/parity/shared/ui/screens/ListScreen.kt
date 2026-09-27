package app.parity.shared.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.parity.core.list.Category
import app.parity.shared.app.AppGraph
import app.parity.shared.data.ShoppingListItemEntity
import app.parity.shared.ui.components.PillButton
import app.parity.shared.ui.components.RoundIconButton
import app.parity.shared.ui.components.strikeThrough
import app.parity.shared.ui.theme.Parity

/** Shopping list grouped by category, with green strikethrough when checked (design §8). */
@Composable
fun ListScreen(graph: AppGraph) {
    val controller = graph.listController
    val lists by controller.lists.collectAsState()
    val active by controller.activeList.collectAsState()
    val items by controller.items.collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var newListDialog by remember { mutableStateOf(false) }
    var categoryFor by remember { mutableStateOf<ShoppingListItemEntity?>(null) }
    val collapsed = remember { mutableStateMapOf<Category, Boolean>() }
    val c = Parity.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { menuOpen = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(active?.name ?: "Shopping list", style = Parity.type.headline)
                    Icon(Icons.Rounded.ExpandMore, contentDescription = "Switch list", tint = c.textSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    lists.forEach { list ->
                        DropdownMenuItem(text = { Text(list.name) }, onClick = { controller.select(list.id); menuOpen = false })
                    }
                    DropdownMenuItem(text = { Text("New list…") }, onClick = { newListDialog = true; menuOpen = false })
                    active?.let { list ->
                        if (lists.size > 1) {
                            DropdownMenuItem(text = { Text("Delete “${list.name}”", color = c.down) }, onClick = { controller.deleteList(list); menuOpen = false })
                        }
                    }
                }
            }
            RoundIconButton(Icons.Rounded.QrCodeScanner, "Import a list by QR", { graph.transfer.startReceiving() })
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.QrCode2, "Share this list by QR", { graph.transfer.sendActiveList() }, enabled = items.isNotEmpty())
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("milk, eggs and bread…", color = c.textSecondary) },
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                colors = parityTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { controller.add(input); input = "" }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.Add, "Add items", { controller.add(input); input = "" }, size = 52.dp, background = c.accent, tint = c.onAccent, enabled = input.isNotBlank())
        }

        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Type what you need, separated by commas.", style = Parity.type.body, color = c.textSecondary)
                Text("Parity sorts it into aisles for you.", style = Parity.type.body, color = c.textSecondary)
            }
        }

        val grouped = items.groupBy { Category.fromName(it.category) }.toSortedMap(compareBy { it.ordinal })
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            grouped.forEach { (category, group) ->
                val isCollapsed = collapsed[category] == true
                item(key = "h-${category.name}") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { collapsed[category] = !isCollapsed }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.label.uppercase(), style = Parity.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                        Text("${group.count { !it.checked }}/${group.size}", style = Parity.type.caption, color = c.textSecondary)
                        Icon(if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                if (!isCollapsed) {
                    items(group.sortedBy { it.checked }, key = { it.id }) { item ->
                        ListRow(item, onToggle = { controller.toggle(item) }, onLongPress = { categoryFor = item }, onDelete = { controller.delete(item) })
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newListDialog) {
        NewListDialog(onCreate = { controller.newList(it); newListDialog = false }, onDismiss = { newListDialog = false })
    }
    categoryFor?.let { item ->
        CategoryDialog(
            current = Category.fromName(item.category),
            onPick = { controller.setCategory(item, it); categoryFor = null },
            onDismiss = { categoryFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(item: ShoppingListItemEntity, onToggle: () -> Unit, onLongPress: () -> Unit, onDelete: () -> Unit) {
    val c = Parity.colors
    val strike by animateFloatAsState(if (item.checked) 1f else 0f, tween(300), label = "strike")
    val dim by animateFloatAsState(if (item.checked) 0.55f else 1f, tween(300), label = "dim")
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.StartToEnd) onDelete() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.down.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = c.down)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (item.checked) "Checked" else "Not checked",
                tint = if (item.checked) c.up else c.textSecondary,
            )
            Spacer(Modifier.width(12.dp))
            Text(item.text, style = Parity.type.body, color = c.textPrimary, modifier = Modifier.alpha(dim).strikeThrough(strike, c.up))
        }
    }
}

@Composable
private fun NewListDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("New list", style = Parity.type.headline) },
        text = {
            OutlinedTextField(name, { name = it.take(40) }, singleLine = true, placeholder = { Text("Weekend") }, shape = RoundedCornerShape(18.dp), colors = parityTextFieldColors())
        },
        confirmButton = { PillButton("Create", { onCreate(name) }, height = 44.dp) },
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}

/** Long-press: move an item to another category; Parity remembers it (design §8.2). */
@Composable
private fun CategoryDialog(current: Category, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Parity.colors.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Move to…", style = Parity.type.headline) },
        text = {
            LazyColumn {
                items(Category.entries) { category ->
                    Row(
                        Modifier.fillMaxWidth().clip(CircleShape).clickable { onPick(category) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.label, style = Parity.type.body, color = if (category == current) Parity.colors.accent else Parity.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (category == current) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Parity.colors.accent)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { PillButton("Cancel", onDismiss, primary = false) },
    )
}
