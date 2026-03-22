package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.saves.FileAdapter;
import com.winlator.cmod.saves.FileItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class FolderPickerDialog extends ContentDialog {

    private File currentDirectory;
    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private TextView tvCurrentPath;
    private Consumer<String> onFolderSelectedListener;

    public FolderPickerDialog(Activity activity, String initialPath) {
        super(activity, R.layout.dialog_folder_picker);
        setTitle(R.string.select_folder);
//        setIcon(R.drawable.ic_folder);  // sesuaikan jika ikonnya berbeda

        currentDirectory = new File(initialPath);
        if (!currentDirectory.exists() || !currentDirectory.isDirectory()) {
            currentDirectory = new File("/");
        }

        initViews();
        loadFiles(currentDirectory);

        setOnConfirmCallback(() -> {
            if (onFolderSelectedListener != null) {
                onFolderSelectedListener.accept(currentDirectory.getAbsolutePath());
            }
            dismiss();
        });

        setOnCancelCallback(this::dismiss);
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        tvCurrentPath = findViewById(R.id.tvCurrentPath);

        updatePathDisplay();
    }

    private void loadFiles(File directory) {
        if (directory == null || !directory.exists()) {
            AppUtils.showToast(getContext(), "Direktori tidak valid");
            dismiss();
            return;
        }

        currentDirectory = directory;
        updatePathDisplay();

        List<FileItem> items = new ArrayList<>();

        // Tambahkan ".." jika bukan di root
        File parent = directory.getParentFile();
        if (parent != null && parent.exists()) {
            items.add(new FileItem("..", parent, true, true));
        }

        // Ambil semua isi direktori
        File[] files = directory.listFiles();
        if (files != null) {
            // Sort: folder dulu, kemudian urut nama
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            for (File file : files) {
                if (file.isDirectory()) {
                    items.add(new FileItem(file.getName(), file, true));
                }
            }
        }

        fileAdapter = new FileAdapter(items, this::onItemClicked);
        recyclerView.setAdapter(fileAdapter);

        adjustRecyclerViewHeight();
    }

    private void onItemClicked(FileItem item) {
        if (item.isUp) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && parent.exists()) {
                loadFiles(parent);
            }
            return;
        }

        if (item.file.isDirectory()) {
            loadFiles(item.file);
        }
    }

    private void adjustRecyclerViewHeight() {
    if (fileAdapter == null) return;

    int itemCount = fileAdapter.getItemCount();
    int desiredItemCount = Math.min(itemCount, 8); // maksimal tampil 8 item tanpa scroll
    int itemHeightApprox = (int) (56 * getContext().getResources().getDisplayMetrics().density); // \~56dp per item

    int targetHeight = desiredItemCount * itemHeightApprox + 32; // + padding

    // Batasi maksimal
    int maxAllowed = (int) (360 * getContext().getResources().getDisplayMetrics().density);
    targetHeight = Math.min(targetHeight, maxAllowed);

    // Set minimal juga
    int minHeight = (int) (160 * getContext().getResources().getDisplayMetrics().density);
    targetHeight = Math.max(targetHeight, minHeight);

    RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) recyclerView.getLayoutParams();
    params.height = targetHeight;
    recyclerView.setLayoutParams(params);
    }

    private void updatePathDisplay() {
        if (tvCurrentPath != null) {
            tvCurrentPath.setText(currentDirectory.getAbsolutePath());
        }
    }

    public void setOnFolderSelectedListener(Consumer<String> listener) {
        this.onFolderSelectedListener = listener;
    }
}
