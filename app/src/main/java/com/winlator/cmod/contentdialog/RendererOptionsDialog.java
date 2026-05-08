package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.AdrenotoolsManager;

import java.util.ArrayList;
import java.util.List;

public class RendererOptionsDialog extends ContentDialog {

    private final boolean isNativeMode;

    private void setGroupVisibility(int id, int vis) {
        View v = findViewById(id);
        if (v != null) v.setVisibility(vis);
    }

    public interface Config {
        boolean getRendererNative();
        void setRendererNative(boolean v);

        String getRendererPresentMode();
        void setRendererPresentMode(String v);

        String getRendererDriverId();
        void setRendererDriverId(String v);

        int getRendererFilterMode();
        void setRendererFilterMode(int v);

        boolean getRendererSwapRB();
        void setRendererSwapRB(boolean v);
    }

    private static final String[] PRESENT_MODE_IDS    = {"mailbox", "fifo", "shared_demand"};
    private static final String[] PRESENT_MODE_LABELS = {
        "Mailbox",
        "Fifo",
        "Shared Demand"
    };

    private static final String[] FILTER_LABELS = {
        "Bilinear",
        "Nearest neighbor",
        "Bicubic"
    };

    public RendererOptionsDialog(View anchorView, Config config, boolean isNativeMode) {
        super(anchorView.getContext(), R.layout.renderer_options_dialog);
        this.isNativeMode = isNativeMode;

        Context ctx = anchorView.getContext();

        Spinner  spPresent = findViewById(R.id.SPRendererPresentMode);
        Spinner  spDriver  = findViewById(R.id.SPRendererDriver);
        Spinner  spFilter  = findViewById(R.id.SPRendererFilter);
        CheckBox cbSwapRB  = findViewById(R.id.CBRendererSwapRB);

        // Keep driver and filter controls available in both Vulkan and native modes.
        setGroupVisibility(R.id.GroupDriver,  View.VISIBLE);
        setGroupVisibility(R.id.GroupFilter,  View.VISIBLE);

        // Present Mode (visible in both modes)
        spPresent.setAdapter(new ArrayAdapter<>(ctx,
            android.R.layout.simple_spinner_dropdown_item, PRESENT_MODE_LABELS));
        int pmSel = 0;
        String curPm = config.getRendererPresentMode();
        for (int i = 0; i < PRESENT_MODE_IDS.length; i++) {
            if (PRESENT_MODE_IDS[i].equals(curPm)) { pmSel = i; break; }
        }
        spPresent.setSelection(pmSel);

        // Renderer Driver
        AdrenotoolsManager atm = new AdrenotoolsManager(ctx);
        List<String> driverLabels = new ArrayList<>();
        List<String> driverIds    = new ArrayList<>();
        driverLabels.add("Wrapper"); driverIds.add("");
        driverLabels.add("System");  driverIds.add("system");
        for (String id : atm.enumarateInstalledDrivers()) {
            driverLabels.add(atm.getDriverName(id) + " " + atm.getDriverVersion(id));
            driverIds.add(id);
        }
        spDriver.setAdapter(new ArrayAdapter<>(ctx,
            android.R.layout.simple_spinner_dropdown_item, driverLabels));
        String curDrv = config.getRendererDriverId();
        int drvSel = 0;
        for (int i = 0; i < driverIds.size(); i++) {
            if (driverIds.get(i).equals(curDrv)) { drvSel = i; break; }
        }
        spDriver.setSelection(drvSel);

        // Texture Filter
        spFilter.setAdapter(new ArrayAdapter<>(ctx,
            android.R.layout.simple_spinner_dropdown_item, FILTER_LABELS));
        spFilter.setSelection(config.getRendererFilterMode());
        cbSwapRB.setChecked(config.getRendererSwapRB());

        // Save on confirm
        setOnConfirmCallback(() -> {
            config.setRendererPresentMode(PRESENT_MODE_IDS[spPresent.getSelectedItemPosition()]);
            config.setRendererDriverId(driverIds.get(spDriver.getSelectedItemPosition()));
            config.setRendererFilterMode(spFilter.getSelectedItemPosition());
            config.setRendererSwapRB(cbSwapRB.isChecked());
        });
    }

    public static int toVkPresentMode(String mode) {
        if (mode == null) return 2;
        switch (mode) {
            case "mailbox":       return 1;
            case "shared_demand": return 1000111000;
            default:              return 2;
        }
    }
}
