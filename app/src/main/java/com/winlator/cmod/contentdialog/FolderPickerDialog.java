package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.saves.FileAdapter;
import com.winlator.cmod.core.AppUtils;

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
//        setIcon(R.drawable.ic_folder); // sesuaikan drawable jika perlu

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

        // Tambahkan ".." jika bukan root
        File parent = directory.getParentFile();
        if (parent != null && parent.exists()) {
            items.add(new FileItem("..", parent, true, true)); // isUp = true
        }

        // Ambil semua file & folder di direktori saat ini
        File[] files = directory.listFiles();
        if (files != null) {
            // Sort: folder dulu, baru file; abjad
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            for (File file : files) {
                // Hanya tampilkan folder (bukan file biasa)
                if (file.isDirectory()) {
                    items.add(new FileItem(file.getName(), file, true, false));
                }
            }
        }

        fileAdapter = new FileAdapter(items, this::onItemClicked);
        recyclerView.setAdapter(fileAdapter);
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

    private void updatePathDisplay() {
        if (tvCurrentPath != null) {
            tvCurrentPath.setText(currentDirectory.getAbsolutePath());
        }
    }

    public void setOnFolderSelectedListener(Consumer<String> listener) {
        this.onFolderSelectedListener = listener;
    }

    // Helper class sederhana
    public static class FileItem {
        String name;
        File file;
        boolean isDirectory;
        boolean isUp;

        FileItem(String name, File file, boolean isDirectory, boolean isUp) {
            this.name = name;
            this.file = file;
            this.isDirectory = isDirectory;
            this.isUp = isUp;
        }
    }
}
