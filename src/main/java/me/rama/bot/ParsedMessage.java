package me.rama.bot;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analiza y descompone un mensaje de Discord en sus componentes relevantes para memes.
 * <p>
 * Extrae:
 * <ul>
 *   <li>La primera URL encontrada en el texto (si existe).</li>
 *   <li>El texto del mensaje sin la URL (como descripción del meme).</li>
 *   <li>Los archivos adjuntos (para manejar imágenes).</li>
 * </ul>
 * También permite descargar imágenes adjuntas de forma asíncrona para
 * re-publicarlas en el embed del bot.
 */
public class ParsedMessage {

    /** Patrón para detectar URLs (http, https, o www.). */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://|www\\.)\\S+",
            Pattern.CASE_INSENSITIVE
    );

    private final User author;
    private final String url;
    private final String text;
    private final List<Message.Attachment> attachments;

    /** Datos binarios de la imagen descargada (null hasta que se capture). */
    private byte[] imageData;

    /** Nombre del archivo de imagen original. */
    private String imageFileName;

    /**
     * Analiza el mensaje extrayendo URL (si existe), texto limpio y adjuntos.
     * <p>
     * Si no hay texto descriptivo después de extraer la URL, se asigna un mensaje
     * por defecto indicando que el meme no tiene descripción.
     */
    public ParsedMessage(Message message) {
        attachments = List.copyOf(message.getAttachments());
        String foundUrl = null;
        String parsedText = message.getContentDisplay();
        this.author = message.getAuthor();

        if (!message.getContentRaw().isBlank()) {
            Matcher matcher = URL_PATTERN.matcher(message.getContentDisplay());

            if (matcher.find()) {
                foundUrl = matcher.group();
                // Eliminar la URL del texto, quedando solo la descripción
                parsedText = message.getContentRaw().replace(foundUrl, "").trim();
            }
        }

        this.url = foundUrl;
        this.text = (parsedText.isBlank())
                ? message.getAuthor().getEffectiveName() + " envió un meme sin descripción <:pepow:1280353071267971174>"
                : parsedText;
    }

    public String getUrl() {
        return url;
    }

    public String getText() {
        return text;
    }

    /** ¿El mensaje contiene una URL? */
    public boolean hasUrl() {
        return url != null;
    }

    /** ¿El mensaje tiene al menos una imagen adjunta? */
    public boolean hasAttachedImage() {
        if (attachments.isEmpty()) {
            return false;
        }
        return attachments.getFirst().isImage();
    }

    /** Obtiene el primer archivo adjunto del mensaje. */
    public Message.Attachment getFirstAttachment() {
        return attachments.getFirst();
    }

    public User getAuthor() {
        return author;
    }

    /**
     * Descarga la imagen adjunta de forma asíncrona.
     * <p>
     * Los datos se almacenan en {@link #imageData} y el nombre en {@link #imageFileName}
     * para su uso posterior con {@link #toFileUpload()}.
     *
     * @return CompletableFuture que se completa cuando la descarga termina.
     */
    public CompletableFuture<ParsedMessage> captureImage() {
        if (!hasAttachedImage()) {
            return CompletableFuture.completedFuture(this);
        }

        Message.Attachment attachment = getFirstAttachment();

        return attachment.getProxy().download().thenApply(is -> {
            try (InputStream stream = is) {
                this.imageData = stream.readAllBytes();
                this.imageFileName = attachment.getFileName();
            } catch (IOException e) {
                throw new RuntimeException("Error descargando imagen de " + attachment.getFileName(), e);
            }
            return this;
        });
    }

    /** ¿Ya se descargó la imagen? */
    public boolean isImageCaptured() {
        return imageData != null;
    }

    /**
     * Convierte la imagen ya capturada en un {@link FileUpload} listo para
     * adjuntar a un embed nuevo (referenciable como {@code attachment://nombreDelArchivo}).
     *
     * @throws IllegalStateException si la imagen no se ha capturado aún.
     */
    public FileUpload toFileUpload() {
        if (!isImageCaptured()) {
            throw new IllegalStateException("Image not captured yet.");
        }
        return FileUpload.fromData(imageData, imageFileName);
    }

    public String getImageFileName() {
        return imageFileName;
    }
}
