package com.winfg.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** The frame-generation profile edited in the app and written to win-fg. */
public final class AppSettings {
    private static final String PREF = "winfg_profile";

    public boolean enabled = true;
    public boolean pacing = false;
    public boolean debug = false;
    public int model = 4;          // 3 = optical flow, 4 = bidirectional
    public int multiplier = 2;     // 2..4
    public int perfPreset = 1;     // 0 quality, 1 balanced, 2 performance
    public float flowScale = 1.0f;
    public int gmMode = 0;         // 0 auto, 1 on, 2 off
    public int frMode = 0;         // 0 auto, 1 on, 2 off
    public int frIters = 4;
    public float frLambda = 2.0f;
    public float frDt = 0.25f;
    public float frEdge = 8.0f;
    public float frEps = 0.05f;
    public int extraImages = 2;

    public static AppSettings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.enabled = p.getBoolean("enabled", s.enabled);
        s.pacing = p.getBoolean("pacing", s.pacing);
        s.debug = p.getBoolean("debug", s.debug);
        s.model = p.getInt("model", s.model);
        s.multiplier = p.getInt("multiplier", s.multiplier);
        s.perfPreset = p.getInt("perf_preset", s.perfPreset);
        s.flowScale = p.getFloat("flow_scale", s.flowScale);
        s.gmMode = p.getInt("gm_mode", s.gmMode);
        s.frMode = p.getInt("fr_mode", s.frMode);
        s.frIters = p.getInt("fr_iters", s.frIters);
        s.frLambda = p.getFloat("fr_lambda", s.frLambda);
        s.frDt = p.getFloat("fr_dt", s.frDt);
        s.frEdge = p.getFloat("fr_edge", s.frEdge);
        s.frEps = p.getFloat("fr_eps", s.frEps);
        s.extraImages = p.getInt("extra_images", s.extraImages);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putBoolean("pacing", pacing)
                .putBoolean("debug", debug)
                .putInt("model", model)
                .putInt("multiplier", multiplier)
                .putInt("perf_preset", perfPreset)
                .putFloat("flow_scale", flowScale)
                .putInt("gm_mode", gmMode)
                .putInt("fr_mode", frMode)
                .putInt("fr_iters", frIters)
                .putFloat("fr_lambda", frLambda)
                .putFloat("fr_dt", frDt)
                .putFloat("fr_edge", frEdge)
                .putFloat("fr_eps", frEps)
                .putInt("extra_images", extraImages)
                .apply();
    }

    public String modelName() {
        return model == 3 ? "OPTICAL FLOW" : "BIDIRECTIONAL";
    }

    public String presetName() {
        switch (perfPreset) {
            case 0: return "QUALITY";
            case 2: return "PERFORMANCE";
            default: return "BALANCED";
        }
    }

    public String summary() {
        return multiplier + "×  ·  " + modelName() + "  ·  " + presetName();
    }

    private static String tristate(int mode) {
        return mode == 0 ? "auto" : (mode == 1 ? "on" : "off");
    }

    /** The flat TOML-lite profile understood by win-fg's layer build. */
    public String toToml() {
        StringBuilder b = new StringBuilder();
        b.append("# Win-FG profile — written by the Win-FG launcher.\n");
        b.append("# Hot-reloaded by the layer; the launcher also mirrors these values\n");
        b.append("# into winfg.* system properties when the system layer is installed.\n");
        b.append("enabled = ").append(enabled).append('\n');
        b.append("debug = ").append(debug).append('\n');
        b.append("pacing = ").append(pacing).append('\n');
        b.append("model = ").append(model).append('\n');
        b.append("multiplier = ").append(multiplier).append('\n');
        b.append("perf_preset = ").append(perfPreset).append('\n');
        b.append(String.format(Locale.US, "flowScale = %.2f\n", flowScale));
        b.append("extra_images = ").append(extraImages).append('\n');
        b.append("global_motion = ").append(tristate(gmMode)).append('\n');
        b.append("flow_reg = ").append(tristate(frMode)).append('\n');
        b.append("fr_iters = ").append(frIters).append('\n');
        b.append(String.format(Locale.US, "fr_lambda = %.2f\n", frLambda));
        b.append(String.format(Locale.US, "fr_dt = %.2f\n", frDt));
        b.append(String.format(Locale.US, "fr_edge = %.1f\n", frEdge));
        b.append(String.format(Locale.US, "fr_eps = %.3f\n", frEps));
        return b.toString();
    }
}
