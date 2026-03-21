package com.winlator.cmod.saves;  // ← sesuaikan package jika perlu

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;

import java.util.List;
import java.util.function.Consumer;
import java.io.File;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final List<FileItem> items;
    private final Consumer<FileItem> onItemClickListener;

    public FileAdapter(List<FileItem> items, Consumer<FileItem> onItemClickListener) {
        this.items = items;
        this.onItemClickListener = onItemClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileItem item = items.get(position);

        holder.tvName.setText(item.name);

        if (item.isUp) {
            holder.ivIcon.setImageResource(R.drawable.ic_open);     // atau ic_chevron_up
//            holder.tvName.setTextColor(holder.itemView.getContext().getColor(R.color.blue_600));  // warna berbeda biar kelihatan spesial
        } else {
            holder.ivIcon.setImageResource(R.drawable.icon_open);           // ikon folder biasa
            holder.tvName.setTextColor(holder.itemView.getContext()
                    .getColor(android.R.color.primary_text_light));
        }

        holder.itemView.setOnClickListener(v -> onItemClickListener.accept(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
        }
    }

    // Kelas FileItem tetap sama seperti di FolderPickerDialog
    public static class FileItem {
        public final String name;
        public final File file;
        public final boolean isDirectory;
        public final boolean isUp;

        public FileItem(String name, File file, boolean isDirectory, boolean isUp) {
            this.name = name;
            this.file = file;
            this.isDirectory = isDirectory;
            this.isUp = isUp;
        }
    }
}
