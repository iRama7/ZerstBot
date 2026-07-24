package me.rama.bot;

import me.rama.Config;
import me.rama.Meme;
import me.rama.ex.InvalidVoteException;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

import static me.rama.Main.db;
import static me.rama.Main.jda;

public class ButtonListener extends ListenerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ButtonListener.class);

    private TextChannel memeChannel;
    private final Config config;

    public ButtonListener(Config config) {
        this.config = config;
    }

    public void init(){
        memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        if(memeChannel == null){
            LOGGER.error("Could not retrieve meme channel.");
        }
    }


    public void onButtonInteraction(@NotNull ButtonInteractionEvent event){

        if (memeChannel != null) {
            if (event.getChannel().getId().equals(memeChannel.getId())) {
                if (event.getComponentId().equals("upButton")) {
                    Message message = event.getMessage();
                    Meme meme = null;
                    try {
                        meme = db.getMeme(Long.parseLong(message.getId()));
                    } catch (SQLException e) {
                        LOGGER.error("Could not retrieve meme from database: {}", e.getMessage());
                    }

                    if (meme == null) {
                        event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto, contacta a un administrador.").setEphemeral(true).queue();
                    } else {

                        User user = event.getUser();
                        User memeAuthor = jda.getUserById(meme.getAuthorId());
                        if(user.equals(memeAuthor)){
                            event.reply("No podés votar tu propio meme <:pepow:1280353071267971174>").setEphemeral(true).queue();
                        }else {

                            long messageId = Long.parseLong(message.getId());
                            long userId = event.getUser().getIdLong();
                            try {
                                boolean hasVoted = db.hasVoted(messageId, userId);
                                boolean success;

                                if (hasVoted) {
                                    success = db.removeVoteAndDecrement(messageId, userId);
                                    if (success) {
                                        meme.unVote(userId);
                                        Button oldButton = event.getButton();
                                        Button newButton = oldButton.withLabel(meme.getVotes() + "");
                                        event.editComponents(ActionRow.of(newButton)).queue();
                                    }
                                } else {
                                    success = db.addVoteAndIncrement(messageId, userId);
                                    if (success) {
                                        meme.vote(userId);
                                        Button oldButton = event.getButton();
                                        Button newButton = oldButton.withLabel(meme.getVotes() + "");
                                        event.editComponents(ActionRow.of(newButton)).queue();
                                    }
                                }
                                if (!success) {
                                    LOGGER.error("Database and cache mismatch error.");
                                    event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto en la base de datos, contacta a un administrador.").setEphemeral(true).queue();
                                }
                            } catch (SQLException | InvalidVoteException e) {
                                LOGGER.error("Could not manage a vote: {}", e.getMessage());
                                event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto, contacta a un administrador.").setEphemeral(true).queue();
                            }
                        }
                    }
                }
            }
        }

    }


}
