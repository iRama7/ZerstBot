package me.rama.db;

import me.rama.Meme;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Capa de acceso a la base de datos SQLite.
 * <p>
 * Todos los métodos de consulta/escritura están sincronizados ({@code synchronized})
 * para evitar condiciones de carrera, ya que SQLite opera en modo serie por defecto
 * al usar una sola conexión.
 * <p>
 * La BD se inicializa automáticamente con las tablas e índices necesarios si no existen.
 */
public class Database {

    private final Connection connection;

    /**
     * Abre (o crea) la base de datos SQLite en la ruta indicada e inicializa el esquema.
     * <p>
     * Tablas creadas:
     * <ul>
     *   <li>{@code bot_state} — clave/valor para estado persistente del bot (ej: último ID procesado).</li>
     *   <li>{@code memes} — memes publicados con su autor, votos y timestamp.</li>
     *   <li>{@code votes} — registros de voto por usuario y meme (PK compuesta).</li>
     * </ul>
     * Índices:
     * <ul>
     *   <li>{@code idx_memes_author_created} — acelera búsqueda por autor + orden cronológico (cooldown).</li>
     *   <li>{@code idx_memes_upvotes_created} — acelera el leaderboard ordenado por votos.</li>
     *   <li>{@code idx_votes_user} — acelera consultas de votos por usuario.</li>
     * </ul>
     *
     * @param path Ruta al archivo .db (ej: "/ruta/database.db").
     * @throws SQLException Si no se puede conectar o crear las tablas.
     */
    public Database(String path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);

        try (Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA foreign_keys = ON;");

            // ── Tabla: estado persistente del bot ────────────────
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS bot_state(
                    key TEXT PRIMARY KEY,
                    value TEXT
                    )
                    """);

            // Valor por defecto: 0 significa "no hay mensajes procesados aún"
            statement.execute("""
                    INSERT OR IGNORE INTO bot_state(key, value)
                    VALUES ('lastProcessedId', '0')
                    """);

            // ── Tabla: memes ────────────────────────────────────
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS memes (
                        message_id       INTEGER PRIMARY KEY,
                        author_id        INTEGER NOT NULL,
                        upvotes          INTEGER NOT NULL DEFAULT 0,
                        created_at       INTEGER NOT NULL
                    )
                    """);

            // Índice compuesto para getLastMemeTimestamp():
            // busca por author_id y ya tiene created_at ordenado para el ORDER BY DESC LIMIT 1
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memes_author_created
                    ON memes(author_id, created_at)
                    """);

            // Índice compuesto para getLeaderboard():
            // upvotes DESC evita ordenar toda la tabla, created_at ayuda en el filtro semanal
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memes_upvotes_created
                    ON memes(upvotes DESC, created_at)
                    """);

            // ── Tabla: votos (relación muchos a muchos) ─────────
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

            // Índice para consultar votos por usuario (ej: futuras queries de "mis votos")
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_votes_user
                    ON votes(user_id)
                    """);
        }
    }

    /**
     * Obtiene el ID del último mensaje procesado por el bot.
     * Se usa al iniciar para recuperar mensajes que quedaron sin procesar.
     */
    public synchronized Long getLastProcessedId() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM bot_state WHERE key = ?")) {

            ps.setString(1, "lastProcessedId");

            ResultSet rs = ps.executeQuery();

            return Long.parseLong(rs.getString("value"));
        }
    }

    /**
     * Actualiza el ID del último mensaje procesado.
     *
     * @param id ID del mensaje de Discord.
     */
    public synchronized void setLastProcessedId(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE bot_state SET value = ? WHERE key = ?")) {

            ps.setString(1, id);
            ps.setString(2, "lastProcessedId");

            ps.executeUpdate();
        }
    }

    /**
     * Obtiene un meme desde la BD por su ID de mensaje.
     *
     * @param messageId ID del mensaje en Discord.
     * @return El meme, o null si no existe.
     */
    public synchronized Meme getMeme(long messageId) throws SQLException {
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

    /**
     * Guarda un nuevo meme en la base de datos.
     *
     * @param meme El meme a persistir (con message_id, author_id y votes inicializados).
     */
    public synchronized void saveMeme(Meme meme) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memes (message_id, author_id, upvotes, created_at) VALUES (?, ?, ?, ?)")) {

            ps.setLong(1, Long.parseLong(meme.getMessageId()));
            ps.setLong(2, Long.parseLong(meme.getAuthorId()));
            ps.setInt(3, meme.getVotes());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Registra un voto de un usuario en un meme e incrementa el contador.
     * <p>
     * La operación es atómica: INSERT en votes + UPDATE upvotes, con rollback si falla.
     * Usa {@code INSERT OR IGNORE} para que si el usuario ya votó, devuelva false sin errores.
     *
     * @param messageId ID del mensaje (meme).
     * @param userId    ID del usuario que vota.
     * @return true si se registró el voto, false si el usuario ya había votado.
     */
    public synchronized boolean addVoteAndIncrement(long messageId, long userId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO votes (message_id, user_id) VALUES (?, ?)")) {
                ps.setLong(1, messageId);
                ps.setLong(2, userId);
                int inserted = ps.executeUpdate();

                if (inserted == 0) {
                    connection.rollback();
                    return false; // El usuario ya había votado
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

    /**
     * Remueve el voto de un usuario de un meme y decrementa el contador.
     * <p>
     * Operación atómica similar a {@link #addVoteAndIncrement(long, long)}.
     *
     * @param messageId ID del mensaje (meme).
     * @param userId    ID del usuario que desvota.
     * @return true si se removió el voto, false si el usuario no había votado.
     */
    public synchronized boolean removeVoteAndDecrement(long messageId, long userId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM votes WHERE message_id = ? AND user_id = ?")) {
                ps.setLong(1, messageId);
                ps.setLong(2, userId);
                int deleted = ps.executeUpdate();

                if (deleted == 0) {
                    connection.rollback();
                    return false; // El usuario no había votado
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

    /**
     * Verifica si un usuario ya votó en un meme específico.
     *
     * @param messageId ID del mensaje (meme).
     * @param userId    ID del usuario.
     * @return true si el usuario ya votó, false en caso contrario.
     */
    public synchronized boolean hasVoted(long messageId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM votes WHERE message_id = ? AND user_id = ?")) {

            ps.setLong(1, messageId);
            ps.setLong(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Registro que representa una entrada del leaderboard. */
    public record LeaderboardEntry(long messageId, long authorId, int upvotes, long createdAt) {}

    /**
     * Obtiene el leaderboard de memes, ordenado por votos descendente.
     *
     * @param period Si no es null, filtra solo los memes creados dentro de este período (ej: 7 días para semanal).
     * @param limit  Cantidad máxima de resultados (ej: 10).
     * @return Lista de entradas del leaderboard.
     */
    public synchronized List<LeaderboardEntry> getLeaderboard(Duration period, int limit) throws SQLException {
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

    /**
     * Obtiene el timestamp de la última publicación de un usuario.
     * Se usa para verificar el cooldown entre memes.
     * <p>
     * Aprovecha el índice {@code idx_memes_author_created} para una búsqueda eficiente.
     *
     * @param authorId ID del autor en Discord.
     * @return Timestamp en milisegundos de su último meme, o null si nunca publicó.
     */
    public synchronized Long getLastMemeTimestamp(long authorId) throws SQLException {
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
