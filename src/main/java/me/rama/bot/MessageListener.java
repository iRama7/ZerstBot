package me.rama.bot;

import me.rama.Config;
import me.rama.Meme;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static me.rama.Main.*;

/**
 * Listener principal que procesa los mensajes enviados en el canal de memes.
 * <p>
 * Clasifica cada mensaje en una de tres categorías:
 * <ol>
 *   <li><b>URL</b> — se muestra como embed con enlace clickeable.</li>
 *   <li><b>Imagen adjunta</b> — se descarga y re-publica como embed con la imagen.</li>
 *   <li><b>Otro</b> — se elimina con un aviso al usuario.</li>
 * </ol>
 * También gestiona el cooldown entre publicaciones y permite overrides manuales.
 */
public class MessageListener extends ListenerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MessageListener.class);

    /**
     * Mapa de overrides de cooldown: <ID de usuario, tiene permiso especial>.
     * Se activa mediante el comando /cd y se consume en el primer uso.
     */
    private final Map<Long, Boolean> cooldownOverrides = new ConcurrentHashMap<>();

    private final Config config;
    private TextChannel memeChannel;
    private Guild guild;

    public MessageListener(Config config) {
        this.config = config;
    }

    /**
     * Inicializa las referencias al canal de memes y al servidor principal.
     * Debe llamarse después de que JDA esté listo ({@code jda.awaitReady()}).
     */
    public void init() {
        memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        if (memeChannel == null) {
            LOGGER.error("Could not retrieve meme channel.");
        }

        guild = jda.getGuildById(config.getGuildID());
        if (guild == null) {
            LOGGER.warn("Main guild not found.");
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignorar mensajes del propio bot para evitar loops
        if (event.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
            return;
        }

        // Solo procesar mensajes del canal de memes configurado
        if (memeChannel != null && event.getChannel().getId().equals(memeChannel.getId())) {
            handleMessage(event.getChannel(), event.getMessage(), event.getAuthor());
        }
    }

    /**
     * Procesa un mensaje del canal de memes.
     * <p>
     * Flujo:
     * <ol>
     *   <li>Verifica cooldown del autor.</li>
     *   <li>Clasifica el contenido (URL, imagen, o inválido).</li>
     *   <li>Publica el embed correspondiente y guarda en BD.</li>
     *   <li>Elimina el mensaje original del usuario.</li>
     * </ol>
     */
    private void handleMessage(MessageChannel channel, Message message, User author) {
        channel.sendTyping().queue();
        ParsedMessage pMessage = new ParsedMessage(message);

        // ── Caso 1: El mensaje contiene una URL ────────────────
        if (pMessage.hasUrl()) {

            boolean canSend = checkCD(author.getIdLong(), channel);
            if (!canSend) {
                message.delete().queue();
            } else {
                MessageEmbed embed = buildURLEmbed(pMessage);
                memeChannel.sendMessageEmbeds(embed)
                        .setComponents(ActionRow.of(upButton))
                        .queue(s -> {
                            Meme meme = new Meme(s.getId(), author.getId(), 0);
                            try {
                                db.saveMeme(meme);
                                db.setLastProcessedId(s.getId());
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
                        });
                message.delete().queue();
            }

            // ── Caso 2: El mensaje tiene una imagen adjunta ────
        } else if (pMessage.hasAttachedImage()) {

            boolean canSend = checkCD(author.getIdLong(), channel);
            if (!canSend) {
                message.delete().queue();
            } else {

                // Validar tamaño del archivo según el boost del servidor
                long maxSize = 10000000L; // 10 MB por defecto
                Message.Attachment attachment = pMessage.getFirstAttachment();
                if (guild != null) {
                    maxSize = guild.getBoostTier().getMaxFileSize();
                } else {
                    LOGGER.warn("Could not retrieve guild boost tier because guild is null. Defaulting to 10MB");
                }

                if (attachment.getSize() > maxSize) {
                    message.delete().queue();
                    channel.sendMessage(author.getAsMention() + " <:pepow:1280353071267971174> La imagen/gif que subiste pesa demasiado!")
                            .queue(msg -> msg.delete().queueAfter(3, TimeUnit.SECONDS));
                    return;
                }

                // Descargar la imagen y luego publicar el embed
                pMessage.captureImage()
                        .thenAccept(pm -> {
                            MessageEmbed embed = buildImageEmbed(pm);
                            memeChannel.sendMessageEmbeds(embed)
                                    .setComponents(ActionRow.of(upButton))
                                    .addFiles(pm.toFileUpload())
                                    .queue(s -> {
                                        Meme meme = new Meme(s.getId(), author.getId(), 0);
                                        try {
                                            db.saveMeme(meme);
                                            db.setLastProcessedId(s.getId());
                                        } catch (SQLException e) {
                                            throw new RuntimeException(e);
                                        }
                                        message.delete().queue();
                                    });
                        })
                        .exceptionally(ex -> {
                            LOGGER.error("Error capturing image", ex);
                            return null;
                        });
            }

            // ── Caso 3: El mensaje no es ni URL ni imagen ──────
        } else {
            message.delete().queue();
            channel.sendMessage(author.getAsMention() + " Este canal es solo para memes <:pepow:1280353071267971174>")
                    .queue(msg -> msg.delete().queueAfter(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Construye un embed para un meme que contiene una URL.
     * La URL se muestra como descripción clickeable.
     */
    private MessageEmbed buildURLEmbed(ParsedMessage message) {
        String description = message.getText();
        User author = message.getAuthor();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setAuthor(author.getEffectiveName(), null, author.getAvatarUrl());
        eb.setTitle(description);
        eb.setDescription(message.getUrl());
        eb.setTimestamp(Instant.now());
        eb.setColor(Color.YELLOW);
        return eb.build();
    }

    /**
     * Construye un embed para un meme con imagen adjunta.
     * La imagen se referencia como attachment://nombre para incrustarla en el mensaje.
     */
    private MessageEmbed buildImageEmbed(ParsedMessage message) {
        String description = message.getText();
        User author = message.getAuthor();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setAuthor(author.getEffectiveName(), null, author.getAvatarUrl());
        eb.setTitle(description);
        eb.setImage("attachment://" + message.getImageFileName());
        eb.setTimestamp(Instant.now());
        eb.setColor(Color.YELLOW);
        return eb.build();
    }

    /**
     * Recupera los mensajes que quedaron sin procesar mientras el bot estuvo offline.
     * Busca en el historial del canal desde el último ID procesado y los re-procesa.
     */
    public void loadUncheckedMemes() {
        TextChannel memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        long lastMessageProcessedId = 0;
        try {
            lastMessageProcessedId = db.getLastProcessedId();
        } catch (SQLException | NullPointerException e) {
            LOGGER.error("Could not retrieve last processed id from database {}", e.getMessage());
        }

        if (memeChannel != null) {

            if (lastMessageProcessedId == 0) {
                LOGGER.info("Last processed id is 0 — no messages to recover.");
            } else {
                memeChannel.getHistoryAfter(lastMessageProcessedId, 100).queue(messages -> {

                    List<Message> history = messages.getRetrievedHistory();

                    LOGGER.info("Loading {} unchecked messages from last time.", history.size());

                    for (Message message : history) {
                        // Evitar re-procesar mensajes del propio bot
                        if (!message.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                            handleMessage(memeChannel, message, message.getAuthor());
                        }
                    }
                });
            }
        }
    }

    /**
     * Verifica si el autor puede publicar otro meme según el cooldown configurado.
     * <p>
     * Primero chequea si hay un override activo (por /cd). Si no, consulta la
     * última publicación del usuario en la BD y compara con el tiempo actual.
     *
     * @param authorId ID del usuario en Discord.
     * @param channel  Canal donde se está intentando publicar (para enviar el mensaje de error).
     * @return true si puede publicar, false si debe esperar.
     */
    private boolean checkCD(Long authorId, MessageChannel channel) {
        boolean canSend = false;

        // ── Verificar override de cooldown (activado por /cd) ──
        boolean hasOverride = cooldownOverrides.getOrDefault(authorId, false);
        if (hasOverride) {
            cooldownOverrides.put(authorId, false); // Consumir el override (un solo uso)
            return true;
        }

        try {
            Long lastTimestamp = db.getLastMemeTimestamp(authorId);

            if (lastTimestamp != null) {
                Duration cooldown = Duration.ofSeconds(config.getMemeCooldownSeconds());
                Instant lastPost = Instant.ofEpochMilli(lastTimestamp);
                Duration elapsed = Duration.between(lastPost, Instant.now());

                if (elapsed.compareTo(cooldown) < 0) {
                    // Todavía está en cooldown
                    Duration remaining = cooldown.minus(elapsed);

                    channel.sendMessage("Todavía no podés postear otro meme. Esperá "
                                    + formatDuration(remaining) + " <:pepow:1280353071267971174>")
                            .queue(m -> m.delete().queueAfter(3, TimeUnit.SECONDS));

                } else {
                    canSend = true;
                }
            } else {
                // El usuario nunca publicó un meme → puede publicar sin restricción
                canSend = true;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not retrieve last meme timestamp from database {}", e.getMessage());
        }

        return canSend;
    }

    /**
     * Formatea una duración en un string legible en español.
     * Ejemplos: "2 día/s 3 hora/s", "5 hora/s 30 minuto/s", "45 minuto/s".
     */
    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        if (days > 0) {
            return days + " día/s " + hours + " hora/s";
        } else if (hours > 0) {
            return hours + " hora/s " + minutes + " minuto/s";
        } else {
            return minutes + " minuto/s";
        }
    }

    /**
     * Activa o desactiva el override de cooldown para un usuario.
     * Usado por el comando /cd para permitir que un usuario publique sin esperar.
     *
     * @param id ID del usuario como String (se parsea a Long internamente).
     * @param b  true para activar el override, false para desactivarlo.
     */
    public void putOverride(String id, boolean b) {
        cooldownOverrides.put(Long.parseLong(id), b);
    }
}
