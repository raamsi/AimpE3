package com.example;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class LyricsActivity extends Activity {

    private static final int BG_COLOR = 0xFF0B0B10;
    private static final int ACTIVE_COLOR = 0xFF1DB954;
    private static final int INACTIVE_COLOR = 0xFFDDDDDD;
    private static final long AUTO_SCROLL_PAUSE_MS = 3000L;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static WeakReference<LyricsActivity> activeActivity =
        new WeakReference<LyricsActivity>(null);

    private ListView listViewLyrics;
    private Button btnCloseLyrics;
    private Button btnEditLyrics;
    private TextView emptyText;
    private SpectrumView spectrumView;
    private AmbientGlowView glowView;

    private final ArrayList<String> lyricsTextList = new ArrayList<String>();
    private final ArrayList<Integer> lyricsTimeList = new ArrayList<Integer>();

    private int playbackPosition = 0;
    private int audioSessionId = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LyricsAdapter adapter;
    private int activeIndex = -1;
    private long lastUserTouch = 0L;
    private boolean needsInstantScroll = true;
    private boolean resumed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG_COLOR);
            getWindow().setNavigationBarColor(BG_COLOR);
        }

        if (getIntent() != null) {
            ArrayList<String> texts = getIntent().getStringArrayListExtra("LYRICS_TEXT");
            ArrayList<Integer> times = getIntent().getIntegerArrayListExtra("LYRICS_TIME");
            playbackPosition = getIntent().getIntExtra("CURRENT_POSITION", 0);
            audioSessionId = getIntent().getIntExtra("AUDIO_SESSION_ID", 0);

            int count = Math.min(texts != null ? texts.size() : 0,
                                 times != null ? times.size() : 0);
            for (int i = 0; i < count; i++) {
                lyricsTextList.add(texts.get(i));
                lyricsTimeList.add(times.get(i));
            }
        }
        activeActivity = new WeakReference<LyricsActivity>(this);

        setContentView(buildUi());

        btnCloseLyrics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnEditLyrics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LyricsActivity.this, LyricsAdjusterActivity.class);
                intent.putStringArrayListExtra("EDIT_LYRICS_TEXT",
                                               new ArrayList<String>(lyricsTextList));
                intent.putIntegerArrayListExtra("EDIT_LYRICS_TIME",
                                                new ArrayList<Integer>(lyricsTimeList));
                String activeSongPath = MainActivity.getCurrentSongPathStatic();
                intent.putExtra("AUDIO_PATH", activeSongPath);
                intent.putExtra("SONG_TITLE", "Synchronisation des Paroles");
                startActivityForResult(intent, 100);
            }
        });

        refreshEmptyState();
        applyPlaybackPosition(playbackPosition);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_COLOR);

        glowView = new AmbientGlowView(this);
        root.addView(glowView, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));

        spectrumView = new SpectrumView(this);
        glowView.setSource(spectrumView);
        LinearLayout.LayoutParams spectrumLp = new LinearLayout.LayoutParams(MATCH, dp(120));
        spectrumLp.topMargin = dp(8);
        column.addView(spectrumView, spectrumLp);

        FrameLayout listHolder = new FrameLayout(this);
        column.addView(listHolder, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        listViewLyrics = new ListView(this);
        listViewLyrics.setDivider(null);
        listViewLyrics.setDividerHeight(0);
        listViewLyrics.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listViewLyrics.setCacheColorHint(Color.TRANSPARENT);
        listViewLyrics.setVerticalScrollBarEnabled(false);
        listViewLyrics.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listViewLyrics.setVerticalFadingEdgeEnabled(true);
        listViewLyrics.setFadingEdgeLength(dp(56));

        int spacerH = (int) (getResources().getDisplayMetrics().heightPixels * 0.25f);
        listViewLyrics.addHeaderView(makeSpacer(spacerH), null, false);
        listViewLyrics.addFooterView(makeSpacer(spacerH), null, false);

        adapter = new LyricsAdapter();
        listViewLyrics.setAdapter(adapter);
        listViewLyrics.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                lastUserTouch = SystemClock.uptimeMillis();
                return false;
            }
        });
        listHolder.addView(listViewLyrics, new FrameLayout.LayoutParams(MATCH, MATCH));

        emptyText = new TextView(this);
        emptyText.setText("Aucune parole disponible");
        emptyText.setTextColor(0xFF888888);
        emptyText.setTextSize(16f);
        emptyText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER);
        listHolder.addView(emptyText, emptyLp);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(MATCH, dp(48));
        barLp.setMargins(dp(24), dp(8), dp(24), dp(16));

        GradientDrawable pillEdit = new GradientDrawable();
        pillEdit.setColor(0x22FFFFFF);
        pillEdit.setCornerRadius(dp(24));
        pillEdit.setStroke(dp(1), 0x33FFFFFF);

        btnEditLyrics = new Button(this);
        btnEditLyrics.setText("Éditer");
        btnEditLyrics.setTextColor(0xFFEEEEEE);
        btnEditLyrics.setAllCaps(false);
        btnEditLyrics.setBackground(pillEdit);

        btnCloseLyrics = new Button(this);
        btnCloseLyrics.setText("Fermer");
        btnCloseLyrics.setTextColor(0xFFEEEEEE);
        btnCloseLyrics.setAllCaps(false);
        GradientDrawable pillClose = new GradientDrawable();
        pillClose.setColor(0x22FFFFFF);
        pillClose.setCornerRadius(dp(24));
        pillClose.setStroke(dp(1), 0x33FFFFFF);
        btnCloseLyrics.setBackground(pillClose);

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        editLp.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        closeLp.setMargins(dp(4), 0, dp(4), 0);

        bottomBar.addView(btnEditLyrics, editLp);
        bottomBar.addView(btnCloseLyrics, closeLp);
        column.addView(bottomBar, barLp);

        return root;
    }

    private View makeSpacer(int heightPx) {
        View v = new View(this);
        v.setLayoutParams(new AbsListView.LayoutParams(MATCH, heightPx));
        return v;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            ArrayList<String> newTexts = data.getStringArrayListExtra("NEW_LYRICS_TEXT");
            ArrayList<Integer> newTimes = data.getIntegerArrayListExtra("NEW_LYRICS_TIME");

            if (newTexts != null && newTimes != null) {
                int count = Math.min(newTexts.size(), newTimes.size());
                lyricsTextList.clear();
                lyricsTimeList.clear();
                for (int i = 0; i < count; i++) {
                    lyricsTextList.add(newTexts.get(i));
                    lyricsTimeList.add(newTimes.get(i));
                }
                sortLyricsByTime();

                activeIndex = -1;
                needsInstantScroll = true;
                if (adapter != null) adapter.notifyDataSetChanged();
                refreshEmptyState();
                applyPlaybackPosition(MainActivity.getCurrentPositionStatic());

                MainActivity.applyEditedLyrics(
                    new ArrayList<String>(lyricsTextList),
                    new ArrayList<Integer>(lyricsTimeList));
            }
        }
    }

    private void sortLyricsByTime() {
        final int n = Math.min(lyricsTextList.size(), lyricsTimeList.size());
        final ArrayList<String> texts = new ArrayList<String>(lyricsTextList);
        final ArrayList<Integer> times = new ArrayList<Integer>(lyricsTimeList);

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return times.get(a).compareTo(times.get(b));
            }
        });

        lyricsTextList.clear();
        lyricsTimeList.clear();
        for (int i = 0; i < n; i++) {
            lyricsTextList.add(texts.get(order[i]));
            lyricsTimeList.add(times.get(order[i]));
        }
    }

    private class LyricsAdapter extends BaseAdapter {
        @Override public int getCount() { return lyricsTextList.size(); }
        @Override public Object getItem(int position) { return lyricsTextList.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(LyricsActivity.this);
                tv.setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(24), dp(12), dp(24), dp(12));
                tv.setLineSpacing(0f, 1.1f);
            }

            tv.setText(lyricsTextList.get(position));

            if (position == activeIndex) {
                tv.setTextColor(ACTIVE_COLOR);
                tv.setTextSize(24f);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setAlpha(1f);
                tv.setShadowLayer(dp(10), 0f, 0f, 0x991DB954);
            } else {
                int dist = activeIndex < 0 ? 3 : Math.abs(position - activeIndex);
                tv.setTextColor(INACTIVE_COLOR);
                tv.setTextSize(17f);
                tv.setTypeface(null, Typeface.NORMAL);
                tv.setAlpha(dist == 1 ? 0.80f : (dist == 2 ? 0.60f : 0.42f));
                tv.setShadowLayer(dp(3), 0f, dp(1), 0xCC000000);
            }
            return tv;
        }
    }

    static void updatePlaybackPosition(final int positionMs) {
        final LyricsActivity activity = activeActivity.get();
        if (activity == null) return;
        activity.handler.post(new Runnable() {
            @Override
            public void run() {
                activity.applyPlaybackPosition(positionMs);
            }
        });
    }

    static void updateAudioSession(final int sessionId) {
        final LyricsActivity activity = activeActivity.get();
        if (activity == null) return;
        activity.handler.post(new Runnable() {
            @Override
            public void run() {
                activity.audioSessionId = sessionId;
                if (activity.resumed && sessionId > 0 && activity.spectrumView != null) {
                    activity.spectrumView.linkMediaPlayer(sessionId);
                }
            }
        });
    }

    static void replaceLyrics(final ArrayList<LrcParser.LyricLine> lines, final int positionMs) {
        final LyricsActivity activity = activeActivity.get();
        if (activity == null) return;
        activity.handler.post(new Runnable() {
            @Override
            public void run() {
                activity.lyricsTextList.clear();
                activity.lyricsTimeList.clear();
                if (lines != null) {
                    for (LrcParser.LyricLine line : lines) {
                        if (line != null && line.text != null) {
                            activity.lyricsTextList.add(line.text);
                            activity.lyricsTimeList.add(line.timeMs);
                        }
                    }
                }
                activity.activeIndex = -1;
                activity.needsInstantScroll = true;
                if (activity.adapter != null) activity.adapter.notifyDataSetChanged();
                activity.refreshEmptyState();
                activity.applyPlaybackPosition(positionMs);
            }
        });
    }

    private void refreshEmptyState() {
        if (emptyText != null) {
            emptyText.setVisibility(lyricsTextList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private int findActiveIndex(int positionMs) {
        int lo = 0;
        int hi = lyricsTimeList.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lyricsTimeList.get(mid) <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private void applyPlaybackPosition(int positionMs) {
        playbackPosition = Math.max(0, positionMs);
        int newIndex = findActiveIndex(playbackPosition);

        if (newIndex == activeIndex) return;
        activeIndex = newIndex;
        if (adapter != null) adapter.notifyDataSetChanged();
        scrollToActive();
    }

    private void scrollToActive() {
        if (listViewLyrics == null || activeIndex < 0) return;

        if (!needsInstantScroll
            && SystemClock.uptimeMillis() - lastUserTouch < AUTO_SCROLL_PAUSE_MS) return;

        final int height = listViewLyrics.getHeight();
        if (height == 0) {
            listViewLyrics.post(new Runnable() {
                @Override
                public void run() {
                    scrollToActive();
                }
            });
            return;
        }

        final int listPosition = activeIndex + 1;
        final int offset = height / 2 - dp(28);

        if (needsInstantScroll) {
            listViewLyrics.setSelectionFromTop(listPosition, offset);
            needsInstantScroll = false;
        } else {
            listViewLyrics.smoothScrollToPositionFromTop(listPosition, offset, 300);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        MainActivity.setLyricsScreenVisible(true);
        if (spectrumView != null) spectrumView.setAnimating(true);
        if (glowView != null) glowView.setAnimating(true);
        if (audioSessionId > 0 && spectrumView != null) spectrumView.linkMediaPlayer(audioSessionId);
    }

    @Override
    protected void onPause() {
        resumed = false;
        MainActivity.setLyricsScreenVisible(false);
        if (spectrumView != null) {
            spectrumView.releaseVisualizer();
            spectrumView.setAnimating(false);
        }
        if (glowView != null) glowView.setAnimating(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (spectrumView != null) spectrumView.releaseVisualizer();
        LyricsActivity current = activeActivity.get();
        if (current == this) activeActivity = new WeakReference<LyricsActivity>(null);
        handler.removeCallbacksAndMessages(null);
    }
}
