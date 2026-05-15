package com.winlator.cmod.contentdialog;



import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.ContainerDetailFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.winhandler.WinHandler;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ShortcutSettingsDialog extends ContentDialog {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;
    private TextView tvGraphicsDriverVersion;
    private String box64Version;
    private CheckBox cbNativeRendering;


    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        // Initialize the ContentsManager
        ContainerManager containerManager = shortcut.container.getManager();

//        if (containerManager != null) {
//            this.contentsManager = new ContentsManager(containerManager.getContext());
//            this.contentsManager.syncTurnipContents();
//        } else {
//            Toast.makeText(fragment.getContext(), "Failed to initialize container manager. Please try again.", Toast.LENGTH_SHORT).show();
//            return;
//        }

        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);

        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);

        // Initialize the turnip version TextView
        tvGraphicsDriverVersion = findViewById(R.id.TVGraphicsDriverVersion);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(shortcut.container.id);
//        containerDetailFragment.loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()));

        loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()), isDarkMode);


        final Spinner sGraphicsDriver = findViewById(R.id.SGraphicsDriver);
        
        final Spinner sDXWrapper = findViewById(R.id.SDXWrapper);

        final Spinner sBox64Version = findViewById(R.id.SBox64Version);
        
        ContentsManager contentsManager = new ContentsManager(context);
        
        contentsManager.syncContents();

        final View vGraphicsDriverConfig = findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
        
        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()));

        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver()),
            shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper()));

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));
        final Spinner sEmulator = findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, shortcut.getExtra("emulator", shortcut.container.getEmulator()));
        final Spinner sEmulator64 = findViewById(R.id.SEmulator64);
        sEmulator64.setEnabled(false);
        final Spinner sMIDISoundFont = findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()));

        final EditText etLC_ALL = findViewById(R.id.ETlcall);
        etLC_ALL.setText(shortcut.getExtra("lc_all", shortcut.container.getLC_ALL()));

        final View btShowLCALL = findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = context.getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        FrameLayout fexcoreFL = findViewById(R.id.fexcoreFrame);
        String wineVersion = shortcut.container.getWineVersion();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.isArm64EC()) {
            fexcoreFL.setVisibility(View.VISIBLE);
            sEmulator.setEnabled(true);
            sEmulator64.setSelection(0);
        }
        else {
            fexcoreFL.setVisibility(View.GONE);
            sEmulator.setEnabled(false);
            sEmulator.setSelection(1);
            sEmulator64.setSelection(1);
        }

        ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
        loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());

        // Add this part to set the initial spinner selection based on the shortcut
        String currentBox64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
        if (currentBox64Version != null) {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, currentBox64Version);
        } else {
            // Default selection or use a preferred default version
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, wineInfo.isArm64EC() ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
        }

        // Set OnItemSelectedListener for the Box64 version spinner
        sBox64Version.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = parent.getItemAtPosition(position).toString();
                box64Version = selectedVersion;  // Update the class-level variable
                // Update the shortcut extra immediately, or wait until saveData() is called
                shortcut.putExtra("box64Version", selectedVersion);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // This method must be implemented, even if it's empty.
                // Optional: You can handle the case where no item is selected, if needed.
            }
        });

        final CheckBox cbForceFullscreen =  findViewById(R.id.CBForceFullscreen);
        boolean forceFullscreen = shortcut.getExtra("forceFullscreen", "0").equals("1");
        cbForceFullscreen.setChecked(forceFullscreen);

        // LSFG render params — enable/disable global dan DLL path dikelola di
        // SettingsFragment (SharedPreferences). Di sini hanya parameter render
        // per-shortcut yang dimuat.

        // ── Multiplier: 3 CheckBox horizontal (2x / 3x / 4x) ────────────────
        final LinearLayout llLsfgMultiplier = findViewById(R.id.LLLsfgMultiplier);
        final int[] multiplierValues = {2, 3, 4};
        final String[] multiplierLabels = {"2x", "3x", "4x"};
        final CheckBox[] cbMultiplier = new CheckBox[multiplierValues.length];
        {
            int savedMultiplier = Math.max(2, Math.min(4,
                    Integer.parseInt(shortcut.getExtra("lsfgMultiplier", "2"))));
            llLsfgMultiplier.removeAllViews();
            for (int i = 0; i < multiplierValues.length; i++) {
                CheckBox cb = new CheckBox(context);
                cb.setText(multiplierLabels[i]);
                cb.setChecked(multiplierValues[i] == savedMultiplier);
                final int idx = i;
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        for (int j = 0; j < cbMultiplier.length; j++) {
                            if (j != idx && cbMultiplier[j] != null)
                                cbMultiplier[j].setChecked(false);
                        }
                    } else {
                        boolean anyChecked = false;
                        for (CheckBox c : cbMultiplier) {
                            if (c != null && c.isChecked()) { anyChecked = true; break; }
                        }
                        if (!anyChecked) cb.setChecked(true);
                    }
                });
                cbMultiplier[i] = cb;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd((int)(8 * context.getResources().getDisplayMetrics().density));
                llLsfgMultiplier.addView(cb, lp);
            }
        }

        // ── Present Mode: Spinner ───────────────────────────────────────────
        final Spinner sLsfgPresentMode = findViewById(R.id.SLsfgPresentMode);
        {
            String[] presentModeEntries = {"FIFO", "Mailbox", "Immediate"};
            sLsfgPresentMode.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_dropdown_item, presentModeEntries));
            String savedPresentMode = shortcut.getExtra("lsfgPresentMode", "fifo")
                    .toLowerCase(java.util.Locale.ROOT);
            int presentModeIdx;
            switch (savedPresentMode) {
                case "mailbox":   presentModeIdx = 1; break;
                case "immediate": presentModeIdx = 2; break;
                default:          presentModeIdx = 0; break;
            }
            sLsfgPresentMode.setSelection(presentModeIdx, false);
        }

        // ── Pacing Mode: Spinner ─────────────────────────────────────────────
        final Spinner sLsfgPacingMode = findViewById(R.id.SLsfgPacingMode);
        {
            String[] pacingModeEntries = {"Disabled", "None", "Sleep", "Busy Wait"};
            sLsfgPacingMode.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_dropdown_item, pacingModeEntries));
            String savedPacingMode = shortcut.getExtra("lsfgPacingMode", "disabled")
                    .toLowerCase(java.util.Locale.ROOT);
            int pacingModeIdx;
            switch (savedPacingMode) {
                case "none":      pacingModeIdx = 1; break;
                case "sleep":     pacingModeIdx = 2; break;
                case "busy_wait": pacingModeIdx = 3; break;
                default:          pacingModeIdx = 0; break;
            }
            sLsfgPacingMode.setSelection(pacingModeIdx, false);
        }

        // ── Flow Scale: SeekBar ───────────────────────────────────────────────
        final SeekBar sbLsfgFlowScale = findViewById(R.id.SBLsfgFlowScale);
        final TextView tvLsfgFlowScale = findViewById(R.id.TVLsfgFlowScale);
        {
            float saved = 0.80f;
            try { saved = Float.parseFloat(shortcut.getExtra("lsfgFlowScale", "0.80")); }
            catch (Exception ignored) {}
            saved = Math.max(0.25f, Math.min(1.00f, saved));
            int progress = Math.round((saved - 0.25f) * 100f);
            sbLsfgFlowScale.setProgress(progress);
            tvLsfgFlowScale.setText(String.format(java.util.Locale.US, "%.2f", saved));
        }
        sbLsfgFlowScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.25f + (progress / 100f);
                tvLsfgFlowScale.setText(String.format(java.util.Locale.US, "%.2f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        final CheckBox cbLsfgPerformanceMode = findViewById(R.id.CBLsfgPerformanceMode);
        cbLsfgPerformanceMode.setChecked(shortcut.getExtra("lsfgPerformanceMode", "1").equals("1"));

        final CheckBox cbLsfgHdrMode = findViewById(R.id.CBLsfgHdrMode);
        cbLsfgHdrMode.setChecked(shortcut.getExtra("lsfgHdrMode", "0").equals("1"));

        // Native Rendering (Direct Rendering+) checkbox.
        int cbNativeRenderingId = context.getResources().getIdentifier(
                "CBNativeRendering", "id", context.getPackageName());
        cbNativeRendering = cbNativeRenderingId != 0
                ? findViewById(cbNativeRenderingId)
                : null;
        if (cbNativeRendering != null) {
            cbNativeRendering.setChecked(shortcut.getExtra("nativeRendering", "0").equals("1"));
        }

        // ── HUD Overlay: TVHudElements → AlertDialog multi-checkbox ──────────
        // Elemen HUD sesuai FrameRating.toggleElement():
        //   0=FPS, 1=Renderer/API, 2=GPU, 3=CPU/RAM, 4=Battery/Temp, 5=Graph
        // Disimpan sebagai shortcut extra "hudElements" berformat bitmask integer
        // mis. 0b000111 = 7 → FPS+Renderer+GPU aktif.
        // Nilai 0 = HUD tidak tampil sama sekali (disabled).
        final String[] hudElementLabels = {
            context.getString(R.string.hud_element_fps),
            context.getString(R.string.hud_element_renderer),
            context.getString(R.string.hud_element_gpu),
            context.getString(R.string.hud_element_cpu_ram),
            context.getString(R.string.hud_element_batt_temp),
            context.getString(R.string.hud_element_graph),
        };
        final int HUD_ELEMENT_COUNT = hudElementLabels.length;

        // Load bitmask dari shortcut extra; default 0 = HUD tidak tampil
        final boolean[] hudChecked = new boolean[HUD_ELEMENT_COUNT];
        {
            int savedMask = 0;
            try { savedMask = Integer.parseInt(shortcut.getExtra("hudElements", "0")); }
            catch (Exception ignored) {}
            for (int i = 0; i < HUD_ELEMENT_COUNT; i++) {
                hudChecked[i] = (savedMask & (1 << i)) != 0;
            }
        }

        final TextView tvHudElements = findViewById(R.id.TVHudElements);

        // Helper: update summary text di TextView
        final Runnable updateHudSummary = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HUD_ELEMENT_COUNT; i++) {
                if (hudChecked[i]) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(hudElementLabels[i]);
                }
            }
            tvHudElements.setText(sb.length() > 0
                    ? sb.toString()
                    : context.getString(R.string.hud_elements_none));
        };
        updateHudSummary.run();

        // Klik → buka AlertDialog multi-checkbox
        tvHudElements.setOnClickListener(v -> {
            boolean[] dialogChecked = hudChecked.clone();
            new android.app.AlertDialog.Builder(context)
                    .setTitle(R.string.hud_overlay)
                    .setMultiChoiceItems(hudElementLabels, dialogChecked,
                            (dialog, which, isChecked) -> dialogChecked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        System.arraycopy(dialogChecked, 0, hudChecked, 0, HUD_ELEMENT_COUNT);
                        updateHudSummary.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        final Runnable showInputWarning = () -> ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = findViewById(R.id.CBEnableDInput);
        final View llDInputType = findViewById(R.id.LLDinputMapperType);
        final View btHelpXInput = findViewById(R.id.BTXInputHelp);
        final View btHelpDInput = findViewById(R.id.BTDInputHelp);
        Spinner SDInputType = findViewById(R.id.SDInputType);
        int inputType = Integer.parseInt(shortcut.getExtra("inputType", String.valueOf(shortcut.container.getInputType())));


        cbEnableXInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) == WinHandler.FLAG_INPUT_TYPE_XINPUT);
        cbEnableDInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT);
        cbEnableDInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llDInputType.setVisibility(isChecked?View.VISIBLE:View.GONE);
            if (isChecked && cbEnableXInput.isChecked())
                showInputWarning.run();
        });
        cbEnableXInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && cbEnableDInput.isChecked())
                showInputWarning.run();
        });
        btHelpXInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_xinput));
        btHelpDInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_dinput));
        SDInputType.setSelection(((inputType & WinHandler.FLAG_DINPUT_MAPPER_STANDARD) == WinHandler.FLAG_DINPUT_MAPPER_STANDARD) ? 0 : 1);
        llDInputType.setVisibility(cbEnableDInput.isChecked()?View.VISIBLE:View.GONE);

        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box64PresetManager.loadSpinner("box64", sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        final Spinner sFEXCoreVersion = findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()));

        final Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset()));

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final CheckBox cbDisabledXInput = findViewById(R.id.CBDisabledXInput);
        boolean isXInputDisabled = shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);

        final CheckBox cbSimTouchScreen = findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1") ? true : false);

        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(),
                shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()), isDarkMode);

        final EnvVarsView envVarsView = createEnvVarsTab();

        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);

        TabLayout tabLayout = findViewById(R.id.TabLayout);

        if (isDarkMode) {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background);
        }

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs + " " + value : value);
                return true;
            });
            popupMenu.show();
        });

        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);

        final Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        sStartupSelection.setSelection(Integer.parseInt(shortcut.getExtra("startupSelection", String.valueOf(shortcut.container.getStartupSelection()))));

        final Spinner sSharpnessEffect = findViewById(R.id.SSharpnessEffect);
        final SeekBar sbSharpnessLevel = findViewById(R.id.SBSharpnessLevel);
        final SeekBar sbSharpnessDenoise = findViewById(R.id.SBSharpnessDenoise);
        final TextView tvSharpnessLevel = findViewById(R.id.TVSharpnessLevel);
        final TextView tvSharpnessDenoise = findViewById(R.id.TVSharpnessDenoise);

        AppUtils.setSpinnerSelectionFromValue(sSharpnessEffect, shortcut.getExtra("sharpnessEffect", "None"));

        sbSharpnessLevel.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessLevel", "100")));
        tvSharpnessLevel.setText(shortcut.getExtra("sharpnessLevel", "100") + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessLevel.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        final CPUListView cpuListView = findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)));

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            boolean nameChanged = !shortcut.name.equals(name) && !name.isEmpty();

            if (nameChanged) {
                renameShortcut(name);
            }

            boolean renamingSuccess = !nameChanged || new File(shortcut.file.getParent(), name + ".desktop").exists();

            if (renamingSuccess) {
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String lc_all = etLC_ALL.getText().toString();
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String screenSize = containerDetailFragment.getScreenSize(getContentView());

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;
                finalInputType |= SDInputType.getSelectedItemPosition() == 0 ?  WinHandler.FLAG_DINPUT_MAPPER_STANDARD : WinHandler.FLAG_DINPUT_MAPPER_XINPUT;

                shortcut.putExtra("inputType", String.valueOf(finalInputType));

                boolean disabledXInput = cbDisabledXInput.isChecked();
                shortcut.putExtra("disableXinput", disabledXInput ? "1" : null);

                boolean touchscreenMode = cbSimTouchScreen.isChecked();
                shortcut.putExtra("simTouchScreen", touchscreenMode ? "1" : "0");

                String execArgs = etExecArgs.getText().toString();
                shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
                shortcut.putExtra("screenSize", screenSize);
                shortcut.putExtra("graphicsDriver", graphicsDriver);
                shortcut.putExtra("graphicsDriverConfig", graphicsDriverConfig);
                shortcut.putExtra("dxwrapper", dxwrapper);
                shortcut.putExtra("dxwrapperConfig", dxwrapperConfig);
                shortcut.putExtra("audioDriver", audioDriver);
                shortcut.putExtra("emulator", emulator);
                shortcut.putExtra("midiSoundFont", midiSoundFont);
                shortcut.putExtra("lc_all", lc_all);

                shortcut.putExtra("forceFullscreen", cbForceFullscreen.isChecked() ? "1" : null);

                // Save Native Rendering (Direct Rendering+) state
                if (cbNativeRendering != null) {
                    shortcut.putExtra("nativeRendering", cbNativeRendering.isChecked() ? "1" : "0");
                }

                String wincomponents = containerDetailFragment.getWinComponents(getContentView());
                shortcut.putExtra("wincomponents", wincomponents);

                String envVars = envVarsView.getEnvVars();
                shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

                String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
                shortcut.putExtra("fexcoreVersion", fexcoreVersion);

                String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
                shortcut.putExtra("fexcorePreset", fexcorePreset);

                String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
                shortcut.putExtra("box64Preset", box64Preset);

                byte startupSelection = (byte)sStartupSelection.getSelectedItemPosition();
                shortcut.putExtra("startupSelection", String.valueOf(startupSelection));

                String sharpeningEffect = sSharpnessEffect.getSelectedItem().toString();
                String sharpeningLevel = String.valueOf(sbSharpnessLevel.getProgress());
                String sharpeningDenoise = String.valueOf(sbSharpnessDenoise.getProgress());
                shortcut.putExtra("sharpnessEffect", sharpeningEffect);
                shortcut.putExtra("sharpnessLevel", sharpeningLevel);
                shortcut.putExtra("sharpnessDenoise", sharpeningDenoise);

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
                int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition() - 1).id : 0;
                shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);

                String cpuList = cpuListView.getCheckedCPUListAsString();
                shortcut.putExtra("cpuList", cpuList);

                // Save LSFG render params per-shortcut.
                int lsfgMultiplier = 2;
                for (int i = 0; i < cbMultiplier.length; i++) {
                    if (cbMultiplier[i] != null && cbMultiplier[i].isChecked()) {
                        lsfgMultiplier = multiplierValues[i];
                        break;
                    }
                }
                shortcut.putExtra("lsfgMultiplier", String.valueOf(lsfgMultiplier));

                float lsfgFlowScale = 0.25f + (sbLsfgFlowScale.getProgress() / 100f);
                shortcut.putExtra("lsfgFlowScale", String.format(java.util.Locale.US, "%.2f", lsfgFlowScale));

                shortcut.putExtra("lsfgPerformanceMode", cbLsfgPerformanceMode.isChecked() ? "1" : "0");
                shortcut.putExtra("lsfgHdrMode", cbLsfgHdrMode.isChecked() ? "1" : "0");

                String lsfgPresentMode;
                switch (sLsfgPresentMode.getSelectedItemPosition()) {
                    case 1:  lsfgPresentMode = "mailbox";   break;
                    case 2:  lsfgPresentMode = "immediate"; break;
                    default: lsfgPresentMode = "fifo";      break;
                }
                shortcut.putExtra("lsfgPresentMode", lsfgPresentMode);

                String lsfgPacingMode;
                switch (sLsfgPacingMode.getSelectedItemPosition()) {
                    case 1:  lsfgPacingMode = "none";      break;
                    case 2:  lsfgPacingMode = "sleep";     break;
                    case 3:  lsfgPacingMode = "busy_wait"; break;
                    default: lsfgPacingMode = "disabled";  break;
                }
                shortcut.putExtra("lsfgPacingMode", lsfgPacingMode);

                // Simpan HUD elements bitmask dari array hudChecked
                // 0=FPS, 1=Renderer/API, 2=GPU, 3=CPU/RAM, 4=Battery/Temp, 5=Graph
                int hudMask = 0;
                for (int i = 0; i < HUD_ELEMENT_COUNT; i++) {
                    if (hudChecked[i]) hudMask |= (1 << i);
                }
                // Simpan bitmask; hapus key jika 0 (HUD dinonaktifkan)
                shortcut.putExtra("hudElements", hudMask > 0 ? String.valueOf(hudMask) : null);

                // Save all changes to the shortcut
                shortcut.saveData();
            }
        });
    }

    // Utility method to apply styles to dynamically added TextViews based on their content
    private void applyFieldSetLabelStylesDynamically(ViewGroup rootView, boolean isDarkMode) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View child = rootView.getChildAt(i);
            if (child instanceof ViewGroup) {
                applyFieldSetLabelStylesDynamically((ViewGroup) child, isDarkMode);
            } else if (child instanceof TextView) {
                TextView textView = (TextView) child;
                if (isFieldSetLabel(textView.getText().toString())) {
                    applyFieldSetLabelStyle(textView, isDarkMode);
                }
            }
        }
    }

    private boolean isFieldSetLabel(String text) {
        return text.equalsIgnoreCase("DirectX") ||
                text.equalsIgnoreCase("General") ||
                text.equalsIgnoreCase("Box64") ||
                text.equalsIgnoreCase("Input Controls") ||
                text.equalsIgnoreCase("Game Controller") ||
                text.equalsIgnoreCase("System");
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        ViewGroup llContent = findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }


    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);

        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenHeight), isDarkMode);


        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {

        EditText etName = view.findViewById(R.id.ETName);
        applyDarkThemeToEditText(etName, isDarkMode);

        Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        Spinner sEmulatorSpinner = view.findViewById(R.id.SEmulator);
        Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Spinner sControlsProfile = view.findViewById(R.id.SControlsProfile);
        Spinner sDInputType = view.findViewById(R.id.SDInputType);
        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);
        Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        Spinner sLsfgPresentModeStyle = view.findViewById(R.id.SLsfgPresentMode);
        Spinner sLsfgPacingModeStyle  = view.findViewById(R.id.SLsfgPacingMode);
        

        sGraphicsDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sDXWrapper.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sAudioDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sEmulatorSpinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Preset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sControlsProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sDInputType.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sMIDISoundFont.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Version.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCorePreset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCoreVersion.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sStartupSelection.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        if (sLsfgPresentModeStyle != null) sLsfgPresentModeStyle.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        if (sLsfgPacingModeStyle  != null) sLsfgPacingModeStyle.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

//        EditText etLC_ALL = view.findViewById(R.id.ETlcall);
        EditText etExecArgs = view.findViewById(R.id.ETExecArgs);

//        applyDarkThemeToEditText(etLC_ALL, isDarkMode);
        applyDarkThemeToEditText(etExecArgs, isDarkMode);

    }

    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundColor(Color.parseColor("#424242"));
        } else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(Color.WHITE);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(Color.BLACK);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }

    private void updateExtra(String extraName, String containerValue, String newValue) {
        String extraValue = shortcut.getExtra(extraName);
        if (extraValue.isEmpty() && containerValue.equals(newValue))
            return;
        shortcut.putExtra(extraName, newValue);
    }

    private void renameShortcut(String newName) {
        File parent = shortcut.file.getParentFile();
        File oldDesktopFile = shortcut.file;
        File newDesktopFile = new File(parent, newName + ".desktop");

        if (!newDesktopFile.isFile() && oldDesktopFile.renameTo(newDesktopFile)) {
            updateShortcutFileReference(newDesktopFile);
            deleteOldFileIfExists(oldDesktopFile);
        }

        File linkFile = new File(parent, shortcut.name + ".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName + ".lnk");
            if (!newLinkFile.isFile()) linkFile.renameTo(newLinkFile);
        }

        fragment.loadShortcutsList();
        fragment.updateShortcutOnScreen(newName, newName, shortcut.container.id, newDesktopFile.getAbsolutePath(),
                Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
    }

    private void deleteOldFileIfExists(File oldFile) {
        if (oldFile.exists()) {
            if (!oldFile.delete()) {
                Log.e("ShortcutSettingsDialog", "Failed to delete old file: " + oldFile.getPath());
            }
        }
    }

    private void updateShortcutFileReference(File newFile) {
        try {
            Field fileField = Shortcut.class.getDeclaredField("file");
            fileField.setAccessible(true);
            fileField.set(shortcut, newFile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e("ShortcutSettingsDialog", "Error updating shortcut file reference", e);
        }
    }


    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();

        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        envVarsView.setDarkMode(isDarkMode);

        envVarsView.setEnvVars(new EnvVars(shortcut.getExtra("envVars")));

        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) ->
                new AddEnvVarDialog(context, envVarsView).show()
        );

        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }

    private void showInputWarning() {
        final Context context = fragment.getContext();
        ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC)
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.wowbox64_version_entries)));
        else
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.box64_version_entries)));
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }
    
    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);
        
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);
        
        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();

            tvGraphicsDriverVersion.setText(GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig));

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, tvGraphicsDriverVersion).show();
            });

            ArrayList<String> items = new ArrayList<>();
            for (String value : dxwrapperEntries) {
                    items.add(value);
            }
            sDXWrapper.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray(new String[0])));
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }
}
