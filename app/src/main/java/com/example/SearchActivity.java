package com.example;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.compose.ui.platform.ComposeView;
import com.example.download.AudioDownloadManager;
import com.example.download.DownloadItem;
import com.example.download.ImageLoaderHelper;
import com.example.download.MusicSearchEngine;
import com.example.download.SearchResultAdapter;
import com.example.download.SearchResultItem;
import com.example.download.work.SnaptubeDownloadManagerBridge;
import com.example.download.work.WorkDownloadManager;
import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends ComponentActivity implements
        SearchResultAdapter.OnSearchResultActionListener {

    private static final int TAB_SEARCH = 0;
    private static final int TAB_DOWNLOADS = 1;
    private int currentTab = TAB_SEARCH;

    private AudioDownloadManager downloadManager;
    private WorkDownloadManager workDownloadManager;

    // Header & Tabs
    private Button btnBack;
    private Button btnClearCompleted;
    private Button tabSearchOnline;
    private Button tabMyDownloads;
    private LinearLayout viewSearchOnline;
    private LinearLayout viewMyDownloads;
    private ComposeView composeDownloadManager;

    // Search elements
    private EditText etSearchQuery;
    private Button btnClearSearch;
    private Button btnSubmitSearch;
    private ProgressBar pbSearchProgress;
    private TextView tvSearchResultsHeader;
    private TextView tvSearchCount;
    private ListView lvSearchResults;
    private Button btnQuickPasteUrl;

    // Category chips
    private TextView chipTrend, chipPop, chipRock, chipLofi, chipPiano, chipElectro, chipAcoustic, chipClassical;

    // Search Results State
    private SearchResultAdapter searchResultAdapter;
    private final List<SearchResultItem> searchResults = new ArrayList<SearchResultItem>();

    // Mini Preview Player (Snaptube-like bottom bar)
    private LinearLayout layoutPreviewBar;
    private ProgressBar pbPreviewProgress;
    private ImageView ivPreviewCover;
    private TextView tvPreviewTitle;
    private TextView tvPreviewArtist;
    private Button btnPreviewPlayPause;
    private Button btnPreviewDownloadNow;
    private Button btnPreviewClose;
    private MediaPlayer previewPlayer;
    private SearchResultItem currentPreviewItem;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable previewProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewPlayer != null && previewPlayer.isPlaying()) {
                int current = previewPlayer.getCurrentPosition();
                int total = previewPlayer.getDuration();
                if (total > 0) {
                    pbPreviewProgress.setProgress((int) ((current * 100L) / total));
                }
                handler.postDelayed(this, 400);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        downloadManager = AudioDownloadManager.getInstance(this);
        workDownloadManager = WorkDownloadManager.Companion.getInstance(this);

        initViews();
        setupTabs();
        setupSearchSection();
        setupChips();
        setupPreviewBar();

        searchResultAdapter = new SearchResultAdapter(this, searchResults, this);
        lvSearchResults.setAdapter(searchResultAdapter);

        // Attach WorkManager Compose component into the ComposeView
        if (composeDownloadManager != null) {
            SnaptubeDownloadManagerBridge.attachTo(
                composeDownloadManager,
                workDownloadManager,
                new SnaptubeDownloadManagerBridge.OnTrackPlayListener() {
                    @Override
                    public void onPlayTrack(String filePath, String title) {
                        stopPreview();
                        MainActivity.playSongFromPathStatic(SearchActivity.this, filePath, title);
                        finish();
                    }
                }
            );
        }

        requestNotificationPermissionIfNeeded();

        // Load default trending songs
        loadTrendingSongs();
        refreshDownloadsBadge();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDownloadsBadge();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
    }

    private void initViews() {
        btnBack = (Button) findViewById(R.id.btnBack);
        btnClearCompleted = (Button) findViewById(R.id.btnClearCompleted);
        tabSearchOnline = (Button) findViewById(R.id.tabSearchOnline);
        tabMyDownloads = (Button) findViewById(R.id.tabMyDownloads);
        viewSearchOnline = (LinearLayout) findViewById(R.id.viewSearchOnline);
        viewMyDownloads = (LinearLayout) findViewById(R.id.viewMyDownloads);
        composeDownloadManager = (ComposeView) findViewById(R.id.composeDownloadManager);

        etSearchQuery = (EditText) findViewById(R.id.etSearchQuery);
        btnClearSearch = (Button) findViewById(R.id.btnClearSearch);
        btnSubmitSearch = (Button) findViewById(R.id.btnSubmitSearch);
        pbSearchProgress = (ProgressBar) findViewById(R.id.pbSearchProgress);
        tvSearchResultsHeader = (TextView) findViewById(R.id.tvSearchResultsHeader);
        tvSearchCount = (TextView) findViewById(R.id.tvSearchCount);
        lvSearchResults = (ListView) findViewById(R.id.lvSearchResults);
        btnQuickPasteUrl = (Button) findViewById(R.id.btnQuickPasteUrl);

        chipTrend = (TextView) findViewById(R.id.chipTrend);
        chipPop = (TextView) findViewById(R.id.chipPop);
        chipRock = (TextView) findViewById(R.id.chipRock);
        chipLofi = (TextView) findViewById(R.id.chipLofi);
        chipPiano = (TextView) findViewById(R.id.chipPiano);
        chipElectro = (TextView) findViewById(R.id.chipElectro);
        chipAcoustic = (TextView) findViewById(R.id.chipAcoustic);
        chipClassical = (TextView) findViewById(R.id.chipClassical);

        layoutPreviewBar = (LinearLayout) findViewById(R.id.layoutPreviewBar);
        pbPreviewProgress = (ProgressBar) findViewById(R.id.pbPreviewProgress);
        ivPreviewCover = (ImageView) findViewById(R.id.ivPreviewCover);
        tvPreviewTitle = (TextView) findViewById(R.id.tvPreviewTitle);
        tvPreviewArtist = (TextView) findViewById(R.id.tvPreviewArtist);
        btnPreviewPlayPause = (Button) findViewById(R.id.btnPreviewPlayPause);
        btnPreviewDownloadNow = (Button) findViewById(R.id.btnPreviewDownloadNow);
        btnPreviewClose = (Button) findViewById(R.id.btnPreviewClose);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (btnClearCompleted != null) {
            btnClearCompleted.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    workDownloadManager.clearCompletedDownloads();
                    downloadManager.clearCompletedDownloads();
                    Toast.makeText(SearchActivity.this, "Historique nettoyé", Toast.LENGTH_SHORT).show();
                    refreshDownloadsBadge();
                }
            });
        }
    }

    private void setupTabs() {
        tabSearchOnline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_SEARCH);
            }
        });

        tabMyDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_DOWNLOADS);
            }
        });
    }

    private void switchTab(int tab) {
        currentTab = tab;
        if (tab == TAB_SEARCH) {
            viewSearchOnline.setVisibility(View.VISIBLE);
            viewMyDownloads.setVisibility(View.GONE);
            tabSearchOnline.setTextColor(Color.parseColor("#FFCA28"));
            tabMyDownloads.setTextColor(Color.parseColor("#888888"));
        } else {
            viewSearchOnline.setVisibility(View.GONE);
            viewMyDownloads.setVisibility(View.VISIBLE);
            tabSearchOnline.setTextColor(Color.parseColor("#888888"));
            tabMyDownloads.setTextColor(Color.parseColor("#FFCA28"));
            refreshDownloadsBadge();
        }
    }

    private void setupSearchSection() {
        etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void afterTextChanged(Editable s) {
                btnClearSearch.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        btnClearSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etSearchQuery.setText("");
                loadTrendingSongs();
            }
        });

        btnSubmitSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performSearch(etSearchQuery.getText().toString());
            }
        });

        etSearchQuery.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    performSearch(etSearchQuery.getText().toString());
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });

        btnQuickPasteUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDirectUrlDownloadDialog();
            }
        });
    }

    private void setupChips() {
        View.OnClickListener chipClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetChipStyles();
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setBackgroundColor(Color.parseColor("#FFCA28"));
                    tv.setTextColor(Color.BLACK);

                    if (v == chipTrend) {
                        etSearchQuery.setText("");
                        loadTrendingSongs();
                    } else if (v == chipPop) {
                        etSearchQuery.setText("Pop");
                        performSearch("Pop");
                    } else if (v == chipRock) {
                        etSearchQuery.setText("Rock");
                        performSearch("Rock");
                    } else if (v == chipLofi) {
                        etSearchQuery.setText("Lo-Fi");
                        performSearch("Lo-Fi");
                    } else if (v == chipPiano) {
                        etSearchQuery.setText("Piano");
                        performSearch("Piano");
                    } else if (v == chipElectro) {
                        etSearchQuery.setText("Electro");
                        performSearch("Electro");
                    } else if (v == chipAcoustic) {
                        etSearchQuery.setText("Acoustic");
                        performSearch("Acoustic");
                    } else if (v == chipClassical) {
                        etSearchQuery.setText("Classical");
                        performSearch("Classical");
                    }
                }
            }
        };

        chipTrend.setOnClickListener(chipClick);
        chipPop.setOnClickListener(chipClick);
        chipRock.setOnClickListener(chipClick);
        chipLofi.setOnClickListener(chipClick);
        chipPiano.setOnClickListener(chipClick);
        chipElectro.setOnClickListener(chipClick);
        chipAcoustic.setOnClickListener(chipClick);
        chipClassical.setOnClickListener(chipClick);
    }

    private void resetChipStyles() {
        TextView[] chips = new TextView[]{chipTrend, chipPop, chipRock, chipLofi, chipPiano, chipElectro, chipAcoustic, chipClassical};
        for (TextView chip : chips) {
            if (chip != null) {
                chip.setBackgroundColor(Color.parseColor("#2E2E2E"));
                chip.setTextColor(Color.parseColor("#CCCCCC"));
            }
        }
    }

    private void performSearch(String query) {
        final String q = query.trim();
        if (q.isEmpty()) {
            loadTrendingSongs();
            return;
        }

        // If direct MP3 link, prompt direct download!
        if (q.startsWith("http://") || q.startsWith("https://")) {
            startDirectDownload(q, AudioDownloadManager.extractTitleFromUrl(q));
            return;
        }

        pbSearchProgress.setVisibility(View.VISIBLE);
        tvSearchResultsHeader.setText("Recherche de \"" + q + "\"...");
        tvSearchCount.setText("Recherche en cours...");

        MusicSearchEngine.search(q, new MusicSearchEngine.SearchCallback() {
            @Override
            public void onSuccess(List<SearchResultItem> results) {
                pbSearchProgress.setVisibility(View.GONE);
                searchResults.clear();
                searchResults.addAll(results);
                searchResultAdapter.notifyDataSetChanged();

                tvSearchResultsHeader.setText("Résultats pour \"" + q + "\"");
                tvSearchCount.setText(results.size() + " morceaux trouvés");

                if (results.isEmpty()) {
                    Toast.makeText(SearchActivity.this, "Aucun morceau trouvé pour cette recherche", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                pbSearchProgress.setVisibility(View.GONE);
                Toast.makeText(SearchActivity.this, "Erreur réseau : " + errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadTrendingSongs() {
        searchResults.clear();
        searchResults.addAll(MusicSearchEngine.getTrendingTracks());
        if (searchResultAdapter != null) {
            searchResultAdapter.notifyDataSetChanged();
        }
        tvSearchResultsHeader.setText("🔥 Titres tendances & populaires");
        tvSearchCount.setText(searchResults.size() + " morceaux");
    }

    private void setupPreviewBar() {
        btnPreviewPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (previewPlayer != null) {
                    if (previewPlayer.isPlaying()) {
                        previewPlayer.pause();
                        btnPreviewPlayPause.setText("▶");
                    } else {
                        previewPlayer.start();
                        btnPreviewPlayPause.setText("⏸");
                        handler.post(previewProgressRunnable);
                    }
                }
            }
        });

        btnPreviewClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPreview();
            }
        });

        btnPreviewDownloadNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPreviewItem != null) {
                    onDownload(currentPreviewItem);
                }
            }
        });
    }

    // ================= SearchResultAdapter.OnSearchResultActionListener =================
    @Override
    public void onPreview(SearchResultItem item) {
        if (currentPreviewItem != null && currentPreviewItem.getId().equals(item.getId()) && previewPlayer != null) {
            if (previewPlayer.isPlaying()) {
                previewPlayer.pause();
                btnPreviewPlayPause.setText("▶");
                searchResultAdapter.setCurrentlyPreviewingId(null);
            } else {
                previewPlayer.start();
                btnPreviewPlayPause.setText("⏸");
                searchResultAdapter.setCurrentlyPreviewingId(item.getId());
                handler.post(previewProgressRunnable);
            }
            return;
        }

        startAudioPreview(item);
    }

    private void startAudioPreview(final SearchResultItem item) {
        stopPreview();
        currentPreviewItem = item;

        layoutPreviewBar.setVisibility(View.VISIBLE);
        tvPreviewTitle.setText(item.getTitle());
        tvPreviewArtist.setText(item.getArtist() + " • " + item.getSource());
        btnPreviewPlayPause.setText("⏳");
        pbPreviewProgress.setProgress(0);

        ImageLoaderHelper.load(item.getCoverUrl(), ivPreviewCover, android.R.drawable.ic_media_play);
        searchResultAdapter.setCurrentlyPreviewingId(item.getId());

        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            );
            previewPlayer.setDataSource(item.getStreamUrl());
            previewPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                    btnPreviewPlayPause.setText("⏸");
                    handler.post(previewProgressRunnable);
                }
            });
            previewPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    btnPreviewPlayPause.setText("▶");
                    pbPreviewProgress.setProgress(100);
                    searchResultAdapter.setCurrentlyPreviewingId(null);
                }
            });
            previewPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Toast.makeText(SearchActivity.this, "Impossible de lire l'aperçu audio", Toast.LENGTH_SHORT).show();
                    stopPreview();
                    return true;
                }
            });
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Erreur preview: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPreview();
        }
    }

    private void stopPreview() {
        handler.removeCallbacks(previewProgressRunnable);
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.stop();
                }
                previewPlayer.release();
            } catch (Exception ignored) {}
            previewPlayer = null;
        }
        currentPreviewItem = null;
        if (layoutPreviewBar != null) {
            layoutPreviewBar.setVisibility(View.GONE);
        }
        if (searchResultAdapter != null) {
            searchResultAdapter.setCurrentlyPreviewingId(null);
        }
    }

    @Override
    public void onDownload(final SearchResultItem item) {
        final String[] qualities = new String[]{
            "🎵 MP3 Haute Qualité (320 kbps) • " + item.getFormattedSize(),
            "🎵 MP3 Standard (192 kbps) • ~3.5 Mo",
            "🎵 MP3 Léger (128 kbps) • ~2.1 Mo"
        };

        new AlertDialog.Builder(this)
            .setTitle("⚡ Snaptube - Télécharger avec WorkManager")
            .setItems(qualities, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startDirectDownload(item.getDownloadUrl(), item.getTitle() + " - " + item.getArtist());
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void startDirectDownload(String url, String title) {
        try {
            // Enqueue using WorkManager for persistent background downloading with real-time progress
            workDownloadManager.enqueueDownload(url, title, "MP3 Audio", null);

            // Also mirror to AudioDownloadManager for multi-system compatibility
            try {
                downloadManager.enqueueDownload(url, title);
            } catch (Exception ignored) {}

            Toast.makeText(this, "⚡ Téléchargement MP3 lancé (WorkManager) : " + title, Toast.LENGTH_SHORT).show();
            refreshDownloadsBadge();

            // Switch to Downloads tab to see live WorkManager progress bars
            switchTab(TAB_DOWNLOADS);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDirectUrlDownloadDialog() {
        final EditText input = new EditText(this);
        input.setHint("Coller l'URL MP3 directe (https://...)");
        input.setTextColor(Color.WHITE);
        input.setPadding(30, 20, 30, 20);

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (text != null && (text.toString().startsWith("http://") || text.toString().startsWith("https://"))) {
                input.setText(text.toString());
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("Télécharger depuis une URL (WorkManager)")
            .setMessage("Entrez ou collez le lien direct du fichier audio MP3 :")
            .setView(input)
            .setPositiveButton("Télécharger", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        startDirectDownload(url, AudioDownloadManager.extractTitleFromUrl(url));
                    }
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void refreshDownloadsBadge() {
        int total = downloadManager.getAllDownloads().size();
        if (tabMyDownloads != null) {
            tabMyDownloads.setText("📥 Téléchargements (" + total + ")");
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }
}
