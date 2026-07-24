package me.rama.bot;

import me.rama.Config;
import me.rama.db.Database;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static me.rama.Main.*;

public class Commands extends ListenerAdapter {


    private final Config config;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Commands.class);

    private final Map<Long, Instant> refreshCooldowns = new ConcurrentHashMap<>();
    private static final Duration COOLDOWN = Duration.ofSeconds(10);


    public Commands(Config config){
        this.config = config;
    }


    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("top")) {

            String periodo = event.getOption("periodo").getAsString();
            Duration duration = periodo.equals("weekly") ? Duration.ofDays(7) : null;

            event.deferReply(true).queue();

            try {
                List<Database.LeaderboardEntry> top = db.getLeaderboard(duration, 10);

                if (top.isEmpty()) {
                    event.getHook().sendMessage("No hay memes registrados en este periodo.").setEphemeral(true).queue();
                    return;
                }


                MessageEmbed e = buildLeaderboardEmbed(top, periodo, event.getGuild().getIdLong());
                Button refreshButton = Button.secondary("leaderboard_refresh:" + periodo, "🔄 Actualizar");


                event.getHook().sendMessageEmbeds(e).setComponents(ActionRow.of(refreshButton)).setEphemeral(true).queue();

            } catch (SQLException e) {
                LOGGER.error("Error al obtener el leaderboard: {}", e.getMessage());
                event.getHook().sendMessage("Ocurrió un error al generar el leaderboard.").setEphemeral(true).queue();
            }
        }

        if (event.getName().equals("cd")) {

            User target;
            OptionMapping targetOption = event.getOption("usuario");
            try {
                if (targetOption != null) {
                    target = targetOption.getAsUser();
                }else{
                    throw new NullPointerException("El usuario es null?");
                }
            }catch (NullPointerException | IllegalStateException e){
                event.reply("No se pudo obtener al usuario: " + e.getMessage()).setEphemeral(true).queue();
                return;
            }

            messageListener.putOverride(target.getId(), true);


            event.reply("<:peepoHype:869914174317289472> " + target.getAsMention() + " puede postear un meme sin esperar el cooldown, la próxima vez que lo haga.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getComponentId().startsWith("leaderboard_refresh:")) {


            long messageId = event.getMessageIdLong();
            Instant lastUse = refreshCooldowns.get(messageId);
            Instant now = Instant.now();

            if (lastUse != null) {
                Duration elapsed = Duration.between(lastUse, now);
                if (elapsed.compareTo(COOLDOWN) < 0) {
                    event.reply("No spamees el update <:pepow:1280353071267971174>")
                            .setEphemeral(true)
                            .queue();
                    return;
                }
            }

            refreshCooldowns.put(messageId, now);

            String periodo = event.getComponentId().split(":")[1];
            Duration period = periodo.equals("weekly") ? Duration.ofDays(7) : null;

            event.deferEdit().queue();

            try {
                List<Database.LeaderboardEntry> top = db.getLeaderboard(period, 10);

                if (top.isEmpty()) {
                    event.getHook().editOriginal("No hay memes registrados en este periodo.")
                            .setEmbeds()
                            .queue();
                    return;
                }

                MessageEmbed updatedEmbed = buildLeaderboardEmbed(top, periodo, event.getGuild().getIdLong());
                event.getHook().editOriginalEmbeds(updatedEmbed).queue();

            } catch (SQLException e) {
                LOGGER.error("Error al actualizar el leaderboard: {}", e.getMessage());
                event.getHook().sendMessage("Ocurrió un error al actualizar.").setEphemeral(true).queue();
            }
        }
    }

    public MessageEmbed buildLeaderboardEmbed(List<Database.LeaderboardEntry> top, String periodo, Long guildID) {

        String title = periodo.equals("weekly") ? "🏆 Top semanal de Memes 🏆" : "🏆 Top global de Memes 🏆";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setColor(Color.ORANGE);
        embed.setDescription(" ");
        embed.setTimestamp(Instant.now());

        String[] medals = {"🥇", "🥈", "🥉"};
        int[] podiumOrder = {1, 0, 2};

        for (int rank : podiumOrder) {
            if (rank >= top.size()) continue;

            Database.LeaderboardEntry entry = top.get(rank);
            String messageLink = String.format(
                    "https://discord.com/channels/%d/%d/%d",
                    guildID,
                    config.getMemeChannelId(),
                    entry.messageId()
            );


            String fieldName = medals[rank] + " " + jda.retrieveUserById(entry.authorId()).complete().getEffectiveName();
            String votes = entry.upvotes() > 1 ? " votos" : " voto";
            String fieldValue = entry.upvotes() + votes + "\n[Ir al mensaje](" + messageLink + ")";


            embed.addField(fieldName, fieldValue, true);
        }

        for (int i = 3; i < top.size(); i++) {
            Database.LeaderboardEntry entry = top.get(i);
            String messageLink = String.format(
                    "https://discord.com/channels/%d/%d/%d",
                    guildID,
                    config.getMemeChannelId(),
                    entry.messageId()
            );

            String fieldName = (i + 1) + ". " + jda.retrieveUserById(entry.authorId()).complete().getEffectiveName();
            String fieldValue = entry.upvotes() + " votos\n[Ir al mensaje](" + messageLink + ")";

            embed.addField(fieldName, fieldValue, false);

        }

        return embed.build();

    }




}
