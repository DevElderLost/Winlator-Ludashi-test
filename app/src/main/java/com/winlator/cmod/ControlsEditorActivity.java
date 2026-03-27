package com.winlator.cmod;

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

import androidx.appcompat.app.AppCompatActivity;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {

    private InputControlsView inputControlsView;
    private ControlsProfile profile;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
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
                loadSlotIconsUI(view, element, new String[]{"Icon Up", "Icon Right", "Icon Down", "icon Left"}, 4);
            }
            else if (type == ControlElement.Type.STICK || type == ControlElement.Type.RIGHT_STICK) {
                view.findViewById(R.id.LLBindings).setVisibility(View.VISIBLE);
                view.findViewById(R.id.LLSlotIcons).setVisibility(View.VISIBLE);
                loadSlotIconsUI(view, element, new String[]{"Outer Thumb", "Inner Thumb"}, 2);
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
     * Membangun UI pemilih icon per-slot di dalam LLSlotIcons.
     * slotLabels: nama tiap slot (misal "↑ Up", "→ Right", dll)
     * slotCount: jumlah slot yang ditampilkan
     */
    private void loadSlotIconsUI(View settingsView, ControlElement element, String[] slotLabels, int slotCount) {
        LinearLayout llSlotIcons = settingsView.findViewById(R.id.LLSlotIcons);
        llSlotIcons.removeAllViews();

        byte[] availableIconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            availableIconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                availableIconIds[i] = Byte.parseByte(com.winlator.cmod.core.FileUtils.getBasename(filenames[i]));
            }
        } catch (IOException e) {}
        java.util.Arrays.sort(availableIconIds);

        int iconSize = (int) com.winlator.cmod.core.UnitUtils.dpToPx(36);
        int iconMargin = (int) com.winlator.cmod.core.UnitUtils.dpToPx(2);
        int iconPadding = (int) com.winlator.cmod.core.UnitUtils.dpToPx(3);

        for (int slot = 0; slot < slotCount; slot++) {
            // Row container per slot
            LinearLayout slotRow = new LinearLayout(this);
            slotRow.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, (int) com.winlator.cmod.core.UnitUtils.dpToPx(4), 0, 0);
            slotRow.setLayoutParams(rowParams);

            // Label nama slot
            TextView tvLabel = new TextView(this);
            tvLabel.setText(slotLabels[slot]);
            tvLabel.setTextSize(12);
            tvLabel.setPadding(0, 0, 0, (int) com.winlator.cmod.core.UnitUtils.dpToPx(2));
            slotRow.addView(tvLabel);

            // Horizontal scroll untuk daftar icon
            android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
            hsv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout iconRow = new LinearLayout(this);
            iconRow.setOrientation(LinearLayout.HORIZONTAL);

            final byte currentSlotIcon = element.getSlotIconId(slot);
            final int finalSlot = slot;

            // Opsi "None" (tidak ada icon)
            ImageView noneView = new ImageView(this);
            LinearLayout.LayoutParams noneParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            noneParams.setMargins(iconMargin, 0, iconMargin, 0);
            noneView.setLayoutParams(noneParams);
            noneView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            noneView.setBackgroundResource(R.drawable.icon_background);
            noneView.setTag((byte) 0);
            noneView.setSelected(currentSlotIcon == 0);
            noneView.setOnClickListener(v -> {
                for (int c = 0; c < iconRow.getChildCount(); c++) iconRow.getChildAt(c).setSelected(false);
                noneView.setSelected(true);
                element.setSlotIconId(finalSlot, (byte) 0);
                profile.save();
                inputControlsView.invalidate();
            });
            iconRow.addView(noneView);

            // Daftar icon dari assets
            for (final byte id : availableIconIds) {
                ImageView imageView = new ImageView(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSize, iconSize);
                params.setMargins(iconMargin, 0, iconMargin, 0);
                imageView.setLayoutParams(params);
                imageView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
                imageView.setBackgroundResource(R.drawable.icon_background);
                imageView.setTag(id);
                imageView.setSelected(id == currentSlotIcon);
                imageView.setOnClickListener(v -> {
                    for (int c = 0; c < iconRow.getChildCount(); c++) iconRow.getChildAt(c).setSelected(false);
                    imageView.setSelected(true);
                    element.setSlotIconId(finalSlot, id);
                    profile.save();
                    inputControlsView.invalidate();
                });
                try (java.io.InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                    Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                    imageView.setImageBitmap(bmp);
                    applyIconBackground(imageView, bmp);
                } catch (IOException e) {
                    imageView.setBackgroundResource(R.drawable.icon_background);
                }
                iconRow.addView(imageView);
            }

            hsv.addView(iconRow);
            slotRow.addView(hsv);
            llSlotIcons.addView(slotRow);
        }
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

    /**
     * Menganalisis dominasi warna pada bitmap icon.
     * Jika icon didominasi warna putih/putih-transparan → background diganti hitam.
     * Jika icon didominasi warna hitam/hitam-transparan atau lainnya → background default.
     */
    private void applyIconBackground(ImageView imageView, Bitmap bitmap) {
        if (bitmap == null) {
            imageView.setBackgroundResource(R.drawable.icon_background);
            return;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long totalVisible = 0;
        long whitishPixels = 0;

        // Sampling setiap 2 pixel agar lebih efisien
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int pixel = bitmap.getPixel(x, y);
                int alpha = Color.alpha(pixel);

                // Abaikan pixel yang hampir transparan penuh (alpha < 30)
                if (alpha < 30) continue;

                totalVisible++;

                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                // Anggap "putih/putih-transparan": semua channel tinggi (>= 200)
                // dan tidak terlalu gelap
                if (r >= 200 && g >= 200 && b >= 200) {
                    whitishPixels++;
                }
            }
        }

        if (totalVisible > 0 && (whitishPixels * 100 / totalVisible) >= 60) {
            // Mayoritas pixel putih → pakai background hitam
            imageView.setBackgroundColor(Color.BLACK);
        } else {
            // Hitam, gelap, atau warna lain → pakai background default
            imageView.setBackgroundResource(R.drawable.icon_background);
        }
    }

    private void loadIcons(final LinearLayout parent, byte selectedId) {
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        } catch (IOException e) {}

        Arrays.sort(iconIds);

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

            try (InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                imageView.setImageBitmap(bmp);
                applyIconBackground(imageView, bmp);
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
}
