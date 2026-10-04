package com.winlator.cmod.droiddeck

import android.app.Activity
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme

/**
 * Panel pengaturan DroidDeck (tombol "DD" di left sidebar) berbasis Jetpack Compose.
 * Menggantikan AlertDialog lama. Semua nilai disimpan lewat DDPrefs; perubahan
 * langsung diterapkan ke kontrol lewat DDController.refreshControls().
 */
object DDSettingsComposeDialog {
    @JvmStatic
    @Suppress("DEPRECATION")
    fun show(activity: Activity) {
        val dialog = ComponentDialog(activity, R.style.GameSavesDialog)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
            decorView.setBackgroundColor(AColor.TRANSPARENT)
            decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }
        dialog.setContentView(ComposeView(activity).apply {
            setBackgroundColor(AColor.TRANSPARENT)
            setContent {
                WinZTheme {
                    DDSettingsPanel(
                        activity = activity,
                        onClose = { dialog.dismiss() },
                        onEditLayout = {
                            dialog.dismiss()
                            DDController.openLayoutEditor()
                        }
                    )
                }
            }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

@Composable
private fun DDSettingsPanel(activity: Activity, onClose: () -> Unit, onEditLayout: () -> Unit) {
    var enabled by remember { mutableStateOf(DDController.isControlsEnabled()) }
    var keyboardShown by remember { mutableStateOf(DDController.isKeyboardShown()) }
    var s by remember { mutableStateOf(DDPrefs.read(activity)) }
    var showMapping by remember { mutableStateOf(false) }

    // Baca ulang semua nilai dari DDPrefs lalu terapkan ke kontrol yang sedang tampil.
    fun refresh() {
        s = DDPrefs.read(activity)
        enabled = DDController.isControlsEnabled()
        DDController.refreshControls()
    }

    val scrim = interactionSourceFree(onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .then(scrim)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DroidDeck", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Gamepad overlay settings",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }

                SwitchRow(
                    title = "DroidDeck controls",
                    subtitle = "Replace Winlator on-screen controls with the DroidDeck gamepad",
                    checked = enabled,
                    onChange = { on ->
                        DDController.setControlsEnabled(on)
                        enabled = on
                    }
                )
                SwitchRow(
                    title = "PC keyboard",
                    subtitle = "Show the on-screen PC keyboard",
                    checked = keyboardShown,
                    onChange = { on ->
                        DDController.setKeyboardShown(on)
                        keyboardShown = DDController.isKeyboardShown()
                    }
                )

                HorizontalDivider()
                SectionTitle("Appearance")

                Text("Tint", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DDPrefs.TINTS.forEachIndexed { i, argb ->
                        val selected = argb == s.tint
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    BorderStroke(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outline
                                    ),
                                    CircleShape
                                )
                                .clickable {
                                    DDPrefs.setTint(activity, argb)
                                    refresh()
                                }
                        )
                    }
                }
                val tintIndex = DDPrefs.TINTS.indexOf(s.tint)
                Text(
                    if (tintIndex >= 0) DDPrefs.TINT_NAMES[tintIndex] else "Custom",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Opacity", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = DDPrefs.OPACITIES.map { "$it%" },
                    selectedIndex = DDPrefs.OPACITIES.indexOf(s.opacity),
                    onSelect = { i ->
                        DDPrefs.setOpacity(activity, DDPrefs.OPACITIES[i])
                        refresh()
                    }
                )

                Text("Size", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = DDPrefs.SIZES.map { "$it%" },
                    selectedIndex = DDPrefs.SIZES.indexOf(s.size),
                    onSelect = { i ->
                        DDPrefs.setSize(activity, DDPrefs.SIZES[i])
                        refresh()
                    }
                )

                HorizontalDivider()
                SectionTitle("Behaviour")
                SwitchRow(
                    title = "Double-tap stick = L3/R3 click",
                    subtitle = null,
                    checked = s.stickClick,
                    onChange = { on ->
                        DDPrefs.setStickClick(activity, on)
                        refresh()
                    }
                )
                SwitchRow(
                    title = "Adaptive sticks",
                    subtitle = "Sticks appear under your finger",
                    checked = s.adaptiveSticks,
                    onChange = { on ->
                        DDPrefs.setAdaptiveSticks(activity, on)
                        refresh()
                    }
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMapping = !showMapping },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Button mapping",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (showMapping) "Hide" else "Show",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (showMapping) {
                    DDPrefs.MAPPABLE_IDS.forEachIndexed { i, id ->
                        val targetId = s.mapping[id] ?: id
                        val targetIndex = DDPrefs.TARGET_IDS.indexOf(targetId)
                        MappingRow(
                            name = DDPrefs.MAPPABLE_NAMES[i],
                            shown = if (targetIndex >= 0) DDPrefs.TARGET_NAMES[targetIndex] else targetId,
                            onPick = { idx ->
                                DDPrefs.setTarget(activity, id, DDPrefs.TARGET_IDS[idx])
                                refresh()
                            }
                        )
                    }
                }

                HorizontalDivider()
                Button(onClick = onEditLayout, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit layout")
                }

                SectionTitle("Reset")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { DDController.resetLayoutPrefs(); refresh() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Layout", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { DDController.resetMappingPrefs(); refresh() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Mapping", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { DDController.resetEverything(); refresh() },
                        modifier = Modifier.weight(1f)
                    ) { Text("All", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Klik di area gelap (di luar panel) menutup dialog, tanpa efek ripple. */
@Composable
private fun interactionSourceFree(onClick: () -> Unit): Modifier =
    Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun MappingRow(name: String, shown: String, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(shown) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DDPrefs.TARGET_NAMES.forEachIndexed { idx, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onPick(idx)
                        }
                    )
                }
            }
        }
    }
}
