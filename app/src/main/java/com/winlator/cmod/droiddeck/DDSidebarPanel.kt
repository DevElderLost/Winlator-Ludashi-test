package com.winlator.cmod.droiddeck

// DD-SIDEBAR-FIX: versi aman (tema ringan, lazy attach, fallback, try/catch)

import android.app.Activity
import android.graphics.Color as AColor
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingSlider
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.winlatorColorScheme

private const val TAG = "DDSidebarPanel"
private const val ATTACHED_TAG = "dd_sidebar_panel_attached"

// DD-SIDEBAR-MEASURE: ukuran cadangan bila View induk memberi constraint tak-terbatas
// (pass pengukuran LinearLayout berbobot / ScrollView). Hanya dipakai pada pass itu.
private val PROBE_WIDTH = 320.dp
private val PROBE_HEIGHT = 480.dp

/** Jalankan aksi tanpa pernah melempar exception ke UI thread. */
private inline fun safe(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "Aksi panel DroidDeck gagal", t)
    }
}

/**
 * Panel pengaturan DroidDeck INLINE di left sidebar.
 * Isi Compose dibuat LAZY saat panel pertama kali tampil dan dijaga agar
 * kegagalan apa pun tidak membuat aplikasi crash (fallback ke tombol dialog).
 */
object DDSidebarPanel {
    private val revision = mutableIntStateOf(0)

    /** Daftarkan kontainer LLSubDD. Ringan: belum membuat ComposeView. */
    @JvmStatic
    fun attach(activity: Activity, container: FrameLayout) {
        if (container.tag == ATTACHED_TAG) return
        container.tag = ATTACHED_TAG

        var wasVisible = false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = container.visibility == View.VISIBLE
            if (visible && !wasVisible) {
                if (container.childCount == 0) {
                    container.post { ensureContent(activity, container) }
                } else {
                    bump()
                }
            }
            wasVisible = visible
        }
        val vto = container.viewTreeObserver
        if (vto.isAlive) vto.addOnGlobalLayoutListener(listener)
    }

    /** Paksa komposisi membaca ulang DDPrefs / status kontrol. */
    @JvmStatic
    fun bump() {
        revision.intValue = revision.intValue + 1
    }

    /** Buka panel DD lewat klik item rail. false jika item/panel tidak ada. */
    @JvmStatic
    fun select(activity: Activity): Boolean {
        if (activity.findViewById<View>(R.id.LLSubDD) == null) return false
        val item = activity.findViewById<View>(R.id.BTItemDD) ?: return false
        item.performClick()
        return true
    }

    private fun ensureContent(activity: Activity, container: FrameLayout) {
        if (container.childCount > 0) return
        try {
            check(container.findViewTreeLifecycleOwner() != null) { "ViewTreeLifecycleOwner tidak ada" }
            check(container.findViewTreeSavedStateRegistryOwner() != null) { "ViewTreeSavedStateRegistryOwner tidak ada" }
            val compose = ComposeView(activity).apply {
                setBackgroundColor(AColor.TRANSPARENT)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { SidebarTheme(activity) { DDSidebarContent(activity, revision.intValue) } }
            }
            container.addView(
                compose,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Panel Compose gagal dibuat, pakai tombol dialog", t)
            showFallback(activity, container)
        }
    }

    private fun showFallback(activity: Activity, container: FrameLayout) {
        try {
            container.removeAllViews()
            val d = activity.resources.displayMetrics.density
            val button = Button(activity).apply {
                text = "DroidDeck settings"
                setOnClickListener { safe { DDController.panelOpenDialog() } }
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
            lp.topMargin = (8 * d).toInt()
            container.addView(button, lp)
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback panel gagal", t)
        }
    }
}

/**
 * Tema ringan untuk panel: hanya skema warna Winlator. TIDAK menyentuh window,
 * system bars, DrawerLayout, atau fokus (berbeda dengan WinZTheme).
 */
@Composable
private fun SidebarTheme(activity: Activity, content: @Composable () -> Unit) {
    val colors = winlatorColorScheme(WinlatorThemeManager.currentTheme(activity))
    MaterialTheme(colorScheme = colors) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

@Composable
private fun DDSidebarContent(activity: Activity, rev: Int) {
    val s = remember(rev) { runCatching { DDPrefs.read(activity) }.getOrNull() }
    val enabled = remember(rev) { runCatching { DDController.panelControlsEnabled() }.getOrDefault(false) }
    val keyboard = remember(rev) { runCatching { DDController.panelKeyboardShown() }.getOrDefault(false) }
    var showMapping by remember { mutableStateOf(false) }

    if (s == null) {
        Text(
            "DroidDeck: gagal membaca pengaturan",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
        return
    }

    fun changed() {
        safe { DDController.panelRefreshControls() }
        DDSidebarPanel.bump()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // DD-SIDEBAR-MEASURE: horizontalScroll (baris Tint) dan verticalScroll melempar
        // IllegalStateException jika constraint tak-terbatas. Jaga LEBAR dan TINGGI.
        val widthMod = if (constraints.hasBoundedWidth) Modifier.fillMaxWidth() else Modifier.width(PROBE_WIDTH)
        // DD-SIDEBAR-NATURAL: tinggi tak-terbatas -> tinggi alami, di-scroll oleh SidebarScrollView
        // (seperti panel sidebar lain). Tinggi terbatas -> scroll internal.
        val scrollState = rememberScrollState()
        val heightMod = if (constraints.hasBoundedHeight) Modifier.fillMaxHeight().verticalScroll(scrollState) else Modifier
        Column(
            modifier = widthMod.then(heightMod),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "DroidDeck",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            SettingsCard {
                SettingToggle("DroidDeck controls", enabled) { on ->
                    safe { DDController.panelSetControlsEnabled(on) }
                    changed()
                }
                SettingsDivider()
                SettingToggle("PC keyboard", keyboard) { on ->
                    safe { DDController.panelSetKeyboard(on) }
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
                SettingsDivider()
                SettingSlider("Opacity", s.opacity, DDPrefs.OPACITY_MIN, DDPrefs.OPACITY_MAX, DDPrefs.OPACITY_STEP) { v ->
                    safe { DDPrefs.setOpacity(activity, v) }
                    changed()
                }
                SettingsDivider()
                SettingSlider("Size", s.size, DDPrefs.SIZE_MIN, DDPrefs.SIZE_MAX, DDPrefs.SIZE_STEP) { v ->
                    safe { DDPrefs.setSize(activity, v) }
                    changed()
                }
            }

            SettingsCard {
                SettingToggle("Double-tap stick = L3/R3 click", s.stickClick) { on ->
                    safe { DDPrefs.setStickClick(activity, on) }
                    changed()
                }
                SettingsDivider()
                SettingToggle("Adaptive sticks (appear under finger)", s.adaptiveSticks) { on ->
                    safe { DDPrefs.setAdaptiveSticks(activity, on) }
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
                                safe { DDPrefs.setTarget(activity, id, DDPrefs.TARGET_IDS[idx]) }
                                changed()
                            }
                        }
                    }
                }
            }

            SettingsCard {
                DDActionRow("Controller Test") { safe { DDController.openControllerTest() } }  // DroidDeck-bp
                SettingsDivider()
                DDActionRow("Edit layout") { safe { DDController.panelEditLayout() } }
                SettingsDivider()
                DDActionRow("Reset layout") { safe { DDController.panelResetLayout() }; DDSidebarPanel.bump() }
                SettingsDivider()
                DDActionRow("Reset button mapping") { safe { DDController.panelResetMapping() }; DDSidebarPanel.bump() }
                SettingsDivider()
                DDActionRow("Reset everything") { safe { DDController.panelResetAll() }; DDSidebarPanel.bump() }
            }

            Spacer(Modifier.height(12.dp))
        }
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
