package me.rama;

import me.rama.ex.InvalidVoteException;

import java.sql.SQLException;

import static me.rama.Main.db;

/**
 * Modelo de datos que representa un meme publicado en el canal.
 * <p>
 * El contador de votos local ( {@link #votes} ) se mantiene sincronizado con la BD:
 * primero se persiste en la base de datos y luego se actualiza en memoria.
 * En caso de reinicio del bot, el valor se recupera desde la BD.
 */
public class Meme {

    /** ID del mensaje de Discord donde se publicó el embed del meme (como String por comodidad con JDA). */
    private final String message_id;

    /** ID del autor del meme en Discord (como String). */
    private final String author_id;

    /** Contador local de votos, sincronizado con la BD. */
    private int votes;

    public Meme(String message_id, String author_id, int votes) {
        this.message_id = message_id;
        this.author_id = author_id;
        this.votes = votes;
    }

    /**
     * Incrementa el contador local de votos.
     * Nota: la persistencia en BD se maneja en {@code ButtonListener} antes de llamar a este método.
     */
    public void vote(Long user_id) throws SQLException {
        votes++;
    }

    /**
     * Decrementa el contador local de votos.
     *
     * @throws InvalidVoteException si el meme no tiene votos para quitar.
     */
    public void unVote(Long user_id) throws InvalidVoteException, SQLException {
        if (votes == 0) {
            throw new InvalidVoteException("Could not remove a vote from a meme with 0 votes.");
        }
        votes--;
    }

    public String getMessageId() {
        return message_id;
    }

    public String getAuthorId() {
        return author_id;
    }

    public int getVotes() {
        return votes;
    }

}
