// Message.java

import java.util.UUID;

public class Message {
    private String id;
    private String author;
    private String content;
    private long timestamp;
    private String originNodeId;

    public Message() {
    } // para Gson

    public Message(String author, String content, String originNodeId) {
        this.id = UUID.randomUUID().toString();
        this.author = author;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
        this.originNodeId = originNodeId;
    }

    // getters / setters
    public String getId() {
        return id;
    }

    public String getAuthor() {
        return author;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getOriginNodeId() {
        return originNodeId;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setOriginNodeId(String originNodeId) {
        this.originNodeId = originNodeId;
    }
}