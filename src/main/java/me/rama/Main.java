package me.rama;

import com.google.gson.Gson;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.EnumSet;


public class Main {

    private static JDA jda;
    private Config config;

    public static void main(String[] args) {

        log("Starting ZerstBot...", true);

        loadConfig();
        String token = tryInit();

        if(token != null){
            jda = JDABuilder.createDefault(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .addEventListeners(new MessageListener())
                    .build();
        }else{
            log("[Error] Token is null", true);
        }

        /*
         Sistema de votaciones (reacciones)
         Cooldown de mensajes

         Base de datos
         */
    }

    public static String tryInit(){
        String token = null;
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(getJarDirectory())
                    .load();
            token = dotenv.get("ZERSTBOT_TOKEN");
        }catch (DotenvException e) {
            log("[Error] Dotenv loading failed: " + e.getMessage(), true);
        }
        return token;
    }

    public static void log(String message, boolean logToFile) {
        System.out.println(message);
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

    public void loadConfig() {
        Gson gson = new Gson();

        try (FileReader reader = new FileReader("config.json")) {
            config = gson.fromJson(reader, Config.class);
        }catch (IOException e) {
            log("[Error] Error loading config file: " + e.getMessage(), true);
        }
    }

}