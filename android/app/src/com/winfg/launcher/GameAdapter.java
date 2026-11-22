package com.winfg.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Renders the installed-apps game list. */
final class GameAdapter extends BaseAdapter {
    private final Context context;
    private final LayoutInflater inflater;
    private final PackageManager pm;
    private List<GameEntry> data = new ArrayList<>();
    private int multiplier;

    GameAdapter(Context context, int multiplier) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.pm = context.getPackageManager();
        this.multiplier = multiplier;
    }

    void setData(List<GameEntry> entries) {
        data = entries;
    }

    void setMultiplier(int multiplier) {
        this.multiplier = multiplier;
    }

    @Override public int getCount() { return data.size(); }
    @Override public GameEntry getItem(int position) { return data.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_game, parent, false);
        GameEntry g = data.get(position);

        TextView name = v.findViewById(R.id.game_name);
        TextView pkg = v.findViewById(R.id.game_pkg);
        TextView badge = v.findViewById(R.id.game_badge);
        ImageView icon = v.findViewById(R.id.game_icon);

        name.setText(g.label);
        pkg.setText(g.packageName);
        badge.setText(multiplier + "×");

        try {
            icon.setImageDrawable(pm.getActivityIcon(new ComponentName(g.packageName, g.activityName)));
        } catch (Exception e) {
            icon.setImageResource(R.drawable.ic_launcher);
        }
        return v;
    }
}
