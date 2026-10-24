package com.oyunkayit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_CAPTURE = 2;
    private static final int RED = 0xFFE53935;
    private static final int GRAY = 0xFF2A2A2A;

    private SharedPreferences sp;
    private TextView status;
    private Button btn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF121212);
        getWindow().setNavigationBarColor(0xFF121212);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(dp(20), dp(36), dp(20), dp(24));

        TextView title = tv("Vidlox", 30, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView badge = tv("made by WERFESR", 12, 0xFFFF6B6B);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setLetterSpacing(0.08f);
        badge.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable badgeBg = rounded(0x33E53935, 20);
        badgeBg.setStroke(dp(1), RED);
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        root.addView(badge, blp);

        status = tv("Hazır", 14, 0xFF9E9E9E);
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);

        addGroup(root, "Çözünürlük", "res", new String[]{"720p", "1080p"}, new int[]{720, 1080}, 720);
        addGroup(root, "FPS", "fps", new String[]{"30", "60"}, new int[]{30, 60}, 30);
        addGroup(root, "Yön (oyun yataysa Yatay)", "orient", new String[]{"Yatay", "Dikey"}, new int[]{0, 1}, 0);
        addGroup(root, "Ses", "audio", new String[]{"Oyun+Mik", "Oyun", "Mik", "Sessiz"}, new int[]{0, 1, 2, 3}, 0);
        addGroup(root, "Mikrofon (kulaklık takılıysa Otomatik kulaklığı kullanır)", "micsrc", new String[]{"Otomatik", "Telefon"}, new int[]{0, 1}, 0);
        addMicSlider(root);
        addGroup(root, "AEC (Akustik Yankı Engelleme) - Oyun+Mik modunda", "aec3", new String[]{"Kapalı", "Açık"}, new int[]{0, 1}, 1);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = tv("Kayıt başlayınca oyuna geç. Durdurmak için bildirimdeki \"Durdur\"a bas.\nHoparlörden müzik çalıyorsa kulaklık takmak yankıyı tamamen bitirir.\nVideolar Galeri > Movies/Vidlox klasörüne kaydedilir.", 12, 0xFF757575);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint);

        btn = new Button(this);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(16);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(rounded(RED));
        btn.setOnClickListener(v -> onButton());
        root.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFF121212);
        sv.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    private void onButton() {
        if (RecorderService.running) {
            Intent stop = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
            startService(stop);
            handler.postDelayed(this::updateUi, 1500);
        } else {
            begin();
        }
    }

    private void begin() {
        List<String> need = new ArrayList<>();
        if (sp.getInt("audio", 0) != 3
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            askCapture();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            askCapture();
        }
    }

    private void askCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra("code", resultCode)
                    .putExtra("data", data);
            startForegroundService(i);
            handler.postDelayed(() -> moveTaskToBack(true), 700);
            handler.postDelayed(this::updateUi, 1200);
        }
    }

    private void updateUi() {
        boolean r = RecorderService.running;
        btn.setText(r ? "KAYDI DURDUR" : "KAYDI BAŞLAT");
        status.setText(r ? "● Kaydediliyor" : "Hazır");
        status.setTextColor(r ? RED : 0xFF9E9E9E);
    }

    private void addGroup(LinearLayout root, String label, final String key,
                          String[] names, final int[] vals, final int def) {
        TextView l = tv(label, 13, 0xFF9E9E9E);
        l.setPadding(0, dp(20), 0, dp(8));
        root.addView(l);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[names.length];

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                int cur = sp.getInt(key, def);
                for (int i = 0; i < chips.length; i++) {
                    chips[i].setBackground(rounded(vals[i] == cur ? RED : GRAY));
                }
            }
        };

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView c = tv(names[i], 14, 0xFFFFFFFF);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(12), 0, dp(12));
            c.setOnClickListener(v -> {
                sp.edit().putInt(key, vals[idx]).apply();
                refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            row.addView(c, lp);
            chips[i] = c;
        }
        root.addView(row);
        refresh.run();
    }

    private void addMicSlider(LinearLayout root) {
        final TextView l = tv("", 13, 0xFF9E9E9E);
        l.setPadding(0, dp(20), 0, dp(4));
        root.addView(l);

        SeekBar sb = new SeekBar(this);
        sb.setMax(300);
        int cur = sp.getInt("micgain", 150);
        sb.setProgress(cur);
        l.setText("Mik sesi: " + cur + "%");
        ColorStateList red = ColorStateList.valueOf(RED);
        sb.setProgressTintList(red);
        sb.setThumbTintList(red);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                l.setText("Mik sesi: " + progress + "%");
                sp.edit().putInt("micgain", progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(sb);
    }

    private TextView tv(String text, int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color) {
        return rounded(color, 12);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
