// NodeStore.java

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NodeStore {
    private final Map<String, Message> messagesById = new ConcurrentHashMap<>();
    // para manter ordenação por timestamp:
    private final List<Message> ordered = Collections.synchronizedList(new ArrayList<>());

    public boolean contains(String id) {
        return messagesById.containsKey(id);
    }

    public void addMessage(Message m) {
        if (messagesById.putIfAbsent(m.getId(), m) == null) {
            ordered.add(m);
            ordered.sort(Comparator.comparingLong(Message::getTimestamp));
        }
    }

    public List<Message> listMessages() {
        synchronized (ordered) {
            return new ArrayList<>(ordered);
        }
    }

    public Set<String> allIds() {
        return new HashSet<>(messagesById.keySet());
    }
}