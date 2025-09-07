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
    private final Map<String,String> users = new ConcurrentHashMap<>();
    private final String nodeId;
    private final int port;
    private volatile boolean acceptReplication = true;

    public NodeServer(String nodeId, int port, List<String> peers) {
        this.nodeId = nodeId;
        this.port = port;
        if (peers != null) this.peers.addAll(peers);
        // usuários fixos
        users.put("alice", "password1");
        users.put("bob", "password2");
        users.put("carol", "password3");
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/messages", this::handleMessages);
        server.createContext("/replicate", this::handleReplicate);
        server.setExecutor(executor);
        server.start();

        log("Servidor iniciado na porta %d", port);
        log("Comandos disponíveis: listar | offline | online | sair | <usuário>");

        // inicia thread de input do usuário
        executor.submit(this::consoleInputLoop);
    }

    // === LOOP DE INPUT NO TERMINAL ===
    private void consoleInputLoop() {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.println("\n[" + nodeId + "] Digite um comando ou usuário:");
            String user = sc.nextLine().trim();

            if (user.equalsIgnoreCase("sair")) {
                System.out.println("[" + nodeId + "] Encerrando input do console.");
                break;
            }

            if (user.equalsIgnoreCase("listar")) {
                listarMensagens();
                continue;
            }

            if (user.equalsIgnoreCase("offline")) {
                acceptReplication = false;
                System.out.println("[" + nodeId + "] Agora estou OFFLINE (não recebo replicações).");
                continue;
            }

            if (user.equalsIgnoreCase("online")) {
                acceptReplication = true;
                System.out.println("[" + nodeId + "] Agora estou ONLINE (voltando a receber replicações).");
                executor.submit(this::reconcileFromPeers);
                continue;
            }

            // fluxo normal de envio de mensagem
            System.out.print("[" + nodeId + "] Senha: ");
            String pass = sc.nextLine().trim();

            // autentica
            if (!authenticate(user, pass)) {
                System.out.println("[" + nodeId + "] Erro: usuário ou senha inválidos!");
                continue;
            }

            System.out.print("[" + nodeId + "] Digite sua mensagem: ");
            String content = sc.nextLine().trim();

            // cria mensagem
            Message msg = new Message(user, content, nodeId);
            store.addMessage(msg);
            System.out.println("[" + nodeId + "] Mensagem enviada com sucesso! ID=" + msg.getId());

            // replica em background
            executor.submit(() -> replicateToPeers(msg));
        }
    }

    private void listarMensagens() {
        List<Message> mensagens = store.listMessages();
        if (mensagens.isEmpty()) {
            System.out.println("[" + nodeId + "] Nenhuma mensagem armazenada.");
            return;
        }
        System.out.println("[" + nodeId + "] Mural de mensagens:");
        for (Message m : mensagens) {
            System.out.printf("  - %s (origin: %s) [%s]: %s%n",
                    m.getAuthor(),
                    m.getOriginNodeId(),
                    new Date(m.getTimestamp()).toString(),
                    m.getContent());
        }
    }

    private boolean authenticate(String user, String pass) {
        String expected = users.get(user);
        return expected != null && expected.equals(pass);
    }

    // ==== HANDLERS REST (replicação entre nós) ====
    private void handleMessages(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, store.listMessages());
            return;
        }
        sendPlain(exchange, 405, "Method Not Allowed");
    }

    private void handleReplicate(HttpExchange exchange) throws IOException {
        if (!acceptReplication) {
            sendPlain(exchange, 503, "Node offline");
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
            log("Recebeu replicação de %s: %s", m.getOriginNodeId(), m.getContent());
        }
        sendPlain(exchange, 200, "OK");
    }

    // ==== REPLICAÇÃO E RECONCILIAÇÃO ====
    private void replicateToPeers(Message m) {
        for (String peer : peers) {
            try {
                URL url = new URL(peer + "/replicate");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(gson.toJson(m).getBytes(StandardCharsets.UTF_8));
                }
                int code = con.getResponseCode();
                if (code == 200) {
                    log("Replicou msg %s para %s", m.getId(), peer);
                } else {
                    log("Falha replicando para %s (código %d)", peer, code);
                }
            } catch (Exception e) {
                log("Erro replicando para %s: %s", peer, e.getMessage());
            }
        }
    }

    private void reconcileFromPeers() {
        log("Iniciando reconciliação...");
        Set<String> localIds = store.allIds();
        for (String peer : peers) {
            try {
                URL url = new URL(peer + "/messages");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                if (con.getResponseCode() == 200) {
                    String json = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    Type listType = new TypeToken<List<Message>>(){}.getType();
                    List<Message> remote = gson.fromJson(json, listType);
                    int added = 0;
                    for (Message m : remote) {
                        if (!localIds.contains(m.getId())) {
                            store.addMessage(m);
                            added++;
                        }
                    }
                    log("Reconciliação com %s: adicionou %d mensagens", peer, added);
                }
            } catch (Exception e) {
                log("Erro reconciliando com %s: %s", peer, e.getMessage());
            }
        }
    }

    // ==== HELPERS ====
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
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
    private static void sendPlain(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
    private void log(String fmt, Object... args) {
        System.out.printf("[%s] %s%n", nodeId, String.format(fmt, args));
    }

    // ==== MAIN ====
    public static void main(String[] args) throws Exception {
        int port = 8000;
        String nodeId = "node1";
        List<String> peers = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7));
            else if (a.startsWith("--id=")) nodeId = a.substring(5);
            else if (a.startsWith("--peers=")) {
                for (String p : a.substring(8).split(",")) if (!p.isBlank()) peers.add(p.trim());
            }
        }
        NodeServer server = new NodeServer(nodeId, port, peers);
        server.start();
    }
}