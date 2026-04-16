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
                        new String[]{"Main Button", "Keyboard", "Input Controls", "Exit"}, 4);
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

        // Header hint
        TextView tvHint = new TextView(this);
        tvHint.setText("Tap a direction to configure its sub button (max 8 total)");
        tvHint.setTextSize(11);
        tvHint.setPadding(0, 0, 0, (int) UnitUtils.dpToPx(8));
        container.addView(tvHint);

        // 8-direction grid:
        // Layout:  [UL]  [U]  [UR]
        //          [L ]  [--] [R ]
        //          [DL]  [D]  [DR]
        // Directions: 0=Up, 1=UpRight, 2=Right, 3=DownRight, 4=Down, 5=DownLeft, 6=Left, 7=UpLeft
        final String[] DIR_LABELS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        // grid positions [row][col] -> dirIndex, -1 = center (unused)
        final int[][] GRID = {
                {7, 0, 1},
                {6, -1, 2},
                {5, 4, 3}
        };

        int btnSize = (int) UnitUtils.dpToPx(48);
        int btnMargin = (int) UnitUtils.dpToPx(4);

        for (int row = 0; row < 3; row++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, btnMargin / 2, 0, btnMargin / 2);
            rowLayout.setLayoutParams(rowLp);

            for (int col = 0; col < 3; col++) {
                int dirIdx = GRID[row][col];

                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(btnSize, btnSize);
                cellLp.setMargins(btnMargin, btnMargin, btnMargin, btnMargin);

                if (dirIdx == -1) {
                    // Center placeholder
                    View spacer = new View(this);
                    spacer.setLayoutParams(cellLp);
                    rowLayout.addView(spacer);
                    continue;
                }

                // Find existing SubButton for this direction
                final int finalDir = dirIdx;
                ControlElement.SubButton existingSb = null;
                int existingIdx = -1;
                for (int i = 0; i < element.getSubButtonCount(); i++) {
                    ControlElement.SubButton sb = element.getSubButton(i);
                    if (sb != null && (sb.direction & 0xFF) == dirIdx) {
                        existingSb = sb;
                        existingIdx = i;
                        break;
                    }
                }
                boolean hasSubBtn = (existingSb != null);

                TextView dirBtn = new TextView(this);
                dirBtn.setLayoutParams(cellLp);
                dirBtn.setText(DIR_LABELS[dirIdx]);
                dirBtn.setTextSize(18);
                dirBtn.setGravity(Gravity.CENTER);
                dirBtn.setTypeface(null, hasSubBtn ? Typeface.BOLD : Typeface.NORMAL);
                // Visual: filled = has subbutton, outline = empty
                int primaryColor = ContextCompat.getColor(this, R.color.colorPrimary);
                if (hasSubBtn) {
                    dirBtn.setBackgroundColor(primaryColor);
                    dirBtn.setTextColor(Color.WHITE);
                } else {
                    dirBtn.setBackgroundColor(Color.argb(40, 128, 128, 128));
                    dirBtn.setTextColor(primaryColor);
                }

                final int fExistingIdx = existingIdx;

                dirBtn.setOnClickListener(v -> {
                    showDirectionSubButtonDialog(settingsView, element, finalDir, fExistingIdx, container);
                });

                rowLayout.addView(dirBtn);
            }
            container.addView(rowLayout);
        }

        // Sub button count info
        TextView tvCount = new TextView(this);
        tvCount.setText("Active: " + element.getSubButtonCount() + " / 8 sub buttons");
        tvCount.setTextSize(11);
        tvCount.setPadding(0, (int) UnitUtils.dpToPx(8), 0, 0);
        container.addView(tvCount);
    }

    /**
     * Tampilkan dialog untuk satu direction: checkbox aktif/nonaktif,
     * icon picker (slot-style 1-row + badge 1-8), dan binding section.
     */
    private void showDirectionSubButtonDialog(View settingsView, ControlElement element,
                                               int dirIdx, int existingSubBtnIdx,
                                               LinearLayout dirGridContainer) {
        final String[] DIR_LABELS = {"↑ Up", "↗ Up-Right", "→ Right", "↘ Down-Right",
                "↓ Down", "↙ Down-Left", "← Left", "↖ Up-Left"};

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Direction: " + DIR_LABELS[dirIdx]);

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        int dp8 = (int) UnitUtils.dpToPx(8);
        dialogLayout.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);

        // --- Checkbox aktifkan sub button ---
        CheckBox cbEnable = new CheckBox(this);
        cbEnable.setText("Enable sub button for this direction");
        boolean hasExisting = (existingSubBtnIdx >= 0);
        cbEnable.setChecked(hasExisting);
        dialogLayout.addView(cbEnable);

        // --- Container content (tampil hanya jika checkbox aktif) ---
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setVisibility(hasExisting ? View.VISIBLE : View.GONE);
        dialogLayout.addView(contentLayout);

        // Siapkan atau ambil SubButton
        final int[] subIdxHolder = {existingSubBtnIdx};

        // Fungsi helper rebuild content
        Runnable[] rebuildContent = {null};

        cbEnable.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                // Buat SubButton baru jika belum ada
                if (subIdxHolder[0] < 0) {
                    if (element.getSubButtonCount() >= 8) {
                        cbEnable.setChecked(false);
                        AppUtils.showToast(this, "Maximum 8 sub buttons reached");
                        return;
                    }
                    element.addSubButton();
                    subIdxHolder[0] = element.getSubButtonCount() - 1;
                    element.setMultiButtonDirection(subIdxHolder[0], (byte) dirIdx);
                    profile.save();
                }
                contentLayout.setVisibility(View.VISIBLE);
                if (rebuildContent[0] != null) rebuildContent[0].run();
            } else {
                // Hapus SubButton
                if (subIdxHolder[0] >= 0) {
                    element.removeSubButton(subIdxHolder[0]);
                    subIdxHolder[0] = -1;
                    profile.save();
                }
                contentLayout.setVisibility(View.GONE);
                inputControlsView.invalidate();
            }
        });

        // Build content layout (icon picker + binding)
        rebuildContent[0] = () -> {
            contentLayout.removeAllViews();
            int idx = subIdxHolder[0];
            if (idx < 0) return;
            ControlElement.SubButton sb = element.getSubButton(idx);
            if (sb == null) return;

            // --- Icon picker (slot-style: satu baris, badge 1-8 total sub buttons) ---
            TextView lblIcon = new TextView(this);
            lblIcon.setText("Icon (optional):");
            lblIcon.setPadding(0, dp8, 0, (int) UnitUtils.dpToPx(4));
            contentLayout.addView(lblIcon);

            // Tampilkan semua sub button yang aktif sebagai "slot" badge
            // Badge menunjukkan posisi slot di antara semua sub button yang aktif
            int totalActive = element.getSubButtonCount();
            // Cari posisi idx di antara semua sub button (urutan berdasarkan index)
            int slotPos = idx; // posisi 0-based di list

            addSubButtonIconPickerSlotStyle(contentLayout, element, idx, totalActive);

            // --- Label field ---
            addLabelField(contentLayout, element, idx, sb);

            // --- Binding section ---
            addBindingSection(contentLayout, element, idx, sb);
        };

        if (hasExisting) {
            rebuildContent[0].run();
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(dialogLayout);

        builder.setView(sv);
        builder.setPositiveButton("Done", (d, w) -> {
            // Refresh direction grid
            loadMultiButtonUI(settingsView, element);
            inputControlsView.invalidate();
        });

        builder.show();
    }

    /**
     * Icon picker untuk sub button, menggunakan gaya loadSlotIconsUI:
     * satu baris horizontal scroll, badge angka menunjukkan slot aktif
     * di antara semua sub button (maks 8).
     */
    private void addSubButtonIconPickerSlotStyle(LinearLayout parent, ControlElement element,
                                                  int subIdx, int totalActive) {
        byte[] availableIconIds = loadAllIconIds();

        int iconSize    = (int) UnitUtils.dpToPx(40);
        int iconMargin  = (int) UnitUtils.dpToPx(2);
        int iconPadding = (int) UnitUtils.dpToPx(4);
        int badgeSize   = (int) UnitUtils.dpToPx(14);

        // Hint: slot X of Y
        TextView tvHint = new TextView(this);
        tvHint.setText("Icon (slot " + (subIdx + 1) + " of " + totalActive + "):");
        tvHint.setTextSize(11);
        tvHint.setPadding(0, 0, 0, (int) UnitUtils.dpToPx(4));
        parent.addView(tvHint);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        final List<FrameLayout> iconFrames = new ArrayList<>();
        // selectedId holder
        final byte[] selectedHolder = new byte[]{element.getMultiButtonIconId(subIdx)};

        final Runnable refreshBadges = () -> {
            for (FrameLayout frame : iconFrames) {
                Object tagObj = frame.getTag();
                if (tagObj == null) continue;
                byte fId = (byte) tagObj;

                ImageView fIv    = (ImageView) frame.getChildAt(0);
                TextView  fBadge = (TextView)  frame.getChildAt(1);

                if (fId != 0 && fId == selectedHolder[0]) {
                    fIv.setSelected(true);
                    fBadge.setVisibility(View.VISIBLE);
                    // Badge menampilkan nomor slot (1-based) di antara semua sub button aktif
                    fBadge.setText(String.valueOf(subIdx + 1));
                } else {
                    fIv.setSelected(false);
                    fBadge.setVisibility(View.GONE);
                }
            }
        };

        // "None" button
        FrameLayout noneFrame = new FrameLayout(this);
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(iconSize, iconSize);
        nfp.setMargins(iconMargin, 0, iconMargin, 0);
        noneFrame.setLayoutParams(nfp);
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
            element.setMultiButtonIconId(subIdx, (byte) 0);
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
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            try (java.io.InputStream is = openIconStream(id)) {
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
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(badgeSize, badgeSize);
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
                selectedHolder[0] = id;
                element.setMultiButtonIconId(subIdx, id);
                profile.save();
                inputControlsView.invalidate();
                refreshBadges.run();
            });

            iconRow.addView(frame);
        }

        refreshBadges.run();

        hsv.addView(iconRow);
        parent.addView(hsv);
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
