package me.rama;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;


public class Main {

    private static JDA jda;

    public static void main(String[] args) {

        log("Starting ZerstBot", true);
        String token = System.getenv("ZERSTBOT_TOKEN");
        if(token == null){
            log("Token is null, check permissions or define the env variable.", true);
        }

        jda = JDABuilder.createDefault(token).build();

        /*
         Sistema de votaciones (reacciones)
         Cooldown de mensajes

         Base de datos
         */
    }

    public static void log(String message, boolean logToFile) {
        System.out.println(message);
    }
}