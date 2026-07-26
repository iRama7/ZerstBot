package me.rama;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import me.rama.bot.ButtonListener;
import me.rama.bot.Commands;
import me.rama.bot.MessageListener;
import me.rama.db.Database;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * ZerstBot — Punto de entrada del bot de Discord para gestión de memes.
 * <p>
 * Inicializa JDA (Java Discord API), conecta la base de datos SQLite,
 * registra los listeners de eventos y los comandos slash, y arranca
 * la rotación periódica de actividad/estado.
 */
public class Main {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Main.class);

    // ──────────────────────────────────────────────────────────────
    // Singletons globales (accesibles desde cualquier listener)
    // NOTA: sería mejor inyectarlos, pero se usan estáticos por simplicidad.
    // ──────────────────────────────────────────────────────────────

    /** Instancia de JDA (conexión con Discord). */
    public static JDA jda;

    /** Conexión a la base de datos SQLite. */
    public static Database db;

    /** Configuración cargada desde config.json. */
    private static Config config;

    /** Botón de upvote que se adjunta a cada embed de meme. */
    public static Button upButton;

    /** Referencia al listener de mensajes (necesaria para el override de cooldown). */
    public static MessageListener messageListener;

    /** Lista de actividades/estados que se rotan cada hora. */
    private static final List<Activity> activities = List.of(
            Activity.customStatus("Viendo #memes"),
            Activity.customStatus("Intentando banear a palfer"),
            Activity.customStatus("Escuchando las vendidas de humo de Zerst"),
            Activity.customStatus("Que memes de mierda que mandan estos mortales"),
            Activity.customStatus("Desinstalándole el WoW a Zerst"),
            Activity.streaming("Viendo ZerstGaming en twitch!", "https://twitch.tv/zerstgaming")
    );


    public static void main(String[] args) {

        LOGGER.info("Starting ZerstBot...");

        // ── 1. Cargar configuración ──────────────────────────────
        try {
            config = Config.load(Path.of(getJarDirectory(), "config.json"));
        } catch (IOException e) {
            LOGGER.error("[Error] Could not load config.json {}", e.getMessage());
            return;
        }

        // ── 2. Obtener token desde .env ──────────────────────────
        String token = getToken();
        if (token == null) {
            LOGGER.error("[Error] Token may not be null");
        } else {

            // ── 3. Crear listeners ───────────────────────────────
            messageListener = new MessageListener(config);
            ButtonListener buttonListener = new ButtonListener(config);
            Commands commandsListener = new Commands(config);

            // ── 4. Construir y conectar JDA ──────────────────────
            jda = JDABuilder.createDefault(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .addEventListeners(messageListener, buttonListener, commandsListener)
                    // Deshabilitar caches que no usamos para ahorrar memoria
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SOUNDBOARD_SOUNDS, CacheFlag.SCHEDULED_EVENTS)
                    .setStatus(OnlineStatus.DO_NOT_DISTURB)
                    .build();
            try {
                jda.awaitReady();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // ── 5. Arrancar rotación de estado ───────────────────
            startActivityRotation(jda);

            // ── 6. Conectar base de datos SQLite ─────────────────
            try {
                db = new Database(getJarDirectory() + "/database.db");
            } catch (SQLException e) {
                LOGGER.error("Could not connect to database: {}", e.getMessage());
            }

            // ── 7. Inicializar componentes ───────────────────────
            upButton = getUpButton();
            buttonListener.init();
            messageListener.init();
            messageListener.loadUncheckedMemes();
            registerCommands();
        }
    }

    /**
     * Lee el token de Discord desde un archivo .env usando dotenv.
     * La variable debe llamarse ZERSTBOT_TOKEN.
     */
    public static String getToken() {
        String token = null;
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(getJarDirectory())
                    .load();
            token = dotenv.get("ZERSTBOT_TOKEN");
        } catch (DotenvException e) {
            LOGGER.error("[Error] Dotenv loading failed {}", e.getMessage());
        }
        return token;
    }

    /**
     * Obtiene el directorio donde está el JAR en ejecución.
     * Se usa para resolver rutas relativas de config.json, .env y database.db.
     */
    public static String getJarDirectory() {
        try {
            File jarFile = new File(
                    Main.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );

            return jarFile.getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Construye el botón de upvote que se muestra en cada embed de meme.
     * Usa el emoji configurado en config.json, o un cuadro negro (⬛) por defecto.
     */
    private static Button getUpButton() {
        Emoji upVoteEmoji = Emoji.fromFormatted("⬛");
        try {
            upVoteEmoji = Emoji.fromFormatted(config.getUpvoteEmojiCode());
        } catch (IllegalArgumentException e) {
            LOGGER.error("Could not retrieve upvote emoji from config.json: {}", e.getMessage());
        }

        return Button.of(ButtonStyle.SECONDARY, "upButton", "0", upVoteEmoji);
    }

    /**
     * Registra los comandos slash globales del bot:
     * - /top (periodo: weekly | alltime) — leaderboard de memes
     * - /cd @usuario — resetea el cooldown de un usuario (solo moderadores)
     */
    private static void registerCommands() {
        CommandData leaderboardCommand = net.dv8tion.jda.api.interactions.commands.build.Commands.slash("top", "Muestra el top de memes")
                .addOptions(new OptionData(OptionType.STRING, "periodo", "Periodo de tiempo", true)
                        .addChoice("Semanal", "weekly")
                        .addChoice("Todo el tiempo", "alltime"));

        CommandData resetCooldownCommand = net.dv8tion.jda.api.interactions.commands.build.Commands.slash("cd", "Resetea el cooldown para enviar un meme de un usuario")
                .addOption(OptionType.USER, "usuario", "El usuario a resetear", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS));

        jda.updateCommands().addCommands(leaderboardCommand, resetCooldownCommand).queue();
    }

    /**
     * Arranca un scheduler que cambia el "Ahora jugando..." del bot cada hora
     * con mensajes aleatorios de la lista {@link #activities}.
     */
    public static void startActivityRotation(JDA jda) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                Activity next = activities.get(new Random().nextInt(activities.size()));

                jda.getPresence().setActivity(next);

            } catch (Exception e) {
                LOGGER.warn("Error rotando actividad: {}", e.getMessage());
            }
        }, 0, 1, TimeUnit.HOURS);
    }


}
