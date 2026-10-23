package com.example;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public class LyricsEditorActivity extends Activity {

    private ListView lvEditorList;
    private Button btnDone, btnEditorPlay, btnMark, btnRewind5, btnForward5;
    private SeekBar editorSeekBar;
    private TextView tvEditorSongTitle;

    private ArrayList<EditorItem> editorItems = new ArrayList<EditorItem>();
    private EditorAdapter adapter;
    private int activeRowIndex = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isUserSeeking = false;

    private final Runnable updateTask = new Runnable() {
        @Override
        public void run() {
            if (!isUserSeeking) {
                int pos = MainActivity.getCurrentPositionStatic();
                if (editorSeekBar != null) {
                    editorSeekBar.setProgress(pos);
                }
            }
            if (btnEditorPlay != null) {
                btnEditorPlay.setText(MainActivity.isPlayingStatic() ? "⏸" : "▶");
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics_editor);

        lvEditorList = (ListView) findViewById(R.id.lvEditorList);
        btnDone = (Button) findViewById(R.id.btnDone);
        btnEditorPlay = (Button) findViewById(R.id.btnEditorPlay);
        btnMark = (Button) findViewById(R.id.btnMark);
        btnRewind5 = (Button) findViewById(R.id.btnRewind5);
        btnForward5 = (Button) findViewById(R.id.btnForward5);
        editorSeekBar = (SeekBar) findViewById(R.id.editorSeekBar);
        tvEditorSongTitle = (TextView) findViewById(R.id.tvEditorSongTitle);

        if (getIntent() != null) {
            ArrayList<String> texts = getIntent().getStringArrayListExtra("EDIT_LYRICS_TEXT");
            ArrayList<Integer> times = getIntent().getIntegerArrayListExtra("EDIT_LYRICS_TIME");

            if (texts != null) {
                for (int i = 0; i < texts.size(); i++) {
                    int t = (times != null && i < times.size()) ? times.get(i) : 0;
                    editorItems.add(new EditorItem(t, texts.get(i)));
                }
            }
        }

        adapter = new EditorAdapter(this, editorItems);
        if (lvEditorList != null) {
            lvEditorList.setAdapter(adapter);
        }

        int duration = MainActivity.getDurationStatic();
        if (editorSeekBar != null) {
            editorSeekBar.setMax(duration);
        }
        if (tvEditorSongTitle != null) {
            tvEditorSongTitle.setText("Synchronisation interactive");
        }
        if (btnEditorPlay != null) {
            btnEditorPlay.setText(MainActivity.isPlayingStatic() ? "⏸" : "▶");
        }
        handler.post(updateTask);

        if (lvEditorList != null) {
            lvEditorList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    activeRowIndex = position;
                    adapter.setSelectedIndex(activeRowIndex);
                    if (editorItems.get(position).timeMs > 0) {
                        MainActivity.seekToStatic(editorItems.get(position).timeMs);
                    }
                }
            });
        }

        if (btnEditorPlay != null) {
            btnEditorPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MainActivity.togglePlayPauseStatic();
                    btnEditorPlay.setText(MainActivity.isPlayingStatic() ? "⏸" : "▶");
                }
            });
        }

        if (btnMark != null) {
            btnMark.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (activeRowIndex < editorItems.size()) {
                        int currentPos = MainActivity.getCurrentPositionStatic();
                        editorItems.get(activeRowIndex).timeMs = currentPos;
                        adapter.notifyDataSetChanged();

                        if (activeRowIndex < editorItems.size() - 1) {
                            activeRowIndex++;
                            adapter.setSelectedIndex(activeRowIndex);
                            if (lvEditorList != null) {
                                lvEditorList.smoothScrollToPosition(activeRowIndex);
                            }
                        }
                    }
                }
            });
        }

        if (btnRewind5 != null) {
            btnRewind5.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int target = Math.max(0, MainActivity.getCurrentPositionStatic() - 5000);
                    MainActivity.seekToStatic(target);
                }
            });
        }

        if (btnForward5 != null) {
            btnForward5.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int target = Math.min(MainActivity.getDurationStatic(), MainActivity.getCurrentPositionStatic() + 5000);
                    MainActivity.seekToStatic(target);
                }
            });
        }

        if (editorSeekBar != null) {
            editorSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    isUserSeeking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    isUserSeeking = false;
                    MainActivity.seekToStatic(seekBar.getProgress());
                }
            });
        }

        if (btnDone != null) {
            btnDone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent resultIntent = new Intent();
                    ArrayList<String> newTexts = new ArrayList<String>();
                    ArrayList<Integer> newTimes = new ArrayList<Integer>();

                    for (EditorItem item : editorItems) {
                        newTexts.add(item.text);
                        newTimes.add(item.timeMs);
                    }

                    resultIntent.putStringArrayListExtra("NEW_LYRICS_TEXT", newTexts);
                    resultIntent.putIntegerArrayListExtra("NEW_LYRICS_TIME", newTimes);
                    setResult(RESULT_OK, resultIntent);

                    Toast.makeText(LyricsEditorActivity.this, "Paroles synchronisées !", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(updateTask);
    }
}
