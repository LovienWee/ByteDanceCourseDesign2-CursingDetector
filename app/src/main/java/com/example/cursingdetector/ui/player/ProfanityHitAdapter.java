package com.example.cursingdetector.ui.player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cursingdetector.R;
import com.example.cursingdetector.model.ProfanityHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfanityHitAdapter extends RecyclerView.Adapter<ProfanityHitAdapter.HitViewHolder> {

    public interface OnHitClickListener {
        void onHitClick(ProfanityHit hit);
    }

    private List<ProfanityHit> data = new ArrayList<>();
    private OnHitClickListener listener;

    public ProfanityHitAdapter(OnHitClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<ProfanityHit> hits) {
        data.clear();
        if (hits != null) {
            data.addAll(hits);
        }
        notifyDataSetChanged();
    }

    public void addHit(ProfanityHit hit) {
        data.add(hit);
        notifyItemInserted(data.size() - 1);
    }

    @NonNull
    @Override
    public HitViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_profanity_hit, parent, false);
        return new HitViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HitViewHolder holder, int position) {
        ProfanityHit hit = data.get(position);
        String time = formatTime(hit.startSec) + " - " + formatTime(hit.endSec);
        holder.tvWordTime.setText(hit.word + "   " + time);
        holder.tvLineText.setText(hit.lineText);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onHitClick(hit);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class HitViewHolder extends RecyclerView.ViewHolder {
        TextView tvWordTime;
        TextView tvLineText;

        public HitViewHolder(@NonNull View itemView) {
            super(itemView);
            tvWordTime = itemView.findViewById(R.id.tv_word_time);
            tvLineText = itemView.findViewById(R.id.tv_line_text);
        }
    }

    private String formatTime(double sec) {
        int totalMs = (int) (sec * 1000);
        int totalSeconds = totalMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        int ms = totalMs % 1000 / 100; // 显示一位小数

        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, ms);
    }
}
