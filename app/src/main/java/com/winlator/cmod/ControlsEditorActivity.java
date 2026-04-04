package com.winlator.cmod;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.content.Context;

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

        // Baca preferensi dark mode — harus sebelum setContentView
        // agar AppTheme.Dark menerapkan windowBackground, textViewStyle,
        // checkboxStyle, popupWindowStyle, dll. secara otomatis ke seluruh layout.
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

            // Reset visibility
            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLBindings).setVisibility(View.GONE);
            view.findViewById(R.id.LLSlotIcons).setVisibility(View.GONE);
            view.findViewById(R.id.LLCursorMove).setVisibility(View.GONE);
            view.findViewById(R.id.LLCursorMoveRadius).setVisibility(View.GONE);

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

                // Tampilkan opsi Cursor Move hanya untuk RIGHT_STICK
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
            loadBindingSpinners(element, view);
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
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbScale.setProgress((int) (element.getScale() * 100));

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        // === CURSOR MOVE MODE (hanya RIGHT_STICK) ===
        CheckBox cbCursorMove = view.findViewById(R.id.CBCursorMove);
        cbCursorMove.setChecked(element.isCursorMove());

        final TextView tvCursorRadius = view.findViewById(R.id.TVCursorMoveRadius);
        SeekBar sbCursorRadius = view.findViewById(R.id.SBCursorMoveRadius);
        // Range radius: 50–500 piksel (ukuran lingkaran pergerakan pointer di layar)
        // SeekBar max=450, progress offset +50 agar nilai aktual = progress+50
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
            // Tampilkan / sembunyikan seekbar radius sesuai state checkbox
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
            // slotIconIds sudah di-set realtime di onClick setiap icon,
            // tidak perlu dibaca ulang dari view di sini.
            profile.save();
            inputControlsView.invalidate();
        });
    }

    /**
     * Satu baris icon tunggal dengan multi-select berurutan (tidak ada lag).
     *
     * Urutan slot sesuai ControlElement:
     *   D_PAD             → slot 0=Up, 1=Right, 2=Down, 3=Left  (slotCount=4)
     *   STICK/RIGHT_STICK → slot 0=Outer, 1=Inner               (slotCount=2)
     *
     * Tap icon yang belum dipilih → masuk ke slot kosong berikutnya.
     * Tap icon yang sudah dipilih → hapus dari slot itu, slot sesudahnya maju.
     * Tap "×"                     → reset semua slot ke 0 (tidak ada icon).
     *
     * Badge angka biru di pojok kanan atas menunjukkan urutan slot.
     */
    private void loadSlotIconsUI(View settingsView, ControlElement element,
                                 String[] slotLabels, int slotCount) {
        LinearLayout llSlotIcons = settingsView.findViewById(R.id.LLSlotIcons);
        llSlotIcons.removeAllViews();

        // Muat daftar icon dari assets + folder import pengguna
        byte[] availableIconIds = loadAllIconIds();

        // State slot lokal — diperbarui setiap tap dan disinkronkan ke element
        final byte[] selectedSlots = new byte[slotCount];
        for (int s = 0; s < slotCount; s++) {
            selectedSlots[s] = element.getSlotIconId(s);
        }

        int iconSize    = (int) com.winlator.cmod.core.UnitUtils.dpToPx(40);
        int iconMargin  = (int) com.winlator.cmod.core.UnitUtils.dpToPx(2);
        int iconPadding = (int) com.winlator.cmod.core.UnitUtils.dpToPx(4);
        int badgeSize   = (int) com.winlator.cmod.core.UnitUtils.dpToPx(14);

        // Baris keterangan urutan slot (mis. "1=Up  2=Right  3=Down  4=Left")
        TextView tvHint = new TextView(this);
        StringBuilder hint = new StringBuilder("Slot: ");
        for (int s = 0; s < slotCount; s++) {
            if (s > 0) hint.append("  ");
            hint.append(s + 1).append("=").append(slotLabels[s]);
        }
        tvHint.setText(hint.toString());
        tvHint.setTextSize(11);
        tvHint.setPadding(0, 0, 0, (int) com.winlator.cmod.core.UnitUtils.dpToPx(4));
        llSlotIcons.addView(tvHint);

        // HorizontalScrollView — hanya SATU baris icon
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        // Referensi semua FrameLayout untuk refresh badge tanpa rebuild view
        final java.util.List<android.widget.FrameLayout> iconFrames = new java.util.ArrayList<>();

        // Runnable: perbarui badge angka semua icon sesuai selectedSlots
        final Runnable refreshBadges = () -> {
            for (android.widget.FrameLayout frame : iconFrames) {
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

        // Tombol "×" — reset semua slot
        android.widget.FrameLayout noneFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(iconSize, iconSize);
        nfp.setMargins(iconMargin, 0, iconMargin, 0);
        noneFrame.setLayoutParams(nfp);

        ImageView noneIv = new ImageView(this);
        noneIv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        noneIv.setBackgroundResource(R.drawable.icon_background);
        noneFrame.addView(noneIv);

        TextView noneLabel = new TextView(this);
        noneLabel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        noneLabel.setText("×");
        noneLabel.setTextSize(20);
        noneLabel.setGravity(android.view.Gravity.CENTER);
        noneFrame.addView(noneLabel);

        noneFrame.setOnClickListener(v -> {
            java.util.Arrays.fill(selectedSlots, (byte) 0);
            for (int s = 0; s < slotCount; s++) element.setSlotIconId(s, (byte) 0);
            profile.save();
            inputControlsView.invalidate();
            refreshBadges.run();
        });
        iconRow.addView(noneFrame);

        // Satu daftar icon (tidak berulang per slot)
        for (final byte id : availableIconIds) {
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setTag(id);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            frameParams.setMargins(iconMargin, 0, iconMargin, 0);
            frame.setLayoutParams(frameParams);

            // ImageView icon dengan applyIconBackground
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
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

            // Badge angka urutan slot (pojok kanan atas)
            TextView badge = new TextView(this);
            android.widget.FrameLayout.LayoutParams badgeParams =
                    new android.widget.FrameLayout.LayoutParams(badgeSize, badgeSize);
            badgeParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            badge.setLayoutParams(badgeParams);
            badge.setTextSize(8);
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setTextColor(Color.WHITE);
            badge.setBackgroundColor(Color.argb(200, 0, 100, 220));
            badge.setVisibility(View.GONE);
            frame.addView(badge);

            iconFrames.add(frame);

            frame.setOnClickListener(v -> {
                // Cari apakah icon ini sudah ada di slot manapun
                int existingSlot = -1;
                for (int s = 0; s < slotCount; s++) {
                    if (selectedSlots[s] == id) { existingSlot = s; break; }
                }

                if (existingSlot >= 0) {
                    // Sudah dipilih → hapus, geser slot sesudahnya maju
                    for (int s = existingSlot; s < slotCount - 1; s++) {
                        selectedSlots[s] = selectedSlots[s + 1];
                    }
                    selectedSlots[slotCount - 1] = 0;
                } else {
                    // Belum dipilih → masuk ke slot kosong pertama
                    boolean placed = false;
                    for (int s = 0; s < slotCount; s++) {
                        if (selectedSlots[s] == 0) {
                            selectedSlots[s] = id;
                            placed = true;
                            break;
                        }
                    }
                    if (!placed) {
                        // Semua slot penuh → geser kiri, tempatkan di slot terakhir
                        System.arraycopy(selectedSlots, 1, selectedSlots, 0, slotCount - 1);
                        selectedSlots[slotCount - 1] = id;
                    }
                }

                // Sinkronisasi ke ControlElement
                for (int s = 0; s < slotCount; s++) element.setSlotIconId(s, selectedSlots[s]);
                profile.save();
                inputControlsView.invalidate();
                refreshBadges.run();
            });

            iconRow.addView(frame);
        }

        // Inisialisasi badge sesuai state awal
        refreshBadges.run();

        hsv.addView(iconRow);
        llSlotIcons.addView(hsv);
    }

    /**
     * Analisis dominasi warna bitmap icon.
     * Icon dominan putih → background hitam (agar terlihat), dengan LayerDrawable
     * agar stroke indikator selected dari icon_background selector tetap tampil di atas.
     * Lainnya → background default drawable saja.
     *
     * Pada dark mode: deteksi warna dilewati sepenuhnya.
     * icon_background_black diganti icon_background karena background tema sudah gelap,
     * sehingga icon putih tetap terlihat tanpa lapisan hitam tambahan.
     */
    private void applyIconBackground(ImageView imageView, Bitmap bitmap) {
        imageView.setBackgroundResource(R.drawable.icon_background);
        if (bitmap == null) return;

        // Dark mode: background tema sudah gelap — tidak perlu deteksi warna,
        // cukup pakai icon_background standar untuk semua icon.
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
            // Dominan putih → susun LayerDrawable: lapisan bawah hitam solid,
            // lapisan atas icon_background selector (membawa stroke biru saat selected).
            android.graphics.drawable.Drawable blackLayer =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.icon_background_black);
            android.graphics.drawable.Drawable selectorLayer =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.icon_background);
            android.graphics.drawable.LayerDrawable layered =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{blackLayer, selectorLayer});
            imageView.setBackground(layered);
        }
        // else: icon_background sudah di-set di awal, tidak perlu tindakan lagi
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

        // Hanya BUTTON yang boleh pakai tombol Add dan multiple binding
        if (type == ControlElement.Type.BUTTON) {
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.VISIBLE);

            if (element.getBindingCount() == 0) {
                element.addBinding(Binding.NONE);   // tambahkan binding default
                profile.save();                     // simpan agar tidak hilang
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

            // Tampilkan semua binding yang sudah ada (minimal 1)
            for (int i = 0; i < element.getBindingCount(); i++) {
                addNewBindingRow(element, container, i);
            }
        } 
        else if (type == ControlElement.Type.D_PAD || 
                 type == ControlElement.Type.STICK || 
                 type == ControlElement.Type.TRACKPAD ||
                 type == ControlElement.Type.RIGHT_STICK) {
            // Sembunyikan tombol Add
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.GONE);

            // 4 arah tetap ditampilkan
            addNewBindingRow(element, container, 0); // Up
            addNewBindingRow(element, container, 1); // Right
            addNewBindingRow(element, container, 2); // Down
            addNewBindingRow(element, container, 3); // Left
        } 
        else {
            // Untuk TOUCHSCREEN_TOGGLE dan tipe lain yang tidak butuh binding
            ImageButton btnAddBinding = settingsView.findViewById(R.id.btnAddBinding);
            btnAddBinding.setVisibility(View.GONE);
            // container sudah di-removeAllViews() di atas, jadi kosong
        }
    }

    private void addNewBindingRow(final ControlElement element, final LinearLayout container, final int index) {
        View row = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);

        Spinner sBindingType = row.findViewById(R.id.SBindingType);
        Spinner sBinding = row.findViewById(R.id.SBinding);
        ImageButton btnRemove = row.findViewById(R.id.btnRemoveBinding);

        // Setup spinner binding
        Runnable updateBindingSpinner = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0: bindingEntries = Binding.keyboardBindingLabels(); break;
                case 1: bindingEntries = Binding.mouseBindingLabels(); break;
                case 2: bindingEntries = Binding.gamepadBindingLabels(); break;
            }
            if (bindingEntries != null) {
                sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
                AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
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

        updateBindingSpinner.run();

        // Logika Remove: hanya untuk BUTTON dan index >= 1
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

    /**
     * Mengumpulkan semua ID icon yang tersedia dari dua sumber:
     *  1. Assets bawaan   : assets/inputcontrols/icons/
     *  2. Import pengguna : filesDir/inputcontrols/icons/
     * Hasilnya digabung, deduplikasi, lalu diurutkan numerik secara ascending.
     */
    private byte[] loadAllIconIds() {
        List<Byte> ids = new ArrayList<>();

        // 1. Asset bawaan
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

        // 2. Folder import pengguna (filesDir/inputcontrols/icons/)
        File iconsDir = InputControlsManager.getIconsDir(this);
        File[] files = iconsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String base = FileUtils.getBasename(f.getName());
                try {
                    // ID dari folder import bisa > 127, tapi byte di sini; gunakan int dulu lalu cast
                    int idInt = Integer.parseInt(base);
                    if (idInt >= Byte.MIN_VALUE && idInt <= Byte.MAX_VALUE) {
                        byte id = (byte) idInt;
                        if (!ids.contains(id)) ids.add(id);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // Urutkan (byte unsigned sort agar 1,2,...,30,31 tetap urut)
        ids.sort((a, b) -> Byte.toUnsignedInt(a) - Byte.toUnsignedInt(b));

        byte[] result = new byte[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    /**
     * Membuka InputStream untuk icon dengan ID tertentu.
     * Urutan prioritas:
     *  1. Asset bawaan   : assets/inputcontrols/icons/<id>.png
     *  2. Import pengguna: filesDir/inputcontrols/icons/<id>.(png|jpg|jpeg|webp)
     * Mengembalikan null jika tidak ditemukan di kedua sumber.
     */
    private InputStream openIconStream(byte id) {
        // 1. Asset bawaan
        try {
            return getAssets().open("inputcontrols/icons/" + id + ".png");
        } catch (IOException ignored) {}

        // 2. Folder import pengguna
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

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }
    
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(context));
    }
    
}
