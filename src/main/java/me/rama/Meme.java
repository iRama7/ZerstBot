package me.rama;

import me.rama.ex.InvalidVoteException;

import java.sql.SQLException;

import static me.rama.Main.db;

public class Meme {

    private final String message_id;
    private final String author_id;
    private int votes;

    public Meme(String message_id, String author_id, int votes){
        this.message_id = message_id;
        this.author_id = author_id;
        this.votes = votes;
    }

    public void vote(Long user_id) throws SQLException {
        db.addVoteAndIncrement(Long.parseLong(message_id), user_id);
        votes++;
    }

    public void unVote(Long user_id) throws InvalidVoteException, SQLException {
        if(votes == 0){
            throw new InvalidVoteException("Could not remove a vote from a meme with 0 votes.");
        }
        db.removeVoteAndDecrement(Long.parseLong(message_id), user_id);
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
