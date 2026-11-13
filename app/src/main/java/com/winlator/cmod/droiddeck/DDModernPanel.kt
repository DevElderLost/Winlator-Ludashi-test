package com.winlator.cmod.droiddeck

// DD-MODERN-SIDEBAR: panel pengaturan DroidDeck untuk sidebar baru (ModernSidebarHost).
// Tampilannya mengikuti panel sidebar lain: kartu surfaceVariant, baris Switch, Slider M3,
// dropdown OutlinedButton. TIDAK memakai verticalScroll sendiri; scroll dipegang kolom panel sidebar.

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor

private const val TAG = "DDModernPanel"

/** Jalankan aksi tanpa pernah melempar exception ke UI thread. */
private inline fun safe(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "Aksi panel DroidDeck gagal", t)
    }
}

@Composable
fun DDModernPanel(activity: Activity, revision: Int, onRefresh: () -> Unit) {
    var local by remember { mutableIntStateOf(0) }
    val s = remember(revision, local) { runCatching { DDPrefs.read(activity) }.getOrNull() }
    val enabled = remember(revision, local) { runCatching { DDController.panelControlsEnabled() }.getOrDefault(false) }
    val keyboard = remember(revision, local) { runCatching { DDController.panelKeyboardShown() }.getOrDefault(false) }
    // bagian Deck selalu tampil (jalur evdev); deckWanted hanya menentukan label "Hidraw only"/"None"
    val deckWanted = remember(revision, local) { runCatching { DDDeck.isWanted() }.getOrDefault(false) }
    var showMapping by remember { mutableStateOf(false) }
    var showVisibility by remember { mutableStateOf(false) }

    fun changed() {
        safe { DDController.panelRefreshControls() }
        local += 1
        onRefresh()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DroidDeck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (s == null) {
            Text(
                "DroidDeck: gagal membaca pengaturan",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        DDGroup {
            DDSwitchRow("DroidDeck controls", enabled) { on ->
                safe { DDController.panelSetControlsEnabled(on) }
                changed()
            }
            DDDivider()
            DDSwitchRow("PC keyboard", keyboard) { on ->
                safe { DDController.panelSetKeyboard(on) }
                changed()
            }
        }

        DDGroup {
            val tintIndex = DDPrefs.TINTS.indexOf(s.tint)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tint", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DDPrefs.TINTS.forEach { argb ->
                        val selected = argb == s.tint
                        Box(
                            Modifier
                                .size(34.dp)
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
                                    safe { DDPrefs.setTint(activity, argb) }
                                    changed()
                                }
                        )
                    }
                }
                Text(
                    if (tintIndex >= 0 && tintIndex < DDPrefs.TINT_NAMES.size) DDPrefs.TINT_NAMES[tintIndex] else "Custom",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DDDivider()
            DDSliderRow("Opacity", s.opacity, DDPrefs.OPACITY_MIN, DDPrefs.OPACITY_MAX, DDPrefs.OPACITY_STEP) { v ->
                safe { DDPrefs.setOpacity(activity, v) }
                changed()
            }
            DDDivider()
            DDSliderRow("Size", s.size, DDPrefs.SIZE_MIN, DDPrefs.SIZE_MAX, DDPrefs.SIZE_STEP) { v ->
                safe { DDPrefs.setSize(activity, v) }
                changed()
            }
        }

        DDGroup {
            DDSwitchRow("Double-tap stick = L3/R3 click", s.stickClick) { on ->
                safe { DDPrefs.setStickClick(activity, on) }
                changed()
            }
            DDDivider()
            DDSwitchRow("Adaptive sticks (appear under finger)", s.adaptiveSticks) { on ->
                safe { DDPrefs.setAdaptiveSticks(activity, on) }
                changed()
            }
            DDDivider()
            DDSwitchRow("Rumble (phone vibration)", s.rumble) { on ->
                safe { DDPrefs.setRumble(activity, on) }
                // matikan: hentikan getar yang sedang berjalan
                if (!on) safe { (activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.cancel() }
                changed()
            }
        }

        // DroidDeck-visibility: pilih joystick, tombol, trackpad dan grip yang tampil di layar
        DDExpandable("Visible controls", showVisibility, { showVisibility = !showVisibility }) {
            DDPrefs.VIS_SECTIONS.forEachIndexed { sec, title ->
                DDDivider()
                Text(
                    title,
                    Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val from = DDPrefs.VIS_SECTION_START[sec]
                val to = if (sec + 1 < DDPrefs.VIS_SECTION_START.size) DDPrefs.VIS_SECTION_START[sec + 1] else DDPrefs.VIS_IDS.size
                for (i in from until to) {
                    val id = DDPrefs.VIS_IDS[i]
                    DDSwitchRow(DDPrefs.VIS_NAMES[i], !DDPrefs.isHidden(activity, id)) { shown ->
                        safe { DDPrefs.setHidden(activity, id, !shown) }
                        changed()
                    }
                }
            }
            if (DDPrefs.anyHidden(activity)) {
                DDDivider()
                DDActionButton("Show all controls") {
                    safe { DDPrefs.showAllElements(activity) }
                    changed()
                }
            }
        }

        DDExpandable("Button mapping", showMapping, { showMapping = !showMapping }) {
            val targetLabels = DDPrefs.TARGET_NAMES.toList()
            DDPrefs.MAPPABLE_IDS.forEachIndexed { i, id ->
                DDDivider()
                val targetId = s.mapping[id] ?: id
                val ti = DDPrefs.TARGET_IDS.indexOf(targetId)
                DDChoiceRow(
                    DDPrefs.MAPPABLE_NAMES[i],
                    if (ti >= 0) DDPrefs.TARGET_NAMES[ti] else targetId,
                    targetLabels
                ) { picked ->
                    val idx = targetLabels.indexOf(picked)
                    if (idx >= 0) {
                        safe { DDPrefs.setTarget(activity, id, DDPrefs.TARGET_IDS[idx]) }
                        changed()
                    }
                }
            }
            DDDivider()
            Text(
                "Steam Deck buttons \u2192 gamepad (evdev)",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // tanpa hidraw, "Hidraw only" tidak bermakna: tombol tanpa pemetaan tidak mengirim apa pun ke evdev
            val deckLabels = DDPrefs.DECK_TARGET_NAMES.toList().mapIndexed { i, n -> if (i == 0 && !deckWanted) "None" else n }
            DDPrefs.DECK_IDS.forEachIndexed { i, id ->
                DDDivider()
                val cur = DDPrefs.deckTarget(activity, id)
                val ci = DDPrefs.DECK_TARGET_IDS.indexOf(cur)
                DDChoiceRow(
                    DDPrefs.DECK_NAMES[i],
                    if (ci >= 0) deckLabels[ci] else cur,
                    deckLabels
                ) { picked ->
                    val idx = deckLabels.indexOf(picked)
                    if (idx >= 0) {
                        safe { DDPrefs.setDeckTarget(activity, id, DDPrefs.DECK_TARGET_IDS[idx]) }
                        changed()
                    }
                }
            }
        }

        DDGroup {
            val padLabels = DDPrefs.PAD_MODE_NAMES.toList()
            val padIdx = DDPrefs.PAD_MODE_IDS.indexOf(DDPrefs.padMode(activity))
            DDChoiceRow(
                "Right trackpad",
                if (padIdx >= 0) DDPrefs.PAD_MODE_NAMES[padIdx] else padLabels[0],
                padLabels
            ) { picked ->
                val idx = padLabels.indexOf(picked)
                if (idx >= 0) {
                    safe { DDPrefs.setPadMode(activity, DDPrefs.PAD_MODE_IDS[idx]) }
                    changed()
                }
            }
            DDDivider()
            val gyroLabels = DDPrefs.GYRO_NAMES.toList()
            val gyroId = DDPrefs.gyroMode(activity)
            val gyroIdx = DDPrefs.GYRO_IDS.indexOf(gyroId)
            DDChoiceRow(
                "Gyro \u2192 right stick",
                if (gyroIdx >= 0) DDPrefs.GYRO_NAMES[gyroIdx] else gyroLabels[0],
                gyroLabels
            ) { picked ->
                val idx = gyroLabels.indexOf(picked)
                if (idx >= 0) {
                    safe { DDPrefs.setGyroMode(activity, DDPrefs.GYRO_IDS[idx]) }
                    changed()
                }
            }
            if (gyroId != "off") {
                DDDivider()
                DDSliderRow(
                    "Gyro sensitivity",
                    DDPrefs.gyroSens(activity),
                    DDPrefs.GYRO_SENS_MIN,
                    DDPrefs.GYRO_SENS_MAX,
                    DDPrefs.GYRO_SENS_STEP
                ) { v ->
                    safe { DDPrefs.setGyroSens(activity, v) }
                    changed()
                }
            }
        }

        DDActionButton("Controller Test") { safe { DDController.openControllerTest() } }  // DroidDeck-bp
        DDActionButton("Edit layout") { safe { DDController.panelEditLayout() } }
        DDActionButton("Reset layout") { safe { DDController.panelResetLayout() }; changed() }
        DDActionButton("Reset button mapping") { safe { DDController.panelResetMapping() }; changed() }
        DDActionButton("Reset everything") { safe { DDController.panelResetAll() }; changed() }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DDGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun DDDivider() {
    HorizontalDivider(color = dividerColor())
}

@Composable
private fun DDSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, null)
    }
}

@Composable
private fun DDSliderRow(label: String, value: Int, min: Int, max: Int, step: Int, onDone: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                current.toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val steps = ((max - min) / step - 1).coerceAtLeast(0)
        Slider(
            current.coerceIn(min.toFloat(), max.toFloat()),
            { current = it },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            onValueChangeFinished = { onDone(current.toInt()) }
        )
    }
}

@Composable
private fun DDChoiceRow(label: String, current: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), shape = WinZShapes.Small) {
                Text(current, Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        expanded = false
                        if (option != current) onPick(option)
                    })
                }
            }
        }
    }
}

@Composable
private fun DDExpandable(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DDGroup {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
        }
        if (expanded) content()
    }
}

@Composable
private fun DDActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth(), shape = WinZShapes.Medium) { Text(label) }
}
