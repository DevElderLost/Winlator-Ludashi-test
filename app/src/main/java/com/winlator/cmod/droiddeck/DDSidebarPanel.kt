package com.winlator.cmod.droiddeck

import android.app.Activity
import android.graphics.Color as AColor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.theme.WinZTheme

/**
 * Panel pengaturan DroidDeck INLINE di left sidebar (kolom panel, di samping rail),
 * sama seperti panel Controls/Rendering. Nilai disimpan lewat DDPrefs dan langsung
 * diterapkan ke kontrol lewat DDController.panelRefreshControls().
 */
object DDSidebarPanel {
    private val revision = mutableIntStateOf(0)

    /** Tempel isi Compose ke kontainer LLSubDD (sekali saja). */
    @JvmStatic
    fun attach(activity: Activity, container: FrameLayout) {
        if (container.childCount > 0) return
        val compose = ComposeView(activity).apply {
            setBackgroundColor(AColor.TRANSPARENT)
            setContent { WinZTheme { DDSidebarContent(activity, revision.intValue) } }
        }
        container.addView(
            compose,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Baca ulang nilai setiap kali panel berpindah dari tersembunyi ke tampil.
        var wasVisible = false
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = container.visibility == View.VISIBLE
            if (visible && !wasVisible) bump()
            wasVisible = visible
        }
    }

    /** Paksa komposisi membaca ulang DDPrefs / status kontrol. */
    @JvmStatic
    fun bump() {
        revision.intValue = revision.intValue + 1
    }

    /** Buka panel DD lewat klik item rail. false jika panel Compose belum terpasang. */
    @JvmStatic
    fun select(activity: Activity): Boolean {
        val panel = activity.findViewById<View>(R.id.LLSubDD) as? ViewGroup ?: return false
        if (panel.childCount == 0) return false
        val item = activity.findViewById<View>(R.id.BTItemDD) ?: return false
        item.performClick()
        return true
    }
}

@Composable
private fun DDSidebarContent(activity: Activity, rev: Int) {
    val s = remember(rev) { DDPrefs.read(activity) }
    val enabled = remember(rev) { DDController.panelControlsEnabled() }
    val keyboard = remember(rev) { DDController.panelKeyboardShown() }
    var showMapping by remember { mutableStateOf(false) }

    fun changed() {
        DDController.panelRefreshControls()
        DDSidebarPanel.bump()
    }

    val opacityLabels = DDPrefs.OPACITIES.map { "$it%" }
    val sizeLabels = DDPrefs.SIZES.map { "$it%" }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "DroidDeck",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        SettingsCard {
            SettingToggle("DroidDeck controls", enabled) { on ->
                DDController.panelSetControlsEnabled(on)
                changed()
            }
            SettingsDivider()
            SettingToggle("PC keyboard", keyboard) { on ->
                DDController.panelSetKeyboard(on)
                changed()
            }
        }

        SettingsCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tintIndex = DDPrefs.TINTS.indexOf(s.tint)
                Text("Tint", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DDPrefs.TINTS.forEach { argb ->
                        val selected = argb == s.tint
                        Box(
                            modifier = Modifier
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
                                    DDPrefs.setTint(activity, argb)
                                    changed()
                                }
                        )
                    }
                }
                Text(
                    if (tintIndex >= 0) DDPrefs.TINT_NAMES[tintIndex] else "Custom",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingsDivider()
            SettingChoice("Opacity", "${s.opacity}%", opacityLabels) { picked ->
                val i = opacityLabels.indexOf(picked)
                if (i >= 0) {
                    DDPrefs.setOpacity(activity, DDPrefs.OPACITIES[i])
                    changed()
                }
            }
            SettingsDivider()
            SettingChoice("Size", "${s.size}%", sizeLabels) { picked ->
                val i = sizeLabels.indexOf(picked)
                if (i >= 0) {
                    DDPrefs.setSize(activity, DDPrefs.SIZES[i])
                    changed()
                }
            }
        }

        SettingsCard {
            SettingToggle("Double-tap stick = L3/R3 click", s.stickClick) { on ->
                DDPrefs.setStickClick(activity, on)
                changed()
            }
            SettingsDivider()
            SettingToggle("Adaptive sticks (appear under finger)", s.adaptiveSticks) { on ->
                DDPrefs.setAdaptiveSticks(activity, on)
                changed()
            }
        }

        SettingsCard {
            Surface(
                onClick = { showMapping = !showMapping },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Button mapping",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (showMapping) "Hide" else "Show",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (showMapping) {
                val targetLabels = DDPrefs.TARGET_NAMES.toList()
                DDPrefs.MAPPABLE_IDS.forEachIndexed { i, id ->
                    SettingsDivider()
                    val targetId = s.mapping[id] ?: id
                    val ti = DDPrefs.TARGET_IDS.indexOf(targetId)
                    SettingChoice(
                        DDPrefs.MAPPABLE_NAMES[i],
                        if (ti >= 0) DDPrefs.TARGET_NAMES[ti] else targetId,
                        targetLabels
                    ) { picked ->
                        val idx = targetLabels.indexOf(picked)
                        if (idx >= 0) {
                            DDPrefs.setTarget(activity, id, DDPrefs.TARGET_IDS[idx])
                            changed()
                        }
                    }
                }
            }
        }

        SettingsCard {
            DDActionRow("Edit layout") { DDController.panelEditLayout() }
            SettingsDivider()
            DDActionRow("Reset layout") { DDController.panelResetLayout(); DDSidebarPanel.bump() }
            SettingsDivider()
            DDActionRow("Reset button mapping") { DDController.panelResetMapping(); DDSidebarPanel.bump() }
            SettingsDivider()
            DDActionRow("Reset everything") { DDController.panelResetAll(); DDSidebarPanel.bump() }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DDActionRow(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
