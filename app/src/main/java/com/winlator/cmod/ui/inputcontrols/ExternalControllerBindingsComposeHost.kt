package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme

private val BINDING_TYPE_NAMES = listOf("Keyboard", "Mouse", "Gamepad")

@Immutable
data class ControllerBindingItem(
    val position: Int,
    val title: String,
    val typeIndex: Int,
    val bindingLabels: List<String>,
    val bindingIndex: Int
)

@Immutable
data class ExternalControllerBindingsModel(
    val controllerName: String,
    val bindings: List<ControllerBindingItem>,
    val highlightedPosition: Int = -1
)

@Stable
interface ExternalControllerBindingsCallbacks {
    fun onBack()
    fun onRemoveBinding(position: Int)
    fun onBindingTypeChanged(position: Int, typeIndex: Int)
    fun onBindingValueChanged(position: Int, typeIndex: Int, valueIndex: Int)
}

object ExternalControllerBindingsComposeHost {
    @JvmStatic
    fun create(
        context: Context,
        model: ExternalControllerBindingsModel,
        callbacks: ExternalControllerBindingsCallbacks
    ): ComposeView {
        val modelState = mutableStateOf(model)
        return ComposeView(context).apply {
            tag = modelState
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { ExternalControllerBindingsScreen(modelState.value, callbacks) } }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun update(view: View?, model: ExternalControllerBindingsModel) {
        (view?.tag as? MutableState<ExternalControllerBindingsModel>)?.value = model
    }
}

@Composable
private fun ExternalControllerBindingsScreen(
    model: ExternalControllerBindingsModel,
    callbacks: ExternalControllerBindingsCallbacks
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(model.controllerName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = callbacks::onBack) { Icon(Icons.Outlined.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        if (model.bindings.isEmpty()) {
            EmptyBindings(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(model.bindings, key = { it.position }) { item ->
                    BindingRow(
                        item = item,
                        highlighted = item.position == model.highlightedPosition,
                        onRemove = { callbacks.onRemoveBinding(item.position) },
                        onTypeChanged = { newType -> callbacks.onBindingTypeChanged(item.position, newType) },
                        onValueChanged = { newValue ->
                            callbacks.onBindingValueChanged(item.position, item.typeIndex, newValue)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBindings(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(74.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Gamepad, null, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("No bindings yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Press any button on the controller to add a binding.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BindingRow(
    item: ControllerBindingItem,
    highlighted: Boolean,
    onRemove: () -> Unit,
    onTypeChanged: (Int) -> Unit,
    onValueChanged: (Int) -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "bindingHighlight"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, "Remove binding", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownField(
                    modifier = Modifier.weight(1f),
                    label = BINDING_TYPE_NAMES[item.typeIndex],
                    options = BINDING_TYPE_NAMES,
                    onSelected = { index -> onTypeChanged(index) }
                )
                DropdownField(
                    modifier = Modifier.weight(1f),
                    label = item.bindingLabels.getOrElse(item.bindingIndex) { "-" },
                    options = item.bindingLabels,
                    onSelected = { index -> onValueChanged(index) }
                )
            }
        }
    }
}

@Composable
private fun DropdownField(
    modifier: Modifier = Modifier,
    label: String,
    options: List<String>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelected(index) }
                )
            }
        }
    }
}
