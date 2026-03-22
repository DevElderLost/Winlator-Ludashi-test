package com.winlator.cmod.saves;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Paint;
import android.graphics.Canvas;

import androidx.core.content.ContextCompat;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;

import java.util.List;
import java.util.function.Consumer;

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
            holder.ivIcon.setImageResource(R.drawable.icon_open);  // atau ic_chevron_up
            holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.colorPrimary), PorterDuff.Mode.SRC_IN);
            holder.tvName.setTextColor(holder.itemView.getContext().getColor(android.R.color.primary_text_light));
            
        } else {
            holder.ivIcon.setImageResource(R.drawable.icon_open);
            holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.colorPrimary), PorterDuff.Mode.SRC_IN);
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
}
