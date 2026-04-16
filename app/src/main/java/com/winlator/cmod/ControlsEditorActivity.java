package com.winlator.cmod;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.content.Context;
import android.graphics.PorterDuff;

import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.NumberPicker;
import com.winlator.cmod.core.LocaleHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {

    private int pendingExpandSubButtonIndex = -1; // <-- tambahkan ini

    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private boolean isDarkMode;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        isDarkMode = preferences.getBoolean("dark_mode", false);
        setTheme(isDarkMode ? R.style.AppTheme_Dark : R.style.AppTheme);

        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        profile = InputControlsManager.loadProfile(this, 
                ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));

        ((TextView) findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);
        pendingExpandSubButtonIndex = -1;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                if (!inputControlsView.addElement()) {
                    AppUtils.showToast(this, R.string.no_profile_selected);
                }
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) {
                    AppUtils.showToast(this, R.string.no_control_element_selected);
                }
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) {
                    showControlElementSettings(v);
                } else {
                    AppUtils.showToast(this, R.string.no_control_element_selected);
                }
                break;
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();

            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.LLCursorMove).setVisibility(View.GONE);
            view.findViewById(R.id.LLCursorMoveRadius).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLBindings).setVisibility(View.GONE);
            view.findViewById(R.id.LLSlotIcons).setVisibility(View.GONE);
            view.findViewById(R.id.LLMultiButtonOptions).setVisibility(View.GONE);

            if (type == ControlElement.Type.BUTTON) {
                view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                view.findViewById(R.id.CBToggleSwitch).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLBindings).setVisibility(View.VISIBLE);
            }
            else if (type == ControlElement.Type.TOUCHSCREEN_TOGGLE) {
                view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
            }
            else if (type == ControlElement.Type.RANGE_BUTTON) {
                view.findViewById(R.id.LLRangeOptions).setVisibility(View.VISIBLE);
            }
            else if (type == ControlElement.Type.D_PAD) {
                view.findViewById(R.id.LLBindings).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLSlotIcons).setVisibility(View.VISIBLE);
                loadSlotIconsUI(view, element, new String[]{"↑ Up", "→ Right", "↓ Down", "← Left"}, 4);
            }
            else if (type == ControlElement.Type.STICK || type == ControlElement.Type.RIGHT_STICK) {
                view.findViewById(R.id.LLBindings).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLSlotIcons).setVisibility(View.VISIBLE);
                loadSlotIconsUI(view, element, new String[]{"Outer Circle", "Inner Thumb"}, 2);

                if (type == ControlElement.Type.RIGHT_STICK) {
                    view.findViewById(R.id.LLCursorMove).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCursorMoveRadius).setVisibility(
                            element.isCursorMove() ? View.VISIBLE : View.GONE);
                } else {
                    view.findViewById(R.id.LLCursorMove).setVisibility(View.GONE);
                    view.findViewById(R.id.LLCursorMoveRadius).setVisibility(View.GONE);
                }
            }
            else if (type == ControlElement.Type.TRACKPAD) {
                view.findViewById(R.id.LLBindings).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
            }
            else if (type == ControlElement.Type.MENU_NAVIGATION) {
                view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLSlotIcons).setVisibility(View.VISIBLE);
                loadSlotIconsUI(view, element,
                        new String[]{"Main Button", "Keyboard", "Cursor Pos", "Input Controls", "Exit"}, 6);
            }
            else if (type == ControlElement.Type.MULTIPLE_BUTTON) {
                view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLMultiButtonOptions).setVisibility(View.VISIBLE);
                loadMultiButtonUI(view, element);
            }

            loadBindingSpinners(element, view);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
            element.setOrientation((byte) (checkedId == R.id.RBVertical ? 1 : 0));
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            profile.save();
            inputControlsView.invalidate();
        });

        final TextView tvScale = view.findViewById(R.id.TVScale);
        SeekBar sbScale = view.findViewById(R.id.SBScale);
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvScale.setText(progress + "%");
                if (fromUser) {
                    progress = (int) Mathf.roundTo(progress, 5);
                    seekBar.setProgress(progress);
                    element.setScale(progress / 100.0f);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbScale.setProgress((int) (element.getScale() * 100));

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        CheckBox cbCursorMove = view.findViewById(R.id.CBCursorMove);
        cbCursorMove.setChecked(element.isCursorMove());

        final TextView tvCursorRadius = view.findViewById(R.id.TVCursorMoveRadius);
        SeekBar sbCursorRadius = view.findViewById(R.id.SBCursorMoveRadius);
        final int RADIUS_MIN = 50;
        final int RADIUS_MAX = 500;
        sbCursorRadius.setMax(RADIUS_MAX - RADIUS_MIN);
        int initialProgress = Mathf.clamp(element.getCursorMoveRadius(), RADIUS_MIN, RADIUS_MAX) - RADIUS_MIN;
        sbCursorRadius.setProgress(initialProgress);
        tvCursorRadius.setText((initialProgress + RADIUS_MIN) + " px");
        sbCursorRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int radiusPx = progress + RADIUS_MIN;
                tvCursorRadius.setText(radiusPx + " px");
                if (fromUser) {
                    element.setCursorMoveRadius(radiusPx);
                    profile.save();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbCursorMove.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setCursorMove(isChecked);
            profile.save();
            view.findViewById(R.id.LLCursorMoveRadius).setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());

        final LinearLayout llIconList = view.findViewById(R.id.LLIconList);
        loadIcons(llIconList, element.getIconId());

        updateLayout.run();

        PopupWindow popupWindow = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        popupWindow.setOnDismissListener(() -> {
            String text = etCustomText.getText().toString().trim();
            byte iconId = 0;
            for (int i = 0; i < llIconList.getChildCount(); i++) {
                View child = llIconList.getChildAt(i);
                if (child.isSelected()) {
                    iconId = (byte) child.getTag();
                    break;
                }
            }

            element.setText(text);
            element.setIconId(iconId);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void loadSlotIconsUI(View settingsView, ControlElement element,
                                 String[] slotLabels, int slotCount) {
        LinearLayout llSlotIcons = settingsView.findViewById(R.id.LLSlotIcons);
        llSlotIcons.removeAllViews();

        byte[] availableIconIds = loadAllIconIds();

        final byte[] selectedSlots = new byte[slotCount];
        for (int s = 0; s < slotCount; s++) {
            selectedSlots[s] = element.getSlotIconId(s);
        }

        int iconSize    = (int) UnitUtils.dpToPx(40);
        int iconMargin  = (int) UnitUtils.dpToPx(2);
        int iconPadding = (int) UnitUtils.dpToPx(4);
        int badgeSize   = (int) UnitUtils.dpToPx(14);

        TextView tvHint = new TextView(this);
        StringBuilder hint = new StringBuilder("Slot: ");
        for (int s = 0; s < slotCount; s++) {
            if (s > 0) hint.append("  ");
            hint.append(s + 1).append("=").append(slotLabels[s]);
        }
        tvHint.setText(hint.toString());
        tvHint.setTextSize(11);
        tvHint.setPadding(0, 0, 0, (int) UnitUtils.dpToPx(4));
        llSlotIcons.addView(tvHint);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        final List<FrameLayout> iconFrames = new ArrayList<>();

        final Runnable refreshBadges = () -> {
            for (FrameLayout frame : iconFrames) {
                Object tagObj = frame.getTag();
                if (tagObj == null) continue;
                byte fId = (byte) tagObj;

                int slotIndex = -1;
                for (int s = 0; s < slotCount; s++) {
                    if (selectedSlots[s] == fId) { slotIndex = s; break; }
                }

                ImageView fIv    = (ImageView) frame.getChildAt(0);
                TextView  fBadge = (TextView)  frame.getChildAt(1);

                if (slotIndex >= 0) {
                    fIv.setSelected(true);
                    fBadge.setVisibility(View.VISIBLE);
                    fBadge.setText(String.valueOf(slotIndex + 1));
                } else {
                    fIv.setSelected(false);
                    fBadge.setVisibility(View.GONE);
                }
            }
        };

        FrameLayout noneFrame = new FrameLayout(this);
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(iconSize, iconSize);
        nfp.setMargins(iconMargin, 0, iconMargin, 0);
        noneFrame.setLayoutParams(nfp);

        ImageView noneIv = new ImageView(this);
        noneIv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        noneIv.setBackgroundResource(R.drawable.icon_background);
        noneFrame.addView(noneIv);

        TextView noneLabel = new TextView(this);
        noneLabel.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        noneLabel.setText("×");
        noneLabel.setTextSize(20);
        noneLabel.setGravity(Gravity.CENTER);
        noneFrame.addView(noneLabel);

        noneFrame.setOnClickListener(v -> {
            Arrays.fill(selectedSlots, (byte) 0);
            for (int s = 0; s < slotCount; s++) element.setSlotIconId(s, (byte) 0);
            profile.save();
            inputControlsView.invalidate();
            refreshBadges.run();
        });
        iconRow.addView(noneFrame);

        for (final byte id : availableIconIds) {
            FrameLayout frame = new FrameLayout(this);
            frame.setTag(id);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            frameParams.setMargins(iconMargin, 0, iconMargin, 0);
            frame.setLayoutParams(frameParams);

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            try (InputStream is = openIconStream(id)) {
                if (is != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    iv.setImageBitmap(bmp);
                    applyIconBackground(iv, bmp);
                } else {
                    iv.setBackgroundResource(R.drawable.icon_background);
                }
            } catch (IOException e) {
                iv.setBackgroundResource(R.drawable.icon_background);
            }
            frame.addView(iv);

            TextView badge = new TextView(this);
            FrameLayout.LayoutParams badgeParams =
                    new FrameLayout.LayoutParams(badgeSize, badgeSize);
            badgeParams.gravity = Gravity.TOP | Gravity.END;
            badge.setLayoutParams(badgeParams);
            badge.setTextSize(8);
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(Color.WHITE);
            badge.setBackgroundColor(Color.argb(200, 0, 100, 220));
            badge.setVisibility(View.GONE);
            frame.addView(badge);

            iconFrames.add(frame);

            frame.setOnClickListener(v -> {
                int existingSlot = -1;
                for (int s = 0; s < slotCount; s++) {
                    if (selectedSlots[s] == id) { existingSlot = s; break; }
                }

                if (existingSlot >= 0) {
                    for (int s = existingSlot; s < slotCount - 1; s++) {
                        selectedSlots[s] = selectedSlots[s + 1];
                    }
                    selectedSlots[slotCount - 1] = 0;
                } else {
                    boolean placed = false;
                    for (int s = 0; s < slotCount; s++) {
                        if (selectedSlots[s] == 0) {
                            selectedSlots[s] = id;
                            placed = true;
                            break;
                        }
                    }
                    if (!placed) {
                        System.arraycopy(selectedSlots, 1, selectedSlots, 0, slotCount - 1);
                        selectedSlots[slotCount - 1] = id;
                    }
                }

                for (int s = 0; s < slotCount; s++) element.setSlotIconId(s, selectedSlots[s]);
                profile.save();
                inputControlsView.invalidate();
                refreshBadges.run();
            });

            iconRow.addView(frame);
        }

        refreshBadges.run();

        hsv.addView(iconRow);
        llSlotIcons.addView(hsv);
    }

    private void loadMultiButtonUI(View settingsView, ControlElement element) {
    LinearLayout container = settingsView.findViewById(R.id.LLMultiButtonOptions);
    container.removeAllViews();

    ImageButton btnAddSub = new ImageButton(this);
    btnAddSub.setImageResource(R.drawable.icon_add_24dp);
    btnAddSub.setBackgroundColor(Color.TRANSPARENT);
    btnAddSub.setColorFilter(ContextCompat.getColor(ControlsEditorActivity.this, R.color.colorPrimary), PorterDuff.Mode.SRC_IN);
    int pad = (int) UnitUtils.dpToPx(8);
    btnAddSub.setPadding(pad, pad, pad, pad);
    LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    btnLp.gravity = Gravity.END;
    btnAddSub.setLayoutParams(btnLp);
    btnAddSub.setOnClickListener(v -> {
        element.addSubButton();
        profile.save();
        // Tandai sub‑button baru (indeks terakhir) untuk di‑expand
        pendingExpandSubButtonIndex = element.getSubButtonCount() - 1;
        loadMultiButtonUI(settingsView, element);
        inputControlsView.invalidate();
    });
    container.addView(btnAddSub);

    List<ControlElement.SubButton> subButtons = element.getSubButtons();
    for (int i = 0; i < subButtons.size(); i++) {
        // Tentukan apakah section ini harus expanded
        boolean expanded = (i == pendingExpandSubButtonIndex);
        addSubButtonSection(container, element, i, expanded);
        if (i < subButtons.size() - 1) addSeparator(container);
    }
    // Reset pending index setelah semua section dibuat
    pendingExpandSubButtonIndex = -1;
}

    private void addSubButtonSection(LinearLayout container, ControlElement element, int index, boolean expanded) {
    ControlElement.SubButton sb = element.getSubButton(index);

    // Header
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(0, (int) UnitUtils.dpToPx(4), 0, (int) UnitUtils.dpToPx(4));
    header.setBackgroundResource(android.R.drawable.list_selector_background);

    TextView tvHeader = new TextView(this);
    tvHeader.setText("Button " + (index + 1));
    tvHeader.setTypeface(null, Typeface.BOLD);
    tvHeader.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    header.addView(tvHeader);

    ImageButton btnRemove = new ImageButton(this);
    btnRemove.setImageResource(R.drawable.icon_remove_24dp);
    btnRemove.setBackgroundColor(Color.TRANSPARENT);
    btnRemove.setColorFilter(ContextCompat.getColor(ControlsEditorActivity.this, R.color.colorPrimary), PorterDuff.Mode.SRC_IN);
    btnRemove.setPadding((int) UnitUtils.dpToPx(4), (int) UnitUtils.dpToPx(4), (int) UnitUtils.dpToPx(4), (int) UnitUtils.dpToPx(4));
    btnRemove.setOnClickListener(v -> {
        element.removeSubButton(index);
        profile.save();
        // Reset pending expand (tidak perlu expand setelah remove)
        pendingExpandSubButtonIndex = -1;
        loadMultiButtonUI((View) container.getParent(), element);
        inputControlsView.invalidate();
    });
    header.addView(btnRemove);

    TextView tvChevron = new TextView(this);
    tvChevron.setText(expanded ? "▼" : "▶");
    header.addView(tvChevron);

    container.addView(header);

    // Body
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding((int) UnitUtils.dpToPx(8), (int) UnitUtils.dpToPx(4), 0, (int) UnitUtils.dpToPx(4));
    body.setVisibility(expanded ? View.VISIBLE : View.GONE);
    container.addView(body);

    header.setOnClickListener(v -> {
        boolean currentlyExpanded = body.getVisibility() == View.VISIBLE;
        body.setVisibility(currentlyExpanded ? View.GONE : View.VISIBLE);
        tvChevron.setText(currentlyExpanded ? "▶" : "▼");
    });

    addDirectionSpinner(body, element, index, sb);
    addLabelField(body, element, index, sb);
    addIconPicker(body, element, index, sb);
    addBindingSection(body, element, index, sb);
}

    private void addDirectionSpinner(LinearLayout body, ControlElement element, int index, ControlElement.SubButton sb) {
        final String[] DIRECTION_NAMES = {
                "NONE (hidden)", "↑ Up", "↗ Up-Right", "→ Right",
                "↘ Down-Right", "↓ Down", "↙ Down-Left", "← Left", "↖ Up-Left"
        };

        TextView lblDir = new TextView(this);
        lblDir.setText("Direction:");
        lblDir.setPadding(0, (int) UnitUtils.dpToPx(4), 0, 0);
        body.addView(lblDir);

        Spinner spDir = new Spinner(this);
        spDir.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, DIRECTION_NAMES));

        byte curDir = sb.direction;
        int dirSelection = (curDir == (byte) 0xFF || curDir < 0) ? 0 : (curDir & 0xFF) + 1;
        if (dirSelection > 8) dirSelection = 0;
        spDir.setSelection(dirSelection, false);
        spDir.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(spDir);

        spDir.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                byte dirVal = (pos == 0) ? (byte) 0xFF : (byte) (pos - 1);
                element.setMultiButtonDirection(index, dirVal);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void addLabelField(LinearLayout body, ControlElement element, int index, ControlElement.SubButton sb) {
        TextView lblText = new TextView(this);
        lblText.setText("Label (optional):");
        lblText.setPadding(0, (int) UnitUtils.dpToPx(6), 0, 0);
        body.addView(lblText);

        EditText etLabel = new EditText(this);
        etLabel.setText(sb.text);
        etLabel.setHint("leave empty = show key name");
        etLabel.setSingleLine(true);
        etLabel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(etLabel);

        etLabel.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                element.setMultiButtonText(index, s.toString());
                profile.save();
                inputControlsView.invalidate();
            }
        });
    }

    private void addIconPicker(LinearLayout body, ControlElement element, int index, ControlElement.SubButton sb) {
        TextView lblIcon = new TextView(this);
        lblIcon.setText("Icon (optional):");
        lblIcon.setPadding(0, (int) UnitUtils.dpToPx(6), 0, 0);
        body.addView(lblIcon);

        byte[] iconIds = loadAllIconIds();
        int iconSize = (int) UnitUtils.dpToPx(40);
        int margin = (int) UnitUtils.dpToPx(2);
        int padding = (int) UnitUtils.dpToPx(4);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        final byte[] selectedHolder = { sb.iconId };

        FrameLayout noneFrame = new FrameLayout(this);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(iconSize, iconSize);
        nlp.setMargins(margin, 0, margin, 0);
        noneFrame.setLayoutParams(nlp);
        ImageView noneIv = new ImageView(this);
        noneIv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        noneIv.setBackgroundResource(R.drawable.icon_background);
        noneFrame.addView(noneIv);
        TextView noneLabel = new TextView(this);
        noneLabel.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        noneLabel.setText("×");
        noneLabel.setTextSize(20);
        noneLabel.setGravity(Gravity.CENTER);
        noneFrame.addView(noneLabel);
        noneFrame.setOnClickListener(v -> {
            selectedHolder[0] = 0;
            element.setMultiButtonIconId(index, (byte) 0);
            profile.save();
            inputControlsView.invalidate();
            refreshIconSelection(iconRow, (byte) 0);
        });
        iconRow.addView(noneFrame);

        for (final byte id : iconIds) {
            FrameLayout frame = new FrameLayout(this);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(iconSize, iconSize);
            flp.setMargins(margin, 0, margin, 0);
            frame.setLayoutParams(flp);

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setPadding(padding, padding, padding, padding);
            iv.setTag(id);
            iv.setSelected(id == selectedHolder[0]);

            try (InputStream is = openIconStream(id)) {
                if (is != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    iv.setImageBitmap(bmp);
                    applyIconBackground(iv, bmp);
                }
            } catch (IOException e) {
                iv.setBackgroundResource(R.drawable.icon_background);
            }

            iv.setOnClickListener(v -> {
                selectedHolder[0] = id;
                element.setMultiButtonIconId(index, id);
                profile.save();
                inputControlsView.invalidate();
                refreshIconSelection(iconRow, id);
            });

            frame.addView(iv);
            iconRow.addView(frame);
        }

        hsv.addView(iconRow);
        body.addView(hsv);
    }

    private void refreshIconSelection(LinearLayout iconRow, byte selectedId) {
        for (int i = 0; i < iconRow.getChildCount(); i++) {
            View child = iconRow.getChildAt(i);
            if (child instanceof FrameLayout) {
                ImageView iv = (ImageView) ((FrameLayout) child).getChildAt(0);
                Object tag = iv.getTag();
                if (tag instanceof Byte) {
                    iv.setSelected((byte) tag == selectedId);
                } else {
                    iv.setSelected(false);
                }
            }
        }
    }

    private void addBindingSection(LinearLayout body, ControlElement element, int index, ControlElement.SubButton sb) {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, (int) UnitUtils.dpToPx(6), 0, (int) UnitUtils.dpToPx(2));

        TextView lblBindings = new TextView(this);
        lblBindings.setText("Key Bindings:");
        lblBindings.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(lblBindings);

        ImageButton btnAdd = new ImageButton(this);
        btnAdd.setImageResource(R.drawable.icon_add_24dp);
        btnAdd.setBackgroundColor(Color.TRANSPARENT);
        btnAdd.setColorFilter(ContextCompat.getColor(ControlsEditorActivity.this, R.color.colorPrimary), PorterDuff.Mode.SRC_IN);
        int pad = (int) UnitUtils.dpToPx(4);
        btnAdd.setPadding(pad, pad, pad, pad);
        titleRow.addView(btnAdd);
        body.addView(titleRow);

        LinearLayout bindContainer = new LinearLayout(this);
        bindContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(bindContainer);

        // FIX: btnAdd harus mereferensikan bindContainer (bukan body).
        // bindContainer dideklarasi setelah closure, sehingga digunakan array wrapper agar bisa diakses dari lambda.
        btnAdd.setOnClickListener(v -> {
            sb.bindings.add(Binding.NONE);
            element.setMultiButtonBindings(index, sb.bindings);
            profile.save();
            rebuildBindingRows(bindContainer, element, index, sb);
            inputControlsView.invalidate();
        });

        rebuildBindingRows(bindContainer, element, index, sb);
    }

    private void rebuildBindingRows(LinearLayout bindContainer, ControlElement element, int index, ControlElement.SubButton sb) {
        bindContainer.removeAllViews();
        for (int i = 0; i < sb.bindings.size(); i++) {
            addMultiBtnBindingRow(element, bindContainer, index, i);
        }
    }

    private void addMultiBtnBindingRow(ControlElement element, LinearLayout bindContainer,
                                   int subIdx, int bindIdx) {
    View row = LayoutInflater.from(this).inflate(R.layout.binding_field, bindContainer, false);

    Spinner sBindingType = row.findViewById(R.id.SBindingType);
    Spinner sBinding     = row.findViewById(R.id.SBinding);
    ImageButton btnRemove = row.findViewById(R.id.btnRemoveBinding);

    Runnable updateBindingSpinner = () -> {
        String[] entries = null;
        Binding[] values = null;
        switch (sBindingType.getSelectedItemPosition()) {
            case 0:
                entries = Binding.keyboardBindingLabels();
                values = Binding.keyboardBindingValues();
                break;
            case 1:
                entries = Binding.mouseBindingLabels();
                values = Binding.mouseBindingValues();
                break;
            case 2:
                entries = Binding.gamepadBindingLabels();
                values = Binding.gamepadBindingValues();
                break;
        }
        if (entries != null) {
            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, entries));
            List<Binding> sb = element.getMultiButtonBindings(subIdx);
            Binding current = (bindIdx < sb.size()) ? sb.get(bindIdx) : Binding.NONE;
            int selIndex = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) {
                    selIndex = i;
                    break;
                }
            }
            sBinding.setSelection(selIndex);
        }
    };

    sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            updateBindingSpinner.run();
        }
        @Override public void onNothingSelected(AdapterView<?> p) {}
    });

    List<Binding> sb = element.getMultiButtonBindings(subIdx);
    Binding current = (bindIdx < sb.size()) ? sb.get(bindIdx) : Binding.NONE;
    if      (current.isKeyboard()) sBindingType.setSelection(0, false);
    else if (current.isMouse())    sBindingType.setSelection(1, false);
    else if (current.isGamepad())  sBindingType.setSelection(2, false);

    updateBindingSpinner.run();

    sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            Binding newBinding = Binding.NONE;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0: newBinding = Binding.keyboardBindingValues()[pos]; break;
                case 1: newBinding = Binding.mouseBindingValues()[pos];    break;
                case 2: newBinding = Binding.gamepadBindingValues()[pos];  break;
            }
            List<Binding> sb2 = element.getMultiButtonBindings(subIdx);
            if (bindIdx < sb2.size()) {
                sb2.set(bindIdx, newBinding);
                element.setMultiButtonBindings(subIdx, sb2);
                profile.save();
                inputControlsView.invalidate();
            }
        }
        @Override public void onNothingSelected(AdapterView<?> p) {}
    });


    if (bindIdx >= 1) {
        btnRemove.setVisibility(View.VISIBLE);
        btnRemove.setOnClickListener(v -> {
            List<Binding> sb2 = element.getMultiButtonBindings(subIdx);
            if (bindIdx < sb2.size()) {
                sb2.remove(bindIdx);
                element.setMultiButtonBindings(subIdx, sb2);
                profile.save();
                View parent = (View) bindContainer.getParent();
                if (parent instanceof LinearLayout) {
                    rebuildBindingRows(bindContainer, element, subIdx, element.getSubButton(subIdx));
                }
                inputControlsView.invalidate();
            }
        });
    } else {
        btnRemove.setVisibility(View.GONE);
    }

    bindContainer.addView(row);
}

    private void addSeparator(LinearLayout parent) {
        View sep = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        int margin = (int) UnitUtils.dpToPx(6);
        lp.topMargin = margin;
        lp.bottomMargin = margin;
        sep.setLayoutParams(lp);
        sep.setBackgroundColor(0x33FFFFFF);
        parent.addView(sep);
    }

    private void applyIconBackground(ImageView imageView, Bitmap bitmap) {
        imageView.setBackgroundResource(R.drawable.icon_background);
        if (bitmap == null) return;
        if (isDarkMode) return;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long totalVisible = 0;
        long whitishPixels = 0;

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 30) continue;
                totalVisible++;
                if (Color.red(pixel) >= 200 && Color.green(pixel) >= 200 && Color.blue(pixel) >= 200) {
                    whitishPixels++;
                }
            }
        }

        if (totalVisible > 0 && (whitishPixels * 100 / totalVisible) >= 60) {
            android.graphics.drawable.Drawable blackLayer =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.icon_background_black);
            android.graphics.drawable.Drawable selectorLayer =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.icon_background);
            android.graphics.drawable.LayerDrawable layered =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{blackLayer, selectorLayer});
            imageView.setBackground(layered);
        }
    }

    private byte[] loadAllIconIds() {
        List<Byte> ids = new ArrayList<>();
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            if (filenames != null) {
                for (String fn : filenames) {
                    try {
                        byte id = Byte.parseByte(FileUtils.getBasename(fn));
                        if (!ids.contains(id)) ids.add(id);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}

        File iconsDir = InputControlsManager.getIconsDir(this);
        File[] files = iconsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String base = FileUtils.getBasename(f.getName());
                try {
                    int idInt = Integer.parseInt(base);
                    if (idInt >= Byte.MIN_VALUE && idInt <= Byte.MAX_VALUE) {
                        byte id = (byte) idInt;
                        if (!ids.contains(id)) ids.add(id);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        ids.sort((a, b) -> Byte.toUnsignedInt(a) - Byte.toUnsignedInt(b));
        byte[] result = new byte[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    private InputStream openIconStream(byte id) {
        try {
            return getAssets().open("inputcontrols/icons/" + id + ".png");
        } catch (IOException ignored) {}

        File iconsDir = InputControlsManager.getIconsDir(this);
        String[] extensions = {".png", ".jpg", ".jpeg", ".webp"};
        for (String ext : extensions) {
            File f = new File(iconsDir, id + ext);
            if (f.isFile()) {
                try {
                    return new FileInputStream(f);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, Runnable callback) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setType(ControlElement.Type.values()[position]);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View settingsView) {
        LinearLayout container = settingsView.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();

        if (type == ControlElement.Type.MULTIPLE_BUTTON ||
            type == ControlElement.Type.MENU_NAVIGATION) {
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.GONE);
            return;
        }

        if (type == ControlElement.Type.BUTTON) {
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.VISIBLE);

            if (element.getBindingCount() == 0) {
                element.addBinding(Binding.NONE);
                profile.save();
            }
            
            btnAddBinding.setOnClickListener(v -> {
                if (element.getBindingCount() >= 8) {
                    AppUtils.showToast(this, "Maksimal 8 binding diperbolehkan");
                    return;
                }
                element.addBinding(Binding.NONE);
                profile.save();
                addNewBindingRow(element, container, element.getBindingCount() - 1);
                inputControlsView.invalidate();
            });

            for (int i = 0; i < element.getBindingCount(); i++) {
                addNewBindingRow(element, container, i);
            }
        } 
        else if (type == ControlElement.Type.D_PAD || 
                 type == ControlElement.Type.STICK || 
                 type == ControlElement.Type.TRACKPAD ||
                 type == ControlElement.Type.RIGHT_STICK) {
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.GONE);

            addNewBindingRow(element, container, 0);
            addNewBindingRow(element, container, 1);
            addNewBindingRow(element, container, 2);
            addNewBindingRow(element, container, 3);
        } 
        else {
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.GONE);
        }
    }

    private void addNewBindingRow(final ControlElement element, final LinearLayout container, final int index) {
    View row = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);

    Spinner sBindingType = row.findViewById(R.id.SBindingType);
    Spinner sBinding = row.findViewById(R.id.SBinding);
    ImageButton btnRemove = row.findViewById(R.id.btnRemoveBinding);

    Runnable updateBindingSpinner = () -> {
        String[] bindingEntries = null;
        Binding[] bindingValues = null;
        switch (sBindingType.getSelectedItemPosition()) {
            case 0:
                bindingEntries = Binding.keyboardBindingLabels();
                bindingValues = Binding.keyboardBindingValues();
                break;
            case 1:
                bindingEntries = Binding.mouseBindingLabels();
                bindingValues = Binding.mouseBindingValues();
                break;
            case 2:
                bindingEntries = Binding.gamepadBindingLabels();
                bindingValues = Binding.gamepadBindingValues();
                break;
        }
        if (bindingEntries != null) {
            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            Binding current = element.getBindingAt(index);
            int selIndex = 0;
            for (int i = 0; i < bindingValues.length; i++) {
                if (bindingValues[i] == current) {
                    selIndex = i;
                    break;
                }
            }
            sBinding.setSelection(selIndex);
        }
    };

    sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            updateBindingSpinner.run();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });

    Binding current = element.getBindingAt(index);
    if (current.isKeyboard()) sBindingType.setSelection(0, false);
    else if (current.isMouse()) sBindingType.setSelection(1, false);
    else if (current.isGamepad()) sBindingType.setSelection(2, false);

    updateBindingSpinner.run();

    sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            Binding newBinding = Binding.NONE;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0: newBinding = Binding.keyboardBindingValues()[position]; break;
                case 1: newBinding = Binding.mouseBindingValues()[position]; break;
                case 2: newBinding = Binding.gamepadBindingValues()[position]; break;
            }
            element.setBindingAt(index, newBinding);
            profile.save();
            inputControlsView.invalidate();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });

    if (element.getType() == ControlElement.Type.BUTTON && index >= 1) {
        btnRemove.setVisibility(View.VISIBLE);
        btnRemove.setOnClickListener(v -> {
            element.removeBinding(index);
            profile.save();
            container.removeView(row);
            inputControlsView.invalidate();
        });
    } else {
        btnRemove.setVisibility(View.GONE);
    }

    container.addView(row);
}

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadIcons(final LinearLayout parent, byte selectedId) {
        byte[] iconIds = loadAllIconIds();

        int size = (int) UnitUtils.dpToPx(40);
        int margin = (int) UnitUtils.dpToPx(2);
        int padding = (int) UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (final byte id : iconIds) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId);
            imageView.setOnClickListener(v -> {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    parent.getChildAt(i).setSelected(false);
                }
                imageView.setSelected(true);
            });

            try (InputStream is = openIconStream(id)) {
                if (is != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    imageView.setImageBitmap(bmp);
                    applyIconBackground(imageView, bmp);
                } else {
                    imageView.setBackgroundResource(R.drawable.icon_background);
                }
            } catch (IOException e) {
                imageView.setBackgroundResource(R.drawable.icon_background);
            }

            parent.addView(imageView);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }

    protected void attachBaseContext(Context context) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(context));
    }
}
