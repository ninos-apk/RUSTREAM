// Local synthetic provider for UiPreviewTest; never packaged in the app.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

class UiDemoServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] video = Files.readAllBytes(Path.of(args[1]));
        byte[] playlist = playlist("http://127.0.0.1:" + port).getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // The player opens further connections while the delayed playlist request may still be running.
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try (exchange) {
                boolean list = exchange.getRequestURI().getPath().equals("/playlist.m3u");
                // A slow provider keeps the import progress on screen for the test's screenshot.
                if (list) Thread.sleep(3500);
                byte[] data = list ? playlist : video;
                exchange.getResponseHeaders().set("Content-Type", list ? "audio/x-mpegurl" : "video/mp4");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            } catch (Exception ignored) { /* Player may cancel a request. */ }
        });
        server.start();
        System.out.println("Local UI demo server ready on " + port);
    }

    /** A long live list plus one film and one episode; every stream URL serves the demo video. */
    private static String playlist(String base) {
        var m3u = new StringBuilder("#EXTM3U\n");
        for (int i = 1; i <= 140; i++) {
            m3u.append(String.format("#EXTINF:-1 group-title=\"Nachrichten und Dokumentationen\",Demo Sender %03d\n%s/live.ts\n", i, base));
        }
        m3u.append("#EXTINF:20 group-title=\"Spielfilme\",Demo Film fuer die neue TV-Oberflaeche\n").append(base).append("/movie/demo/1.mp4\n");
        m3u.append("#EXTINF:20 group-title=\"Serien\",Demo Serie S01E01\n").append(base).append("/series/demo/1.mp4\n");
        return m3u.toString();
    }
}
