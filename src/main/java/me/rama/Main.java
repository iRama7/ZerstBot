package me.rama;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import me.rama.bot.ButtonListener;
import me.rama.bot.Commands;
import me.rama.bot.MessageListener;
import me.rama.db.Database;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
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


public class Main {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Main.class);

    public static JDA jda;
    public static Database db;
    private static Config config;
    public static Button upButton;
    public static MessageListener messageListener;


    public static void main(String[] args) {

        LOGGER.info("Starting ZerstBot...");

        try {
            config = Config.load(Path.of(getJarDirectory(), "config.json"));
        } catch (IOException e) {
            LOGGER.error("[Error] Could not load config.json {}", e.getMessage());
            return;
        }

        String token = getToken();
        if (token == null) {
            LOGGER.error("[Error] Token may not be null");
        }else {

            messageListener = new MessageListener(config);
            ButtonListener buttonListener = new ButtonListener(config);
            Commands commandsListener = new Commands(config);
            jda = JDABuilder.createDefault(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .addEventListeners(messageListener, buttonListener, commandsListener)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SOUNDBOARD_SOUNDS, CacheFlag.SCHEDULED_EVENTS)
                    .build();
            try {
                jda.awaitReady();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {
                db = new Database(getJarDirectory() + "/database.db");
            }catch (SQLException e){
                LOGGER.error("Could not connect to database: {}", e.getMessage());
            }

            upButton = getUpButton();
            buttonListener.init();
            messageListener.init();
            messageListener.loadUncheckedMemes();
            registerCommands();
        }
    }

    public static String getToken(){
        String token = null;
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(getJarDirectory())
                    .load();
            token = dotenv.get("ZERSTBOT_TOKEN");
        }catch (DotenvException e) {
            LOGGER.error("[Error] Dotenv loading failed {}", e.getMessage());
        }
        return token;
    }


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

    private static Button getUpButton(){
        Emoji upVoteEmoji = Emoji.fromFormatted("⬛");
        try {
            upVoteEmoji = Emoji.fromFormatted(config.getUpvoteEmojiCode());
        }catch (IllegalArgumentException e){
            LOGGER.error("Could not retrieve upvote emoji from config.json: {}", e.getMessage());
        }

        return Button.of(ButtonStyle.SECONDARY, "upButton", "0", upVoteEmoji);
    }

    private static void registerCommands(){
        CommandData leaderboardCommand = net.dv8tion.jda.api.interactions.commands.build.Commands.slash("top", "Muestra el top de memes")
                .addOptions(new OptionData(OptionType.STRING, "periodo", "Periodo de tiempo", true)
                        .addChoice("Semanal", "weekly")
                        .addChoice("Todo el tiempo", "alltime"));

        CommandData resetCooldownCommand = net.dv8tion.jda.api.interactions.commands.build.Commands.slash("cd", "Resetea el cooldown para enviar un meme de un usuario")
                .addOption(OptionType.USER, "usuario", "El usuario a resetear", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS));

        jda.updateCommands().addCommands(leaderboardCommand, resetCooldownCommand).queue();
    }



}
