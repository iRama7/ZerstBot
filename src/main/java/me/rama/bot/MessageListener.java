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


public class MessageListener extends ListenerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MessageListener.class);

    private final Map<Long, Boolean> cooldownOverrides = new ConcurrentHashMap<>();


    private final Config config;
    private TextChannel memeChannel;
    private Guild guild;

    public MessageListener(Config config){
        this.config = config;
    }

    public void init(){
        memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        if(memeChannel == null){
            LOGGER.error("Could not retrieve meme channel.");
        }

        guild = jda.getGuildById(config.getGuildID());
        if(guild == null){
            LOGGER.warn("Main guild not found.");
        }


    }


    public void onMessageReceived(MessageReceivedEvent event) {

        User sender = event.getAuthor();

        if(!sender.getId().equals(event.getJDA().getSelfUser().getId())) { // 1514631312437280859 = bot ID

            if (memeChannel != null) {
                if (event.getChannel().getId().equals(memeChannel.getId())) {
                    handleMessage(event.getChannel(), event.getMessage(), sender);
                }
            }
        }

    }

    private void handleMessage(MessageChannel channel, Message message, User author){
        channel.sendTyping().queue();
        ParsedMessage pMessage = new ParsedMessage(message);
        if(pMessage.hasUrl()) {

            boolean canSend = checkCD(author.getIdLong(), channel);
            if(!canSend){
                message.delete().queue();
            }else {

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
        } else if(pMessage.hasAttachedImage()){

            boolean canSend = checkCD(author.getIdLong(), channel);
            if(!canSend) {
                message.delete().queue();
            }else {

                long maxSize = 10000000L;
                Message.Attachment attachment = pMessage.getFirstAttachment();
                try {
                    maxSize = guild.getBoostTier().getMaxFileSize();
                } catch (NullPointerException e) {
                    LOGGER.error("Could not retrieve guild boost tier because guild is null. Defaulting to 10MB");
                }

                if (attachment.getSize() > maxSize) {

                    message.delete().queue();
                    channel.sendMessage(author.getAsMention() + "<:pepow:1280353071267971174> La imagen/gif que subiste pesa demasiado!"
                    ).queue(msg -> msg.delete().queueAfter(3, TimeUnit.SECONDS));
                    return;
                }


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
        }else {

            message.delete().queue();
            channel.sendMessage(author.getAsMention() + " Este canal es solo para memes <:pepow:1280353071267971174>").queue(msg -> msg.delete().queueAfter(3, TimeUnit.SECONDS));
        }

    }

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

    public void loadUncheckedMemes(){
        TextChannel memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        long lastMessageProcessedId = 0;
        try {
            lastMessageProcessedId = db.getLastProcessedId();
        }catch(SQLException | NullPointerException e){
            LOGGER.error("Could not retrieve last processed id from database {}", e.getMessage());
        }

        if(memeChannel != null){

            if(lastMessageProcessedId == 0){
                LOGGER.info("Last processed id is 0");
            }else {

                memeChannel.getHistoryAfter(lastMessageProcessedId, 100).queue(messages -> {

                    List<Message> history = messages.getRetrievedHistory();

                    LOGGER.info("Loading {} unchecked messages from last time.", history.size());

                    for (Message message : history) {

                        if(!message.getAuthor().getId().equals("1514631312437280859")) {
                            handleMessage(memeChannel, message, message.getAuthor());
                        }
                    }
                });
            }

        }
    }

    private boolean checkCD(Long authorId, MessageChannel channel){
        boolean b = false;
        boolean hasOverride = cooldownOverrides.getOrDefault(authorId, false);
        if(hasOverride){
            return true;
        }
        try {
            Long lastTimestamp = db.getLastMemeTimestamp(authorId);

            if (lastTimestamp != null) {
                Duration cooldown = Duration.ofSeconds(config.getMemeCooldownSeconds());
                Instant lastPost = Instant.ofEpochMilli(lastTimestamp);
                Duration elapsed = Duration.between(lastPost, Instant.now());

                if (elapsed.compareTo(cooldown) < 0) {

                    Duration remaining = cooldown.minus(elapsed);

                    channel.sendMessage("Todavía no podés postear otro meme. Esperá "
                                    + formatDuration(remaining) + " <:pepow:1280353071267971174>")
                            .queue(m -> m.delete().queueAfter(3, TimeUnit.SECONDS));

                }else{
                    b = true;
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Could not retrieve last meme timestamp from database {}", e.getMessage());
        }

        return b;
    }

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


    public void putOverride(String id, boolean b) {

        cooldownOverrides.put(Long.parseLong(id), b);

    }
}
