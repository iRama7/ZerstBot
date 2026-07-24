package me.rama;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;


public class Config {

    private long memeChannelId;
    private long guildID;
    private String upvoteEmojiCode;
    private long memeCooldownSeconds;


    public static Config load(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath)) {
            Config config = new Gson().fromJson(reader, Config.class);

            if (config == null) {
                throw new IOException("Config file is empty or null.");
            }

            return config;
        } catch (JsonParseException e) {
            throw new IOException("JSON is invalid.", e);
        }
    }

    private void validate() throws IOException {
        if (memeChannelId <= 0 || guildID <= 0) {
            throw new IOException("ChannelId must be a valid channel ID.");
        }
    }

    public long getMemeChannelId() {
        return memeChannelId;
    }

    public long getGuildID() {
        return guildID;
    }


    public String getUpvoteEmojiCode() {

        return upvoteEmojiCode;
    }

    public long getMemeCooldownSeconds() {
        return memeCooldownSeconds;
    }
}
