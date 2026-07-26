package me.rama;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuración del bot, deserializada desde config.json mediante Gson.
 * <p>
 * Los campos se mapean automáticamente desde el JSON por nombre.
 * Ver config.example.json para la estructura esperada.
 */
public class Config {

    /** ID del canal de Discord donde se publican los memes. */
    private long memeChannelId;

    /** ID del servidor (guild) principal. */
    private long guildID;

    /** Código del emoji personalizado para el botón de upvote (ej: "⬆️" o "<:emoji:1234>"). */
    private String upvoteEmojiCode;

    /** Tiempo de espera en segundos entre publicaciones de un mismo usuario. */
    private long memeCooldownSeconds;

    /**
     * Carga la configuración desde un archivo JSON.
     *
     * @param configPath Ruta al archivo config.json.
     * @return Instancia de Config con los valores parseados.
     * @throws IOException Si el archivo no existe, está vacío o el JSON es inválido.
     */
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
