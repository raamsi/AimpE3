package com.ramsi.aimpe3;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public class AudioAdapter extends BaseAdapter {

    private Context context;
    private ArrayList<AudioItem> items;
    private LayoutInflater inflater;

    public AudioAdapter(Context context, ArrayList<AudioItem> items) {
        this.context = context;
        this.items = items;
        this.inflater = LayoutInflater.from(context);
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
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_search_result, parent, false);
        }

        final AudioItem item = items.get(position);

        TextView tvTitle = (TextView) convertView.findViewById(R.id.tvTitle);
        TextView tvSubtitle = (TextView) convertView.findViewById(R.id.tvSubtitle);
        Button btnDownloadItem = (Button) convertView.findViewById(R.id.btnDownloadItem);

        tvTitle.setText(item.getTitle());
        tvSubtitle.setText(item.getSubtitle());

        // Action de téléchargement au clic sur le bouton de la carte
        btnDownloadItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        // Lien de test direct (ou URL du fichier audio à récupérer)
                        String audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3";

                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(audioUrl));
                        request.setTitle(item.getTitle());
                        request.setDescription("Téléchargement de l'audio en cours...");
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                        // Enregistrement direct dans le dossier Musique public de l'appareil
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, item.getTitle() + ".mp3");

                        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                        if (manager != null) {
                            manager.enqueue(request);
                            Toast.makeText(context, "Téléchargement lancé : " + item.getTitle(), Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(context, "Erreur de téléchargement : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

        return convertView;
    }
}

