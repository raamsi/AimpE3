package com.ramsi.aimpe3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public class EditorAdapter extends BaseAdapter {

    private Context context;
    private ArrayList<EditorItem> items;
    private int selectedIndex = 0;

    public EditorAdapter(Context context, ArrayList<EditorItem> items) {
        this.context = context;
        this.items = items;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items != null ? items.size() : 0;
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
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_editor_row, parent, false);
        }

        TextView tvTime = (TextView) convertView.findViewById(R.id.tvRowTime);
        TextView tvText = (TextView) convertView.findViewById(R.id.tvRowText);

        EditorItem item = items.get(position);
        if (tvTime != null) {
            tvTime.setText(formatTimestamp(item.timeMs));
        }
        if (tvText != null) {
            tvText.setText(item.text);
        }

        if (position == selectedIndex) {
            convertView.setBackgroundColor(0xFFFFAA00); // Fond orange sélectionné
            if (tvTime != null) tvTime.setTextColor(0xFF0B0B10);
            if (tvText != null) tvText.setTextColor(0xFF0B0B10);
        } else {
            convertView.setBackgroundColor(0xFF1E1E24); // Fond sombre par défaut
            if (tvTime != null) tvTime.setTextColor(0xFFAAAAAA);
            if (tvText != null) tvText.setTextColor(0xFFFFFFFF);
        }

        return convertView;
    }

    private String formatTimestamp(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        int millis = (ms % 1000);
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, millis);
    }
}

