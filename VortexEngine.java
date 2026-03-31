import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;
import java.util.concurrent.Executors;

public class VortexEngine {

    private static Process serverProcess = null;
    private static StringBuilder logs = new StringBuilder();
    private static BufferedWriter processInput = null;
    private static final String JAR_NAME = "server.jar";

    public static void main(String[] args) throws Exception {
        checkEula();
        HttpServer server = HttpServer.create(new InetSocketAddress(5000), 0);
        server.createContext("/vortex/", new VortexHandler());
        server.setExecutor(Executors.newFixedThreadPool(10));
        
        System.out.println("==========================================");
        System.out.println("=== VORTEX ENGINE: STANDALONE MODE    ===");
        System.out.println("=== API Port: 5000                     ===");
        System.out.println("=== Status: Ready for GitHub Requests  ===");
        System.out.println("==========================================");
        server.start();
    }

    static class VortexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // РАЗРЕШАЕМ ДОСТУП ДЛЯ GITHUB PAGES
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            String body = "";
            try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                body = s.hasNext() ? s.next() : "";
            }

            String path = exchange.getRequestURI().getPath();
            String response = "{\"status\": \"error\", \"message\": \"Unknown command\"}";

            try {
                if (path.contains("ping")) {
                    response = "{\"status\": \"online\"}";
                } 
                else if (path.contains("server_control")) {
                    if (body.contains("start")) {
                        if (serverProcess != null && serverProcess.isAlive()) {
                            response = "{\"status\": \"error\", \"message\": \"Running\"}";
                        } else {
                            startMinecraftServer();
                            response = "{\"status\": \"success\"}";
                        }
                    } else if (body.contains("stop")) {
                        stopMinecraftServer();
                        response = "{\"status\": \"success\"}";
                    }
                }
                else if (path.contains("get_logs")) {
                    response = "{\"status\": \"success\", \"logs\": \"" + 
                               logs.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
                }
                else if (path.contains("execute_cmd")) {
                    String cmd = body.split("\"command\":\"")[1].split("\"")[0];
                    sendCommand(cmd);
                    response = "{\"status\": \"success\"}";
                }
                else if (path.contains("download_jar")) {
                    String downloadUrl = body.split("\"url\":\"")[1].split("\"")[0];
                    downloadFile(downloadUrl, JAR_NAME);
                    response = "{\"status\": \"success\"}";
                }
            } catch (Exception e) {
                response = "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
            }

            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }

    private static void startMinecraftServer() {
        new Thread(() -> {
            try {
                logs.setLength(0);
                ProcessBuilder pb = new ProcessBuilder("java", "-Xmx1024M", "-Xms1024M", "-jar", JAR_NAME, "nogui");
                pb.redirectErrorStream(true);
                serverProcess = pb.start();
                processInput = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
                BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.append(line).append("\n");
                    if (logs.length() > 30000) logs.delete(0, 5000);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private static void stopMinecraftServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            sendCommand("stop");
        }
    }

    private static void sendCommand(String cmd) {
        if (processInput != null && serverProcess != null && serverProcess.isAlive()) {
            try {
                processInput.write(cmd + "\n");
                processInput.flush();
            } catch (IOException e) {}
        }
    }

    private static void downloadFile(String fileUrl, String destination) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        try (InputStream in = httpConn.getInputStream()) {
            Files.copy(in, new File(destination).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkEula() {
        try {
            File eulaFile = new File("eula.txt");
            if (!eulaFile.exists()) {
                PrintWriter writer = new PrintWriter(eulaFile);
                writer.println("eula=true");
                writer.close();
            }
        } catch (Exception e) {}
    }
}
