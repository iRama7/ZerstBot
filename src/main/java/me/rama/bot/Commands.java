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

/**
 * Listener de comandos slash y botones del leaderboard.
 * <p>
 * Maneja:
 * - {@code /top} — muestra el ranking de memes (semanal o histórico).
 * - {@code /cd} — resetea el cooldown de un usuario (solo moderadores).
 * - Botón "🔄 Actualizar" en el leaderboard para refrescar los datos.
 */
public class Commands extends ListenerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Commands.class);

    private final Config config;

    /**
     * Mapa de cooldown por ID de mensaje para evitar spam en el botón de refrescar leaderboard.
     * Cada mensaje de leaderboard tiene su propio cooldown de 10 segundos.
     */
    private final Map<Long, Instant> refreshCooldowns = new ConcurrentHashMap<>();

    /** Duración del cooldown entre refrescos del leaderboard. */
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    public Commands(Config config) {
        this.config = config;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {

        // ── /top — Leaderboard de memes ─────────────────────────
        if (event.getName().equals("top")) {

            String periodo = event.getOption("periodo").getAsString();
            // Si es "weekly" filtramos por 7 días; si es "alltime", period = null (sin filtro)
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

        // ── /cd — Resetear cooldown de un usuario ───────────────
        if (event.getName().equals("cd")) {

            User target;
            OptionMapping targetOption = event.getOption("usuario");
            try {
                if (targetOption != null) {
                    target = targetOption.getAsUser();
                } else {
                    throw new NullPointerException("El usuario es null?");
                }
            } catch (NullPointerException | IllegalStateException e) {
                event.reply("No se pudo obtener al usuario: " + e.getMessage()).setEphemeral(true).queue();
                return;
            }

            // Activa el override para que el usuario pueda saltarse el cooldown una vez
            messageListener.putOverride(target.getId(), true);

            event.reply("<:peepoHype:869914174317289472> " + target.getAsMention() + " puede postear un meme sin esperar el cooldown, la próxima vez que lo haga.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        // Botón de refrescar leaderboard
        if (event.getComponentId().startsWith("leaderboard_refresh:")) {

            // ── Anti-spam: cooldown de 10s por mensaje ──────────
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

            // ── Refrescar leaderboard ───────────────────────────
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

    /**
     * Construye el embed del leaderboard con el formato:
     * - Top 3: 🥇, 🥈, 🥉 con nombre del autor
     * - Del 4° al 10°: numeración simple
     * Cada entrada incluye cantidad de votos y un enlace al mensaje original.
     *
     * @param top     Lista ordenada de entradas del leaderboard.
     * @param periodo "weekly" o "alltime" (define el título).
     * @param guildID ID del servidor para construir los enlaces.
     * @return Embed listo para enviar.
     */
    public MessageEmbed buildLeaderboardEmbed(List<Database.LeaderboardEntry> top, String periodo, Long guildID) {

        String title = periodo.equals("weekly") ? "🏆 Top semanal de Memes 🏆" : "🏆 Top global de Memes 🏆";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setColor(Color.ORANGE);
        embed.setDescription(" ");
        embed.setTimestamp(Instant.now());

        String[] medals = {"🥇", "🥈", "🥉"};
        // Orden del podio: primero el 2° lugar (🥈), luego 1° (🥇), luego 3° (🥉)
        // para que queden uno al lado del otro visualmente en el embed
        int[] podiumOrder = {1, 0, 2};

        // ── Podio (top 3) ───────────────────────────────────────
        for (int rank : podiumOrder) {
            if (rank >= top.size()) continue;

            Database.LeaderboardEntry entry = top.get(rank);
            String messageLink = String.format(
                    "https://discord.com/channels/%d/%d/%d",
                    guildID,
                    config.getMemeChannelId(),
                    entry.messageId()
            );

            // Intentar obtener el usuario desde caché, si no está, pedirlo a la API
            User author = jda.getUserById(entry.authorId());
            if (author == null) {
                author = jda.retrieveUserById(entry.authorId()).complete();
            }

            String author_name = author.getEffectiveName();

            String fieldName = medals[rank] + " " + author_name;
            String votesLabel = entry.upvotes() == 1 ? " voto" : " votos";
            String fieldValue = entry.upvotes() + votesLabel + "\n[Ir al mensaje](" + messageLink + ")";

            // inline = true para que los 3 del podio se alineen horizontalmente
            embed.addField(fieldName, fieldValue, true);
        }

        // ── Resto del top (4° en adelante) ──────────────────────
        for (int i = 3; i < top.size(); i++) {
            Database.LeaderboardEntry entry = top.get(i);
            String messageLink = String.format(
                    "https://discord.com/channels/%d/%d/%d",
                    guildID,
                    config.getMemeChannelId(),
                    entry.messageId()
            );

            User author = jda.getUserById(entry.authorId());
            if (author == null) {
                author = jda.retrieveUserById(entry.authorId()).complete();
            }

            String author_name = author.getEffectiveName();

            String votesLabel = entry.upvotes() == 1 ? " voto" : " votos";

            String fieldName = (i + 1) + ". " + author_name;
            String fieldValue = entry.upvotes() + votesLabel + "\n[Ir al mensaje](" + messageLink + ")";

            // inline = false para que cada entrada ocupe su propia línea
            embed.addField(fieldName, fieldValue, false);
        }

        return embed.build();
    }


}
