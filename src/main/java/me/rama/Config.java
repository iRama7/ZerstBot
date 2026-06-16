package me.rama;

public class Config {

    private long memeChannelId;
    private long logChannelId;
    private int weeklyLeaderboardSize;

    public int getWeeklyLeaderboardSize() {
        return weeklyLeaderboardSize;
    }

    public long getLogChannelId() {
        return logChannelId;
    }

    public long getMemeChannelId() {
        return memeChannelId;
    }
}
