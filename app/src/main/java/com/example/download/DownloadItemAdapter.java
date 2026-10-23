package com.example.download;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.example.R;
import java.util.List;
import java.util.Locale;

public class DownloadItemAdapter extends BaseAdapter {

    public interface OnItemActionListener {
        void onPlay(DownloadItem item);
        void onCancel(DownloadItem item);
        void onRetry(DownloadItem item);
        void onDelete(DownloadItem item);
    }

    private final Context context;
    private final List<DownloadItem> items;
    private final LayoutInflater inflater;
    private final OnItemActionListener actionListener;

    public DownloadItemAdapter(Context context, List<DownloadItem> items, OnItemActionListener listener) {
        this.context = context;
        this.items = items;
        this.inflater = LayoutInflater.from(context);
        this.actionListener = listener;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public DownloadItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).getDownloadId();
    }

    private static class ViewHolder {
        TextView tvIcon;
        TextView tvTitle;
        TextView tvSubtitle;
        TextView tvStatusBadge;
        ProgressBar pbProgress;
        TextView tvDetails;
        Button btnPlay;
        Button btnCancel;
        Button btnRetry;
        Button btnDelete;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_download, parent, false);
            holder = new ViewHolder();
            holder.tvIcon = (TextView) convertView.findViewById(R.id.tvItemIcon);
            holder.tvTitle = (TextView) convertView.findViewById(R.id.tvItemTitle);
            holder.tvSubtitle = (TextView) convertView.findViewById(R.id.tvItemSubtitle);
            holder.tvStatusBadge = (TextView) convertView.findViewById(R.id.tvItemStatusBadge);
            holder.pbProgress = (ProgressBar) convertView.findViewById(R.id.pbItemProgress);
            holder.tvDetails = (TextView) convertView.findViewById(R.id.tvItemDetails);
            holder.btnPlay = (Button) convertView.findViewById(R.id.btnItemPlay);
            holder.btnCancel = (Button) convertView.findViewById(R.id.btnItemCancel);
            holder.btnRetry = (Button) convertView.findViewById(R.id.btnItemRetry);
            holder.btnDelete = (Button) convertView.findViewById(R.id.btnItemDelete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final DownloadItem item = getItem(position);

        holder.tvTitle.setText(item.getTitle());
        holder.tvSubtitle.setText(item.getFileName());

        // Status badge & Progress
        switch (item.getStatus()) {
            case DownloadItem.STATUS_PENDING:
                holder.tvStatusBadge.setText("En attente");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#64B5F6"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#102A43"));
                holder.pbProgress.setVisibility(View.VISIBLE);
                holder.pbProgress.setIndeterminate(true);
                holder.tvDetails.setText("Connexion au serveur audio...");
                holder.btnPlay.setVisibility(View.GONE);
                holder.btnCancel.setVisibility(View.VISIBLE);
                holder.btnRetry.setVisibility(View.GONE);
                break;

            case DownloadItem.STATUS_RUNNING:
                holder.tvStatusBadge.setText("En cours " + item.getProgress() + "%");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#FFA000"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#332200"));
                holder.pbProgress.setVisibility(View.VISIBLE);
                holder.pbProgress.setIndeterminate(false);
                holder.pbProgress.setProgress(item.getProgress());
                holder.tvDetails.setText(formatProgressDetails(item.getDownloadedBytes(), item.getTotalBytes()));
                holder.btnPlay.setVisibility(View.GONE);
                holder.btnCancel.setVisibility(View.VISIBLE);
                holder.btnRetry.setVisibility(View.GONE);
                break;

            case DownloadItem.STATUS_PAUSED:
                holder.tvStatusBadge.setText("En pause");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#FFCC80"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#332200"));
                holder.pbProgress.setVisibility(View.VISIBLE);
                holder.pbProgress.setIndeterminate(false);
                holder.pbProgress.setProgress(item.getProgress());
                holder.tvDetails.setText("Téléchargement suspendu");
                holder.btnPlay.setVisibility(View.GONE);
                holder.btnCancel.setVisibility(View.VISIBLE);
                holder.btnRetry.setVisibility(View.GONE);
                break;

            case DownloadItem.STATUS_SUCCESSFUL:
                holder.tvStatusBadge.setText("✓ Terminé");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#1DB954"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#0F381E"));
                holder.pbProgress.setVisibility(View.GONE);
                holder.tvDetails.setText("Prêt à écouter • " + formatBytes(item.getTotalBytes()));
                holder.btnPlay.setVisibility(View.VISIBLE);
                holder.btnCancel.setVisibility(View.GONE);
                holder.btnRetry.setVisibility(View.GONE);
                break;

            case DownloadItem.STATUS_FAILED:
                holder.tvStatusBadge.setText("✕ Échec");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#E53935"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#3E1010"));
                holder.pbProgress.setVisibility(View.GONE);
                String err = item.getErrorMessage();
                holder.tvDetails.setText(err != null && !err.isEmpty() ? err : "Erreur de téléchargement");
                holder.btnPlay.setVisibility(View.GONE);
                holder.btnCancel.setVisibility(View.GONE);
                holder.btnRetry.setVisibility(View.VISIBLE);
                break;

            case DownloadItem.STATUS_CANCELLED:
                holder.tvStatusBadge.setText("Annulé");
                holder.tvStatusBadge.setTextColor(Color.parseColor("#9E9E9E"));
                holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#252525"));
                holder.pbProgress.setVisibility(View.GONE);
                holder.tvDetails.setText("Annulé par l'utilisateur");
                holder.btnPlay.setVisibility(View.GONE);
                holder.btnCancel.setVisibility(View.GONE);
                holder.btnRetry.setVisibility(View.VISIBLE);
                break;
        }

        // Action button listeners
        holder.btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onPlay(item);
            }
        });

        holder.btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onCancel(item);
            }
        });

        holder.btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onRetry(item);
            }
        });

        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onDelete(item);
            }
        });

        return convertView;
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "-- Mo";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.FRANCE, "%.1f Mo", mb);
    }

    private String formatProgressDetails(long downloaded, long total) {
        if (total > 0) {
            double dlMb = downloaded / (1024.0 * 1024.0);
            double totMb = total / (1024.0 * 1024.0);
            return String.format(Locale.FRANCE, "%.1f Mo / %.1f Mo", dlMb, totMb);
        } else if (downloaded > 0) {
            double dlMb = downloaded / (1024.0 * 1024.0);
            return String.format(Locale.FRANCE, "%.1f Mo téléchargés", dlMb);
        }
        return "Calcul de la taille...";
    }
}
