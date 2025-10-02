import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class SimpleBlogServer {
    static List<Map<String, String>> posts = Collections.synchronizedList(new ArrayList<>());
    static final Path POSTS_FILE = Paths.get("posts.json");

    public static void main(String[] args) throws IOException {
        loadPostsFromFile();

        // Get port from environment variable or use 8080 by default
        int port;
        String portEnv = System.getenv("PORT");
        if (portEnv != null) {
            port = Integer.parseInt(portEnv);
        } else {
            port = 8080;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", SimpleBlogServer::handleRoot);
        server.createContext("/addPost", SimpleBlogServer::handleAddPost);
        server.createContext("/posts", SimpleBlogServer::handleGetPosts);

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("Server running at http://localhost:" + port);
    }

    static void handleRoot(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            Path filePath = Paths.get("index.html");
            byte[] content = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    static void handleAddPost(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "utf-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            Map<String, String> params = parseFormData(sb.toString());
            params.put("id", UUID.randomUUID().toString());

            // Add at the start (newest first)
            posts.add(0, params);

            // Keep only latest 50
            synchronized (posts) {
                while (posts.size() > 200) posts.remove(posts.size() - 1);
            }

            savePostsToFile();

            String response = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static void handleGetPosts(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            List<Map<String, String>> sortedPosts;
            synchronized(posts) {
                // Return oldest-first
                sortedPosts = new ArrayList<>(posts);
                Collections.reverse(sortedPosts);
            }

            String response = postsToJson(sortedPosts);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static Map<String, String> parseFormData(String formData) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] keyVal = pair.split("=");
            String key = java.net.URLDecoder.decode(keyVal[0], "UTF-8");
            String val = keyVal.length > 1 ? java.net.URLDecoder.decode(keyVal[1], "UTF-8") : "";
            map.put(key, val);
        }
        return map;
    }

    static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String postsToJson(List<Map<String, String>> postsList) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < postsList.size(); i++) {
            Map<String, String> post = postsList.get(i);
            sb.append("{");
            sb.append("\"id\":\"").append(escapeJson(post.get("id"))).append("\",");
            sb.append("\"title\":\"").append(escapeJson(post.get("title"))).append("\",");
            sb.append("\"content\":\"").append(escapeJson(post.get("content"))).append("\"");
            sb.append("}");
            if (i < postsList.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    static void savePostsToFile() {
        try {
            String json = postsToJson(posts);
            Files.write(POSTS_FILE, json.getBytes());
        } catch (IOException e) {
            System.err.println("Error saving posts: " + e.getMessage());
        }
    }

    static void loadPostsFromFile() {
        if (!Files.exists(POSTS_FILE)) return;
        try {
            String content = new String(Files.readAllBytes(POSTS_FILE));
            if (content.trim().isEmpty()) return;

            List<Map<String, String>> loadedPosts = new ArrayList<>();
            String trimmedContent = content.trim();
            if (trimmedContent.length() < 2) return;

            String arrayContent = trimmedContent.substring(1, trimmedContent.length() - 1);
            String[] items = arrayContent.split("\\},\\{");

            for (int i = 0; i < items.length; i++) {
                String item = items[i];
                if (i == 0) item = item.substring(1);
                if (i == items.length - 1) item = item.substring(0, item.length() - 1);

                Map<String, String> post = new HashMap<>();
                for (String pair : item.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length < 2) continue;
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "")
                            .replace("\\n", "\n")
                            .replace("\\r", "\r")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                    post.put(key, value);
                }
                loadedPosts.add(post);
            }
            posts.clear();
            posts.addAll(loadedPosts);
        } catch (Exception e) {
            System.err.println("Error loading posts: " + e.getMessage());
        }
    }
}
