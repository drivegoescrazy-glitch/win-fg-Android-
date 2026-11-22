package com.winfg.launcher;

/** One launchable app in the game library. */
public final class GameEntry {
    public final String packageName;
    public final String activityName;
    public final String label;
    public final boolean game;

    public GameEntry(String packageName, String activityName, String label, boolean game) {
        this.packageName = packageName;
        this.activityName = activityName;
        this.label = label;
        this.game = game;
    }
}
