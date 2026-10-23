package com.example.download;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.example.R;
import java.util.List;

public class SearchResultAdapter extends BaseAdapter {

    public interface OnSearchResultActionListener {
        void onPreview(SearchResultItem item);
        void onDownload(SearchResultItem item);
    }

    private final Context context;
    private final List<SearchResultItem> items;
    private final OnSearchResultActionListener listener;
    private String currentlyPreviewingId = null;

    public SearchResultAdapter(Context context, List<SearchResultItem> items, OnSearchResultActionListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
    }

    public void setCurrentlyPreviewingId(String id) {
        this.currentlyPreviewingId = id;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_search_result, parent, false);
            holder = new ViewHolder();
            holder.ivCover = (ImageView) convertView.findViewById(R.id.ivResultCover);
            holder.tvTitle = (TextView) convertView.findViewById(R.id.tvResultTitle);
            holder.tvArtist = (TextView) convertView.findViewById(R.id.tvResultArtist);
            holder.tvDuration = (TextView) convertView.findViewById(R.id.tvResultDuration);
            holder.tvQuality = (TextView) convertView.findViewById(R.id.tvResultQuality);
            holder.tvSource = (TextView) convertView.findViewById(R.id.tvResultSource);
            holder.btnPreview = (Button) convertView.findViewById(R.id.btnResultPreview);
            holder.btnDownload = (Button) convertView.findViewById(R.id.btnResultDownload);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final SearchResultItem item = items.get(position);

        holder.tvTitle.setText(item.getTitle());
        holder.tvArtist.setText(item.getArtist());
        holder.tvDuration.setText(item.getDuration());
        holder.tvQuality.setText(item.getQualityBadge());
        holder.tvSource.setText(item.getSource() + " • " + item.getFormattedSize());

        // Load album cover
        ImageLoaderHelper.load(item.getCoverUrl(), holder.ivCover, android.R.drawable.ic_media_play);

        // Preview button state
        final boolean isPlaying = item.getId().equals(currentlyPreviewingId);
        if (isPlaying) {
            holder.btnPreview.setText("⏸ Pause");
            holder.btnPreview.setBackgroundColor(Color.parseColor("#1DB954"));
        } else {
            holder.btnPreview.setText("▶ Aperçu");
            holder.btnPreview.setBackgroundColor(Color.parseColor("#2C2C2C"));
        }

        holder.btnPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPreview(item);
                }
            }
        });

        holder.btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDownload(item);
                }
            }
        });

        return convertView;
    }

    private static class ViewHolder {
        ImageView ivCover;
        TextView tvTitle;
        TextView tvArtist;
        TextView tvDuration;
        TextView tvQuality;
        TextView tvSource;
        Button btnPreview;
        Button btnDownload;
    }
}
