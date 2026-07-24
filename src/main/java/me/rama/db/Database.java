package me.rama.db;

import me.rama.Meme;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Database {

    private final Connection connection;

    public Database(String path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);


        try(Statement statement = connection.createStatement()){

            statement.execute("PRAGMA foreign_keys = ON;");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS bot_state(
                    key TEXT PRIMARY KEY,
                    value TEXT
                    )
                    """);

            statement.execute("""
            INSERT OR IGNORE INTO bot_state(key, value)
            VALUES ('lastProcessedId', '0')
        """);

            statement.execute("""
            CREATE TABLE IF NOT EXISTS memes (
                message_id       INTEGER PRIMARY KEY,
                author_id        INTEGER NOT NULL,
                upvotes          INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """);


            statement.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_memes_embed
            ON memes(message_id)
        """);

            statement.execute("""
            CREATE TABLE IF NOT EXISTS votes (
                message_id INTEGER NOT NULL,
                user_id    INTEGER NOT NULL,

                PRIMARY KEY (message_id, user_id),

                FOREIGN KEY (message_id)
                    REFERENCES memes(message_id)
                    ON DELETE CASCADE
            )
        """);
        }

    }

    public synchronized Long getLastProcessedId() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM bot_state WHERE key = ?")) {

            ps.setString(1, "lastProcessedId");

            ResultSet rs = ps.executeQuery();

            return Long.parseLong(rs.getString("value"));
        }
    }

    public synchronized void setLastProcessedId(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE bot_state SET value = ? WHERE key = ?")) {

            ps.setString(1, id);
            ps.setString(2, "lastProcessedId");

            ps.executeUpdate();
        }
    }

    public Meme getMeme(long messageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT message_id, author_id, upvotes FROM memes WHERE message_id = ?")) {

            ps.setLong(1, messageId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }


                return new Meme(
                        rs.getString("message_id"),
                        rs.getString("author_id"),
                        rs.getInt("upvotes")
                );
            }
        }
    }

    public void saveMeme(Meme meme) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memes (message_id, author_id, upvotes, created_at) VALUES (?, ?, ?, ?)")) {

            ps.setLong(1, Long.parseLong(meme.getMessageId()));
            ps.setLong(2, Long.parseLong(meme.getAuthorId()));
            ps.setInt(3, meme.getVotes());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public boolean addVoteAndIncrement(long messageId, long userId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO votes (message_id, user_id) VALUES (?, ?)")) {
                ps.setLong(1, messageId);
                ps.setLong(2, userId);
                int inserted = ps.executeUpdate();

                if (inserted == 0) {
                    connection.rollback();
                    return false;
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE memes SET upvotes = upvotes + 1 WHERE message_id = ?")) {
                ps.setLong(1, messageId);
                ps.executeUpdate();
            }

            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public boolean removeVoteAndDecrement(long messageId, long userId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM votes WHERE message_id = ? AND user_id = ?")) {
                ps.setLong(1, messageId);
                ps.setLong(2, userId);
                int deleted = ps.executeUpdate();

                if (deleted == 0) {
                    connection.rollback();
                    return false;
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE memes SET upvotes = upvotes - 1 WHERE message_id = ? AND upvotes > 0")) {
                ps.setLong(1, messageId);
                ps.executeUpdate();
            }

            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public boolean hasVoted(long messageId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM votes WHERE message_id = ? AND user_id = ?")) {

            ps.setLong(1, messageId);
            ps.setLong(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record LeaderboardEntry(long messageId, long authorId, int upvotes, long createdAt) {}

    public List<LeaderboardEntry> getLeaderboard(Duration period, int limit) throws SQLException {
        String sql = "SELECT message_id, author_id, upvotes, created_at FROM memes";
        boolean filterByTime = period != null;

        if (filterByTime) {
            sql += " WHERE created_at >= ?";
        }
        sql += " ORDER BY upvotes DESC LIMIT ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int paramIndex = 1;

            if (filterByTime) {
                long cutoff = System.currentTimeMillis() - period.toMillis();
                ps.setLong(paramIndex++, cutoff);
            }
            ps.setInt(paramIndex, limit);

            List<LeaderboardEntry> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new LeaderboardEntry(
                            rs.getLong("message_id"),
                            rs.getLong("author_id"),
                            rs.getInt("upvotes"),
                            rs.getLong("created_at")
                    ));
                }
            }
            return results;
        }
    }

    
    public Long getLastMemeTimestamp(long authorId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT created_at FROM memes WHERE author_id = ? ORDER BY created_at DESC LIMIT 1")) {

            ps.setLong(1, authorId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("created_at");
                }
                return null;
            }
        }
    }


}
