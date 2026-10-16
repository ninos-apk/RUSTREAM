// Local-only benchmark provider. Does not inspect, log or open playlist stream URLs.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class PlaylistBenchmarkServer {
    public static void main(String[] args) throws Exception {
        byte[] playlist = Files.readAllBytes(Path.of(args[1]));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                int code = path.startsWith("/http/") ? Integer.parseInt(path.substring(6)) : 200;
                byte[] body = path.equals("/playlist.m3u") ? playlist :
                    "<html>\n<body>Login required</body>\n</html>\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", path.equals("/playlist.m3u") ? "audio/x-mpegurl" : "text/html");
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) { /* Client cancellation; no request URLs in logs. */ }
        });
        server.start();
        System.out.println("Local playlist benchmark server ready");
    }
}
