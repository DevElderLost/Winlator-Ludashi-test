package com.winlator.cmod.saves;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;

import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final List<FolderPickerDialog.FileItem> items;
    private final Consumer<FolderPickerDialog.FileItem> onClickListener;

    public FileAdapter(List<FolderPickerDialog.FileItem> items, Consumer<FolderPickerDialog.FileItem> onClickListener) {
        this.items = items;
        this.onClickListener = onClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FolderPickerDialog.FileItem item = items.get(position);
        holder.tvName.setText(item.name);

        if (item.isUp) {
            holder.ivIcon.setImageResource(R.drawable.icon_open); // atau ic_folder_up
            holder.tvName.setTextColor(holder.itemView.getContext().getColor(R.color.blue_700));
        } else {
            holder.ivIcon.setImageResource(R.drawable.icon_open);
        }

        holder.itemView.setOnClickListener(v -> onClickListener.accept(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
        }
    }
}