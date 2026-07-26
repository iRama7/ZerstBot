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

/**
 * Listener que maneja las interacciones con los botones de upvote en los memes.
 * <p>
 * Permite a los usuarios votar o desvotar un meme (toggle).
 * Valida que el usuario no pueda votar su propio meme.
 * El contador de votos se actualiza primero en la BD y luego en el objeto {@link Meme} en memoria.
 */
public class ButtonListener extends ListenerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ButtonListener.class);

    private TextChannel memeChannel;
    private final Config config;

    public ButtonListener(Config config) {
        this.config = config;
    }

    /**
     * Inicializa la referencia al canal de memes.
     * Debe llamarse después de que JDA esté listo.
     */
    public void init() {
        memeChannel = jda.getTextChannelById(config.getMemeChannelId());
        if (memeChannel == null) {
            LOGGER.error("Could not retrieve meme channel.");
        }
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

        // Solo procesar botones del canal de memes
        if (memeChannel == null || !event.getChannel().getId().equals(memeChannel.getId())) {
            return;
        }

        // Solo procesar el botón de upvote (ignorar otros botones como el de refrescar leaderboard)
        if (!event.getComponentId().equals("upButton")) {
            return;
        }

        Message message = event.getMessage();
        Meme meme = null;
        try {
            meme = db.getMeme(Long.parseLong(message.getId()));
        } catch (SQLException e) {
            LOGGER.error("Could not retrieve meme from database: {}", e.getMessage());
        }

        if (meme == null) {
            event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto, contacta a un administrador.")
                    .setEphemeral(true).queue();
            return;
        }

        User user = event.getUser();
        User memeAuthor = jda.getUserById(meme.getAuthorId());

        // ── No permitir votar el propio meme ──────────────────
        if (user.equals(memeAuthor)) {
            event.reply("No podés votar tu propio meme <:pepow:1280353071267971174>")
                    .setEphemeral(true).queue();
            return;
        }

        long messageId = Long.parseLong(message.getId());
        long userId = event.getUser().getIdLong();

        try {
            boolean hasVoted = db.hasVoted(messageId, userId);
            boolean success;

            if (hasVoted) {
                // ── Ya votó → quitar voto (toggle) ────────────
                success = db.removeVoteAndDecrement(messageId, userId);
                if (success) {
                    meme.unVote(userId);
                    Button oldButton = event.getButton();
                    Button newButton = oldButton.withLabel(String.valueOf(meme.getVotes()));
                    event.editComponents(ActionRow.of(newButton)).queue();
                }
            } else {
                // ── No votó → agregar voto ────────────────────
                success = db.addVoteAndIncrement(messageId, userId);
                if (success) {
                    meme.vote(userId);
                    Button oldButton = event.getButton();
                    Button newButton = oldButton.withLabel(String.valueOf(meme.getVotes()));
                    event.editComponents(ActionRow.of(newButton)).queue();
                }
            }

            if (!success) {
                LOGGER.error("Database returned false for vote operation — possible duplicate or race condition.");
                event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto en la base de datos, contacta a un administrador.")
                        .setEphemeral(true).queue();
            }

        } catch (SQLException | InvalidVoteException e) {
            LOGGER.error("Could not manage a vote: {}", e.getMessage());
            event.reply("<:pepow:1280353071267971174> Ha ocurrido un error inesperado al registrar tu voto, contacta a un administrador.")
                    .setEphemeral(true).queue();
        }
    }

}
