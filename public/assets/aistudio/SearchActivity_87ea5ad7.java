package com.ramsi.aimpe3;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;

public class SearchActivity extends Activity {

    private EditText etUrlInput;
    private Button btnNativeDownload;
    private ListView listViewNative;
    private ArrayList<String> historyList;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        etUrlInput = (EditText) findViewById(R.id.etUrlInput);
        btnNativeDownload = (Button) findViewById(R.id.btnNativeDownload);
        listViewNative = (ListView) findViewById(R.id.listViewNative);

        // Historique / Exemples de flux natifs
        historyList = new ArrayList<String>();
        historyList.add("Exemple Audio HD - SoundHelix 1");

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyList);
        if (listViewNative != null) {
            listViewNative.setAdapter(adapter);
        }

        // Action du bouton de téléchargement natif
        if (btnNativeDownload != null) {
            btnNativeDownload.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (etUrlInput != null) {
                            String urlText = etUrlInput.getText().toString().trim();

                            if (!urlText.isEmpty()) {
                                // Si l'utilisateur entre un lien direct
                                String targetUrl = urlText;
                                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                                    // Si c'est juste un mot-clé, on utilise un flux audio de test par défaut
                                    targetUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3";
                                }

                                startNativeDownload(targetUrl, "AimpE3_Media_" + System.currentTimeMillis());
                                etUrlInput.setText("");
                            } else {
                                Toast.makeText(SearchActivity.this, "Veuillez entrer un lien valide", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                });
        }
    }

    private void startNativeDownload(String url, String fileName) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Téléchargement natif en arrière-plan...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // Enregistrement direct dans le dossier Musique de l'appareil
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, fileName + ".mp3");

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                Toast.makeText(this, "Téléchargement démarré en arrière-plan !", Toast.LENGTH_LONG).show();

                // Ajouter à la liste locale
                historyList.add(fileName + " (.mp3)");
                adapter.notifyDataSetChanged();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

