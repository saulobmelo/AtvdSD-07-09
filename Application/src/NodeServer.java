// NodeServer.java

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.*;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class NodeServer {
    private static final Gson gson = new Gson();
    private final NodeStore store = new NodeStore();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> peers = new ArrayList<>();
    private final Map<String, String> users = new ConcurrentHashMap<>();
    private volatile boolean acceptReplication = true;
    private final String nodeId;
    private final int port;

    public NodeServer(String nodeId, int port, List<String> peers) {
        this.nodeId = nodeId;
        this.port = port;
        if (peers != null) this.peers.addAll(peers);
        // três usuários de exemplo (username:password)
        users.put("alice", "password1");
        users.put("bob", "password2");
        users.put("carol", "password3");
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/messages", this::handleMessages);
        server.createContext("/replicate", this::handleReplicate);
        server.createContext("/simulate", this::handleSimulate);
        server.createContext("/status", this::handleStatus);
        server.setExecutor(executor);
        server.start();
        log("Node %s started on port %d. Peers=%s", nodeId, port, peers);
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            // Mensagens públicas podem ser lidas sem autenticação (requisito 3)
            List<Message> list = store.listMessages();
            sendJson(exchange, 200, list);
            return;
        } else if ("POST".equalsIgnoreCase(method)) {
            // Postar mensagem: requer Basic Auth (requisito 3)
            if (!checkBasicAuth(exchange)) {
                sendPlain(exchange, 401, "Unauthorized: use Basic auth");
                return;
            }
            String body = readBody(exchange);
            Message incoming = gson.fromJson(body, Message.class);
            // se o cliente não enviou id/timestamp (normal), criamos localmente
            Message toStore = new Message(incoming.getAuthor(), incoming.getContent(), nodeId);
            store.addMessage(toStore);
            sendJson(exchange, 201, toStore);
            log("Stored message %s locally (author=%s). Now replicating to peers...", toStore.getId(), toStore.getAuthor());

            // replicação assíncrona (requisito 2)
            executor.submit(() -> replicateToPeers(toStore));
            return;
        }
        sendPlain(exchange, 405, "Method Not Allowed");
    }

    private void handleReplicate(HttpExchange exchange) throws IOException {
        // endpoint entre nós; quando node está offline para receber, ignoramos (simula falha)
        if (!acceptReplication) {
            sendPlain(exchange, 503, "Node temporarily not accepting replication (simulated offline)");
            log("Replication request rejected (offline).");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }
        String body = readBody(exchange);
        Message m = gson.fromJson(body, Message.class);
        if (!store.contains(m.getId())) {
            store.addMessage(m);
            log("Received replicated message %s from peer (origin=%s).", m.getId(), m.getOriginNodeId());
        } else {
            log("Received replicated message %s but already present; ignoring.", m.getId());
        }
        sendPlain(exchange, 200, "OK");
    }

    private void handleSimulate(HttpExchange exchange) throws IOException {
        // /simulate/offline  POST {"offline": true}  -> toggles acceptReplication
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath(); // /simulate or /simulate/...
        String body = readBody(exchange);
        Map<String, Object> map = gson.fromJson(body, Map.class);
        Boolean offline = (Boolean) map.getOrDefault("offline", Boolean.FALSE);
        acceptReplication = !offline;
        sendPlain(exchange, 200, "OK");
        log("Simulate: set offline=%s", offline);

        // se voltamos a aceitar replicação, iniciamos reconciliação automática (requisito 4)
        if (!offline) {
            executor.submit(this::reconcileFromPeers);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> st = new HashMap<>();
        st.put("nodeId", nodeId);
        st.put("port", port);
        st.put("peers", peers);
        st.put("messages", store.listMessages().size());
        st.put("acceptReplication", acceptReplication);
        sendJson(exchange, 200, st);
    }

    private boolean checkBasicAuth(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) return false;
        String auth = authHeaders.get(0);
        if (!auth.startsWith("Basic ")) return false;
        try {
            String b64 = auth.substring(6);
            String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) return false;
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            String expected = users.get(user);
            return expected != null && expected.equals(pass);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void replicateToPeers(Message m) {
        for (String peer : peers) {
            try {
                URL url = new URL(peer + "/replicate");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(3000);
                con.setReadTimeout(3000);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    byte[] data = gson.toJson(m).getBytes(StandardCharsets.UTF_8);
                    os.write(data);
                }
                int code = con.getResponseCode();
                if (code == 200) {
                    log("Replicated message %s to %s OK.", m.getId(), peer);
                } else {
                    log("Replicate to %s returned code %d (will rely on reconciliation later).", peer, code);
                }
            } catch (Exception ex) {
                log("Failed to replicate to %s: %s (will rely on reconciliation later)", peer, ex.getMessage());
            }
        }
    }

    private void reconcileFromPeers() {
        log("Starting reconciliation with peers...");
        Set<String> localIds = store.allIds();
        for (String peer : peers) {
            try {
                URL url = new URL(peer + "/messages");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(3000);
                con.setReadTimeout(3000);
                int code = con.getResponseCode();
                if (code != 200) {
                    log("Failed to fetch messages from %s, code=%d", peer, code);
                    continue;
                }
                try (InputStream is = con.getInputStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    Type listType = new TypeToken<List<Message>>() {
                    }.getType();
                    List<Message> remote = gson.fromJson(json, listType);
                    int added = 0;
                    for (Message m : remote) {
                        if (!localIds.contains(m.getId())) {
                            store.addMessage(m);
                            added++;
                        }
                    }
                    log("Reconciliation with %s: fetched %d messages, added %d missing.", peer, remote.size(), added);
                }
            } catch (Exception ex) {
                log("Error reconciling with %s: %s", peer, ex.getMessage());
            }
        }
        log("Reconciliation finished. Local count=%d", store.listMessages().size());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int code, Object o) throws IOException {
        String body = gson.toJson(o);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendPlain(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void log(String fmt, Object... args) {
        String msg = String.format(fmt, args);
        System.out.printf("[%s] %s%n", nodeId, msg);
    }

    // main: args example:
    // --port=8000 --id=node1 --peers=http://localhost:8001,http://localhost:8002
    public static void main(String[] args) throws Exception {
        int port = 8000;
        String nodeId = "node1";
        List<String> peers = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7));
            else if (a.startsWith("--id=")) nodeId = a.substring(5);
            else if (a.startsWith("--peers=")) {
                String[] arr = a.substring(8).split(",");
                for (String p : arr) if (!p.isBlank()) peers.add(p.trim());
            }
        }
        NodeServer server = new NodeServer(nodeId, port, peers);
        server.start();
    }
}