package com.winfg.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_HOST_TREE = 100;
    private static final int REQ_EXPORT_CONF = 101;

    private AppSettings settings;
    private final List<GameEntry> allGames = new ArrayList<>();
    private final List<GameEntry> shownGames = new ArrayList<>();
    private GameAdapter adapter;

    private TextView tabGames, tabSettings, tabEngine;
    private View pageGames, pageSettings, pageEngine;
    private EditText search;
    private TextView gamesSummary, libraryNotice, settingsSummary;
    private TextView engineStatus, engineVersion, engineProfile, engineSha, engineHostPath;

    private boolean binding;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable profileWriter =
            () -> io.execute(() -> EngineManager.writeProfile(getApplicationContext(), settings));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = AppSettings.load(this);
        if (EngineManager.layerInstalled(this)) {
            // Properties do not survive a reboot; re-publish the saved profile.
            EngineManager.pushProps(this, settings);
        }
        setContentView(R.layout.activity_main);

        tabGames = f(R.id.tab_games);
        tabSettings = f(R.id.tab_settings);
        tabEngine = f(R.id.tab_engine);
        pageGames = f(R.id.page_games);
        pageSettings = f(R.id.page_settings);
        pageEngine = f(R.id.page_engine);
        libraryNotice = f(R.id.library_notice);
        engineHostPath = f(R.id.engine_hostpath);

        ((TextView) f(R.id.header_version)).setText("v" + AppInfo.VERSION_NAME);

        tabGames.setOnClickListener(v -> showTab(0));
        tabSettings.setOnClickListener(v -> showTab(1));
        tabEngine.setOnClickListener(v -> showTab(2));

        setupGames();
        setupSettings();
        setupEngine();
        showTab(0);

        io.execute(() -> {
            try {
                EngineManager.ensureExtracted(this);
            } catch (Exception ignored) {}
            ui.post(this::refreshEngineInfo);
        });
        loadGames();
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T f(int id) {
        return (T) findViewById(id);
    }

    private void showTab(int index) {
        pageGames.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pageEngine.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabGames.setSelected(index == 0);
        tabSettings.setSelected(index == 1);
        tabEngine.setSelected(index == 2);
    }

    // ── Game library ─────────────────────────────────────────────────────────

    private void setupGames() {
        search = f(R.id.game_search);
        gamesSummary = f(R.id.games_summary);
        ListView list = f(R.id.game_list);
        adapter = new GameAdapter(this, settings.multiplier);
        list.setAdapter(adapter);
        list.setEmptyView(f(R.id.game_empty));
        list.setOnItemClickListener((parent, view, position, id) -> launchGame(adapter.getItem(position)));
        f(R.id.game_refresh).setOnClickListener(v -> loadGames());

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
    }

    private void loadGames() {
        gamesSummary.setText("SCANNING INSTALLED APPS…");
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
            Map<String, GameEntry> byPackage = new LinkedHashMap<>();
            for (ResolveInfo ri : resolved) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName == null) continue;
                if (ai.packageName.equals(getPackageName())) continue;
                if (byPackage.containsKey(ai.packageName)) continue;
                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Exception e) {
                    label = ai.packageName;
                }
                boolean game = false;
                try {
                    ApplicationInfo app = pm.getApplicationInfo(ai.packageName, 0);
                    game = app.category == ApplicationInfo.CATEGORY_GAME;
                } catch (Exception ignored) {}
                byPackage.put(ai.packageName, new GameEntry(ai.packageName, ai.name, label, game));
            }
            List<GameEntry> list = new ArrayList<>(byPackage.values());
            list.sort((a, b) -> a.game != b.game
                    ? (a.game ? -1 : 1)
                    : a.label.compareToIgnoreCase(b.label));
            ui.post(() -> {
                allGames.clear();
                allGames.addAll(list);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        shownGames.clear();
        for (GameEntry g : allGames) {
            if (q.isEmpty()
                    || g.label.toLowerCase(Locale.US).contains(q)
                    || g.packageName.toLowerCase(Locale.US).contains(q)) {
                shownGames.add(g);
            }
        }
        adapter.setData(shownGames);
        adapter.notifyDataSetChanged();
        if (gamesSummary != null) {
            gamesSummary.setText(shownGames.size() + " APPS  ·  TAP TO LAUNCH WITH YOUR PROFILE");
        }
    }

    private void launchGame(GameEntry g) {
        try {
            settings.save(this);
            EngineManager.writeProfile(this, settings);
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(g.packageName, g.activityName));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra("winfg.conf", EngineManager.confFile(this).getAbsolutePath());
            i.putExtra("winfg.multiplier", settings.multiplier);
            i.putExtra("winfg.model", settings.model);
            startActivity(i);
            toast("Win-FG profile applied · " + settings.summary());
        } catch (Exception e) {
            toast("Could not launch " + g.label);
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private void setupSettings() {
        settingsSummary = f(R.id.settings_summary);

        Switch enabled = f(R.id.sw_enabled);
        enabled.setOnCheckedChangeListener((b, v) -> {
            if (binding) return;
            settings.enabled = v;
            onSettingChanged();
        });

        Switch pacing = f(R.id.sw_pacing);
        pacing.setOnCheckedChangeListener((b, v) -> {
            if (binding) return;
            settings.pacing = v;
            onSettingChanged();
        });

        Switch debug = f(R.id.sw_debug);
        debug.setOnCheckedChangeListener((b, v) -> {
            if (binding) return;
            settings.debug = v;
            onSettingChanged();
        });

        f(R.id.mode_flow).setOnClickListener(v -> {
            if (binding || settings.model == 3) return;
            settings.model = 3;
            updateLabels();
            onSettingChanged();
        });
        f(R.id.mode_bidir).setOnClickListener(v -> {
            if (binding || settings.model == 4) return;
            settings.model = 4;
            updateLabels();
            onSettingChanged();
        });

        f(R.id.mult_2).setOnClickListener(v -> setMultiplier(2));
        f(R.id.mult_3).setOnClickListener(v -> setMultiplier(3));
        f(R.id.mult_4).setOnClickListener(v -> setMultiplier(4));

        Spinner preset = f(R.id.spinner_preset);
        preset.setAdapter(spinnerAdapter(new String[]{"Quality", "Balanced", "Performance"}));
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == settings.perfPreset) return;
                settings.perfPreset = position;
                onSettingChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Spinner gm = f(R.id.spinner_gm);
        gm.setAdapter(spinnerAdapter(new String[]{"Auto", "On", "Off"}));
        gm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == settings.gmMode) return;
                settings.gmMode = position;
                onSettingChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Spinner reg = f(R.id.spinner_reg);
        reg.setAdapter(spinnerAdapter(new String[]{"Auto", "On", "Off"}));
        reg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == settings.frMode) return;
                settings.frMode = position;
                onSettingChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ((SeekBar) f(R.id.seek_flowscale)).setMax(79);
        ((SeekBar) f(R.id.seek_flowscale)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.flowScale = 0.05f + progress * 0.05f;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_iters)).setMax(16);
        ((SeekBar) f(R.id.seek_iters)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.frIters = progress;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_extra)).setMax(8);
        ((SeekBar) f(R.id.seek_extra)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.extraImages = progress;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_lambda)).setMax(80);
        ((SeekBar) f(R.id.seek_lambda)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.frLambda = progress / 10f;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_dt)).setMax(99);
        ((SeekBar) f(R.id.seek_dt)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.frDt = 0.01f + progress * 0.01f;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_edge)).setMax(64);
        ((SeekBar) f(R.id.seek_edge)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.frEdge = progress * 0.5f;
                updateLabels();
                onSettingChanged();
            }
        });

        ((SeekBar) f(R.id.seek_eps)).setMax(99);
        ((SeekBar) f(R.id.seek_eps)).setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (binding) return;
                settings.frEps = 0.005f + progress * 0.005f;
                updateLabels();
                onSettingChanged();
            }
        });

        f(R.id.btn_reset).setOnClickListener(v -> {
            settings = new AppSettings();
            settings.save(this);
            bindSettings();
            onSettingChanged();
            toast("Settings reset to win-fg defaults");
        });

        bindSettings();
    }

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, R.layout.spinner_item, items);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return a;
    }

    private void setMultiplier(int value) {
        if (binding || settings.multiplier == value) return;
        settings.multiplier = value;
        updateLabels();
        onSettingChanged();
    }

    private void bindSettings() {
        binding = true;
        ((Switch) f(R.id.sw_enabled)).setChecked(settings.enabled);
        ((Switch) f(R.id.sw_pacing)).setChecked(settings.pacing);
        ((Switch) f(R.id.sw_debug)).setChecked(settings.debug);

        ((Spinner) f(R.id.spinner_preset)).setSelection(settings.perfPreset, false);
        ((Spinner) f(R.id.spinner_gm)).setSelection(settings.gmMode, false);
        ((Spinner) f(R.id.spinner_reg)).setSelection(settings.frMode, false);

        ((SeekBar) ((SeekBar) f(R.id.seek_flowscale))).setProgress(
                Math.round((settings.flowScale - 0.05f) / 0.05f));
        ((SeekBar) ((SeekBar) f(R.id.seek_iters))).setProgress(settings.frIters);
        ((SeekBar) ((SeekBar) f(R.id.seek_extra))).setProgress(settings.extraImages);
        ((SeekBar) ((SeekBar) f(R.id.seek_lambda))).setProgress(Math.round(settings.frLambda * 10f));
        ((SeekBar) ((SeekBar) f(R.id.seek_dt))).setProgress(
                Math.round((settings.frDt - 0.01f) / 0.01f));
        ((SeekBar) ((SeekBar) f(R.id.seek_edge))).setProgress(Math.round(settings.frEdge * 2f));
        ((SeekBar) ((SeekBar) f(R.id.seek_eps))).setProgress(
                Math.round((settings.frEps - 0.005f) / 0.005f));
        binding = false;
        updateLabels();
    }

    private void updateLabels() {
        settingsSummary.setText(settings.summary());
        f(R.id.mode_flow).setSelected(settings.model == 3);
        f(R.id.mode_bidir).setSelected(settings.model == 4);
        f(R.id.mult_2).setSelected(settings.multiplier == 2);
        f(R.id.mult_3).setSelected(settings.multiplier == 3);
        f(R.id.mult_4).setSelected(settings.multiplier == 4);

        ((TextView) f(R.id.val_flowscale)).setText(String.format(Locale.US, "%.2f", settings.flowScale));
        ((TextView) f(R.id.val_iters)).setText(Integer.toString(settings.frIters));
        ((TextView) f(R.id.val_extra)).setText(Integer.toString(settings.extraImages));
        ((TextView) f(R.id.val_lambda)).setText(String.format(Locale.US, "%.1f", settings.frLambda));
        ((TextView) f(R.id.val_dt)).setText(String.format(Locale.US, "%.2f", settings.frDt));
        ((TextView) f(R.id.val_edge)).setText(String.format(Locale.US, "%.1f", settings.frEdge));
        ((TextView) f(R.id.val_eps)).setText(String.format(Locale.US, "%.3f", settings.frEps));

        if (adapter != null) {
            adapter.setMultiplier(settings.multiplier);
            adapter.notifyDataSetChanged();
        }
        refreshNotice();
    }

    private void onSettingChanged() {
        settings.save(this);
        updateLabels();
        ui.removeCallbacks(profileWriter);
        ui.postDelayed(profileWriter, 600);
    }

    // ── Engine / updates ─────────────────────────────────────────────────────

    private void setupEngine() {
        engineStatus = f(R.id.engine_status);
        engineVersion = f(R.id.engine_version);
        engineProfile = f(R.id.engine_profile);
        engineSha = f(R.id.engine_sha);

        engineProfile.setText(EngineManager.confFile(this).getAbsolutePath());
        engineSha.setText("SHA-256: " + EngineManager.signingSha256(this));
        engineVersion.setText("Win-FG " + AppInfo.VERSION_NAME);

        f(R.id.btn_install).setOnClickListener(v -> confirmInstall());
        f(R.id.btn_remove).setOnClickListener(v -> confirmRemove());
        f(R.id.btn_check).setOnClickListener(v -> checkStatus());
        f(R.id.btn_hostfolder).setOnClickListener(v -> pickHostFolder());
        f(R.id.btn_export_conf).setOnClickListener(v -> exportConf());
        f(R.id.btn_updates).setOnClickListener(v -> openUrl(AppInfo.RELEASES_URL));

        refreshEngineInfo();
        refreshHostLabel();
        refreshNotice();
    }

    private void refreshEngineInfo() {
        if (engineVersion == null) return;
        String version = EngineManager.engineVersion(this).replace('\n', ' ').trim();
        if (!version.isEmpty()) engineVersion.setText(version);
        engineStatus.setText(EngineManager.layerInstalled(this)
                ? "Installed (Magisk module) — reboot after install/remove"
                : "Not installed — profile + export mode");
        engineProfile.setText(EngineManager.confFile(this).getAbsolutePath());
    }

    private void refreshHostLabel() {
        String tree = getSharedPreferences(EngineManager.PREFS, MODE_PRIVATE)
                .getString("host_tree", null);
        if (tree == null) {
            engineHostPath.setText("No host folder selected");
        } else {
            String label = Uri.parse(tree).getLastPathSegment();
            engineHostPath.setText("Host folder: " + (label == null ? tree : label));
        }
    }

    private void confirmInstall() {
        new AlertDialog.Builder(this)
                .setTitle("Install system layer?")
                .setMessage("Creates a Magisk module that loads win-fg as a Vulkan implicit layer. "
                        + "A reboot is required. Once active, launched games read your profile "
                        + "from winfg.* system properties.\n\n"
                        + "This loads the layer into every Vulkan app while frame generation is enabled.")
                .setPositiveButton("INSTALL", (d, w) -> doInstall())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void doInstall() {
        Button install = f(R.id.btn_install);
        install.setEnabled(false);
        engineStatus.setText("Installing system layer…");
        io.execute(() -> {
            try {
                String msg = EngineManager.installSystemLayer(this, settings);
                ui.post(() -> {
                    install.setEnabled(true);
                    engineStatus.setText("Installed — reboot to activate");
                    toast(msg);
                    refreshNotice();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    install.setEnabled(true);
                    engineStatus.setText("Install failed");
                    toast(e.getMessage() == null ? "Install failed" : e.getMessage());
                });
            }
        });
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("Remove system layer?")
                .setMessage("Removes the Magisk module and clears the winfg.* profile properties. "
                        + "A reboot is required to unload the layer.")
                .setPositiveButton("REMOVE", (d, w) -> doRemove())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void doRemove() {
        Button remove = f(R.id.btn_remove);
        remove.setEnabled(false);
        engineStatus.setText("Removing system layer…");
        io.execute(() -> {
            try {
                String msg = EngineManager.removeSystemLayer(this);
                ui.post(() -> {
                    remove.setEnabled(true);
                    engineStatus.setText("Removed — reboot to unload");
                    toast(msg);
                    refreshNotice();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    remove.setEnabled(true);
                    engineStatus.setText("Remove failed");
                    toast(e.getMessage() == null ? "Remove failed" : e.getMessage());
                });
            }
        });
    }

    private void checkStatus() {
        engineStatus.setText("Checking root…");
        io.execute(() -> {
            boolean root = EngineManager.hasRoot();
            boolean installed = root && EngineManager.systemLayerInstalled();
            if (root) EngineManager.setLayerInstalled(this, installed);
            ui.post(() -> {
                engineStatus.setText(!root
                        ? "Root unavailable — profile/export mode only"
                        : installed ? "Installed — module present" : "Not installed");
                refreshNotice();
            });
        });
    }

    private void pickHostFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_HOST_TREE);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
        }
    }

    private void exportConf() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "win-fg-conf.toml");
        try {
            startActivityForResult(i, REQ_EXPORT_CONF);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == REQ_HOST_TREE) {
            Uri tree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (Exception ignored) {}
            getSharedPreferences(EngineManager.PREFS, MODE_PRIVATE).edit()
                    .putString("host_tree", tree.toString()).apply();
            refreshHostLabel();
            io.execute(() -> {
                try {
                    int files = EngineManager.syncHostFolder(this, tree, settings);
                    ui.post(() -> toast("Copied " + files + " engine files to host folder"));
                } catch (Exception e) {
                    ui.post(() -> toast("Host folder write failed"));
                }
            });
        } else if (requestCode == REQ_EXPORT_CONF) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("no stream");
                out.write(settings.toToml().getBytes("UTF-8"));
                toast("Profile exported");
            } catch (Exception e) {
                toast("Export failed");
            }
        }
    }

    private void refreshNotice() {
        if (libraryNotice == null) return;
        boolean installed = EngineManager.layerInstalled(this);
        String text;
        if (!settings.enabled) {
            text = "●  FRAME GENERATION OFF  ·  the layer passes frames through untouched";
        } else if (installed) {
            text = "●  SYSTEM LAYER  ·  " + settings.summary() + "  ·  applied through winfg.* properties";
        } else {
            text = "●  PROFILE MODE  ·  " + settings.summary()
                    + "  ·  install the layer in ENGINE for system-wide frame gen";
        }
        libraryNotice.setText(text);
        libraryNotice.setTextColor(getColor(
                settings.enabled && installed ? R.color.accent : R.color.muted));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("No browser available");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private abstract static class Seek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
