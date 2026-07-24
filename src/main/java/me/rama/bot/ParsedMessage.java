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

public class ParsedMessage {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://|www\\.)\\S+",
            Pattern.CASE_INSENSITIVE
    );

    private final User author;
    private final String url;
    private final String text;
    private final List<Message.Attachment> attachments;

    private byte[] imageData;
    private String imageFileName;

    public ParsedMessage(Message message) {
        attachments = List.copyOf(message.getAttachments());
        String foundUrl = null;
        String parsedText = message.getContentDisplay();
        this.author = message.getAuthor();

        if (!message.getContentRaw().isBlank()) {
            Matcher matcher = URL_PATTERN.matcher(message.getContentDisplay());

            if (matcher.find()) {
                foundUrl = matcher.group();
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

    public boolean hasUrl() {
        return url != null;
    }

    public boolean hasAttachedImage(){
        if(attachments.isEmpty()){
            return false;
        }

        return attachments.getFirst().isImage();
    }

    public Message.Attachment getFirstAttachment(){
        return attachments.getFirst();
    }

    public User getAuthor() {
        return author;
    }

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

    public boolean isImageCaptured() {
        return imageData != null;
    }

    /**
     * Convierte la imagen ya capturada en un FileUpload listo para
     * adjuntar a un embed nuevo (attachment://nombreDelArchivo).
     *
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
