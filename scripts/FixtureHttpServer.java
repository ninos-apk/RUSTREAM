// Local synthetic provider for release smoke testing; never packaged in the app.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class FixtureHttpServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] video = Files.readAllBytes(Path.of(args[1]));
        byte[] customPlaylist = args.length > 2 ? Files.readAllBytes(Path.of(args[2])) : null;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                boolean playlist = exchange.getRequestURI().getPath().equals("/playlist.m3u");
                byte[] data = playlist
                    ? (customPlaylist != null ? customPlaylist : ("#EXTM3U\n#EXTINF:20,Release Fixture\nhttp://127.0.0.1:" + port + "/fixture.mp4\n").getBytes(StandardCharsets.UTF_8))
                    : video;
                exchange.getResponseHeaders().set("Content-Type", playlist ? "audio/x-mpegurl" : "video/mp4");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            } catch (Exception ignored) { /* Player may cancel a request. */ }
        });
        server.start();
        System.out.println("Synthetic release fixture server ready on " + port);
    }
}

