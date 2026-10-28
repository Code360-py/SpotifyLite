import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class SpotifyLiteWeb {

    private static final int PORT = 8080;
    private static final String MUSIC_DIR = System.getProperty("user.home") + "/storage/shared/Music";
    private static final Map<String, String> streamCache = new ConcurrentHashMap<>();

    static class Track {
        String id;
        String title;
        String duration;
        String artist;
        boolean isLocal;

        Track(String id, String title, String duration, String artist, boolean isLocal) {
            this.id = id;
            this.title = title;
            this.duration = duration;
            this.artist = artist;
            this.isLocal = isLocal;
        }

        String toJson() {
            return String.format("{\"id\":\"%s\",\"title\":\"%s\",\"duration\":\"%s\",\"artist\":\"%s\",\"isLocal\":%b}",
                    escape(id), escape(title), escape(duration), escape(artist), isLocal);
        }

        private String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
        }
    }

    public static void main(String[] args) throws IOException {
        new File(MUSIC_DIR).mkdirs();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", new StaticUiHandler());
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/local-downloads", new LocalDownloadsHandler());
        server.createContext("/api/download", new DownloadHandler());
        server.createContext("/stream", new StreamProxyHandler());
        server.createContext("/stream-file", new StreamLocalFileHandler());

        server.start();

        System.out.println("==================================================");
        System.out.println("     SpotifyLite Web (Pure Browser Audio Mode)    ");
        System.out.println("==================================================");
        System.out.println(" [✓] MPV removed -> direct HTML5 audio streaming  ");
        System.out.println(" Access URL: http://localhost:" + PORT);
        System.out.println("==================================================");
    }

    // --- STREAM PROXY (Pipes direct audio to HTML5 <audio> with seekable ranges) ---
    static class StreamProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String id = params.get("id");

            if (id == null || id.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Missing id\"}");
                return;
            }

            String targetUrl = streamCache.get(id);
            if (targetUrl == null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "yt-dlp", "-f", "bestaudio/ba", "-g", "https://www.youtube.com/watch?v=" + id
                    );
                    Process p = pb.start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    targetUrl = r.readLine();
                    p.waitFor();
                    if (targetUrl != null && !targetUrl.trim().isEmpty()) {
                        streamCache.put(id, targetUrl.trim());
                    }
                } catch (Exception ignored) {}
            }

            if (targetUrl == null || targetUrl.isEmpty()) {
                sendJsonResponse(exchange, 500, "{\"error\":\"Stream resolution failed\"}");
                return;
            }

            // Redirect browser directly to YouTube's raw audio HTTPS stream
            exchange.getResponseHeaders().set("Location", targetUrl);
            exchange.sendResponseHeaders(302, -1);
        }
    }

    // --- LOCAL FILE STREAMING ---
    static class StreamLocalFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String path = params.get("path");

            if (path == null) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Missing file path\"}");
                return;
            }

            File file = new File(path);
            if (!file.exists() || !file.canRead()) {
                sendJsonResponse(exchange, 404, "{\"error\":\"File not found\"}");
                return;
            }

            long fileLength = file.length();
            String range = exchange.getRequestHeaders().getFirst("Range");

            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-");
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLength - 1;
                long contentLength = end - start + 1;

                exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                exchange.sendResponseHeaders(206, contentLength);

                try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                     OutputStream os = exchange.getResponseBody()) {
                    raf.seek(start);
                    byte[] buffer = new byte[8192];
                    long bytesRemaining = contentLength;
                    while (bytesRemaining > 0) {
                        int read = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining));
                        if (read == -1) break;
                        os.write(buffer, 0, read);
                        bytesRemaining -= read;
                    }
                }
            } else {
                exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.sendResponseHeaders(200, fileLength);

                try (FileInputStream fis = new FileInputStream(file);
                     OutputStream os = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    // --- SEARCH ---
    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String query = params.getOrDefault("q", "").trim();
            int limit = 25;
            try {
                if (params.containsKey("limit")) limit = Integer.parseInt(params.get("limit"));
            } catch (Exception ignored) {}

            if (query.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Missing query\"}");
                return;
            }

            List<Track> results = new ArrayList<>();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "yt-dlp",
                        "ytsearch" + limit + ":" + query,
                        "--print", "%(id)s||%(title)s||%(duration_string)s||%(uploader)s",
                        "--no-warnings"
                );
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|\\|");
                    if (parts.length >= 4) {
                        results.add(new Track(parts[0], parts[1], parts[2], parts[3], false));
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"Search failed\"}");
                return;
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < results.size(); i++) {
                sb.append(results.get(i).toJson());
                if (i < results.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // --- LOCAL DOWNLOADS SCANNER ---
    static class LocalDownloadsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File dir = new File(MUSIC_DIR);
            File[] files = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".opus") || n.endsWith(".flac");
            });

            List<Track> locals = new ArrayList<>();
            if (files != null) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                for (File f : files) {
                    long sizeMb = f.length() / (1024 * 1024);
                    String displayName = f.getName().replaceFirst("[.][^.]+$", "");
                    locals.add(new Track(f.getAbsolutePath(), displayName, sizeMb + " MB", "Offline Storage", true));
                }
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < locals.size(); i++) {
                sb.append(locals.get(i).toJson());
                if (i < locals.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    // --- DOWNLOAD WORKER ---
    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String id = params.get("id");
            if (id != null) {
                new Thread(() -> {
                    try {
                        String outPath = MUSIC_DIR + "/%(title)s.%(ext)s";
                        new ProcessBuilder(
                                "yt-dlp", "-x", "--audio-format", "mp3", "--audio-quality", "0",
                                "-o", outPath, "https://www.youtube.com/watch?v=" + id
                        ).start().waitFor();
                    } catch (Exception ignored) {}
                }).start();
            }
            sendJsonResponse(exchange, 200, "{\"status\":\"download_started\"}");
        }
    }

    // --- STATIC WEB UI ---
    static class StaticUiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getHtmlContent();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void sendJsonResponse(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getHtmlContent() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\" data-bs-theme=\"dark\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "  <title>SpotifyLite</title>\n" +
                "  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css\">\n" +
                "  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css\">\n" +
                "  <style>\n" +
                "    body {\n" +
                "      background-color: #0b0b0b;\n" +
                "      color: #f1f1f1;\n" +
                "      padding-bottom: 180px;\n" +
                "      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "      -webkit-tap-highlight-color: transparent;\n" +
                "    }\n" +
                "    .navbar { background: rgba(11, 11, 11, 0.9); backdrop-filter: blur(16px); border-bottom: 1px solid #1e1e1e; }\n" +
                "    .search-input-pill {\n" +
                "      background: #181818;\n" +
                "      border: 1px solid #282828;\n" +
                "      border-radius: 50px;\n" +
                "      color: #fff;\n" +
                "      padding: 12px 20px;\n" +
                "      transition: 0.2s;\n" +
                "    }\n" +
                "    .search-input-pill:focus {\n" +
                "      background: #202020;\n" +
                "      border-color: #1db954;\n" +
                "      box-shadow: 0 0 0 3px rgba(29, 185, 84, 0.25);\n" +
                "      color: #fff;\n" +
                "    }\n" +
                "    .song-card {\n" +
                "      background: #141414;\n" +
                "      border: 1px solid #202020;\n" +
                "      border-radius: 12px;\n" +
                "      transition: 0.15s ease;\n" +
                "    }\n" +
                "    .song-card:hover, .song-card:active {\n" +
                "      background: #1f1f1f;\n" +
                "      cursor: pointer;\n" +
                "    }\n" +
                "    .thumb-preview {\n" +
                "      width: 48px;\n" +
                "      height: 48px;\n" +
                "      object-fit: cover;\n" +
                "      border-radius: 8px;\n" +
                "    }\n" +
                "    .pill-player-container {\n" +
                "      position: fixed;\n" +
                "      bottom: 74px;\n" +
                "      left: 0;\n" +
                "      right: 0;\n" +
                "      display: flex;\n" +
                "      justify-content: center;\n" +
                "      z-index: 1040;\n" +
                "      padding: 0 14px;\n" +
                "      pointer-events: none;\n" +
                "    }\n" +
                "    .pill-player {\n" +
                "      pointer-events: auto;\n" +
                "      width: 100%;\n" +
                "      max-width: 680px;\n" +
                "      background: rgba(22, 22, 22, 0.95);\n" +
                "      backdrop-filter: blur(20px);\n" +
                "      -webkit-backdrop-filter: blur(20px);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.1);\n" +
                "      border-radius: 40px;\n" +
                "      padding: 10px 18px;\n" +
                "      box-shadow: 0 16px 40px rgba(0, 0, 0, 0.7);\n" +
                "      display: flex;\n" +
                "      flex-direction: column;\n" +
                "      gap: 6px;\n" +
                "    }\n" +
                "    .pill-thumb {\n" +
                "      width: 44px;\n" +
                "      height: 44px;\n" +
                "      object-fit: cover;\n" +
                "      border-radius: 50%;\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.15);\n" +
                "      animation: spin 14s linear infinite;\n" +
                "      animation-play-state: paused;\n" +
                "    }\n" +
                "    .pill-thumb.spinning {\n" +
                "      animation-play-state: running;\n" +
                "    }\n" +
                "    @keyframes spin {\n" +
                "      100% { transform: rotate(360deg); }\n" +
                "    }\n" +
                "    .ctrl-icon-btn {\n" +
                "      background: none;\n" +
                "      border: none;\n" +
                "      color: #b3b3b3;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: center;\n" +
                "      width: 36px;\n" +
                "      height: 36px;\n" +
                "      border-radius: 50%;\n" +
                "      transition: 0.15s;\n" +
                "    }\n" +
                "    .ctrl-icon-btn:hover {\n" +
                "      color: #fff;\n" +
                "      background: rgba(255, 255, 255, 0.08);\n" +
                "    }\n" +
                "    .play-pill-btn {\n" +
                "      background: #1db954;\n" +
                "      color: #000;\n" +
                "      border: none;\n" +
                "      width: 44px;\n" +
                "      height: 44px;\n" +
                "      border-radius: 50%;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: center;\n" +
                "      font-size: 1.35rem;\n" +
                "      box-shadow: 0 4px 14px rgba(29, 185, 84, 0.4);\n" +
                "    }\n" +
                "    .play-pill-btn:active { transform: scale(0.92); }\n" +
                "    .pill-progress-track {\n" +
                "      height: 5px;\n" +
                "      border-radius: 5px;\n" +
                "      background: rgba(255, 255, 255, 0.15);\n" +
                "      cursor: pointer;\n" +
                "      position: relative;\n" +
                "      overflow: hidden;\n" +
                "    }\n" +
                "    .pill-progress-fill {\n" +
                "      height: 100%;\n" +
                "      width: 0%;\n" +
                "      background: #1db954;\n" +
                "      border-radius: 5px;\n" +
                "      transition: width 0.1s linear;\n" +
                "    }\n" +
                "    .vol-slider {\n" +
                "      width: 65px;\n" +
                "      accent-color: #1db954;\n" +
                "    }\n" +
                "    .bottom-nav-bar {\n" +
                "      position: fixed;\n" +
                "      bottom: 0;\n" +
                "      left: 0;\n" +
                "      right: 0;\n" +
                "      height: 64px;\n" +
                "      background: #000;\n" +
                "      border-top: 1px solid #1a1a1a;\n" +
                "      display: flex;\n" +
                "      justify-content: space-around;\n" +
                "      align-items: center;\n" +
                "      z-index: 1050;\n" +
                "    }\n" +
                "    .nav-tab-btn {\n" +
                "      background: none;\n" +
                "      border: none;\n" +
                "      color: #888;\n" +
                "      display: flex;\n" +
                "      flex-direction: column;\n" +
                "      align-items: center;\n" +
                "      font-size: 0.72rem;\n" +
                "      gap: 2px;\n" +
                "      padding: 6px 20px;\n" +
                "    }\n" +
                "    .nav-tab-btn.active {\n" +
                "      color: #1db954;\n" +
                "    }\n" +
                "    .nav-tab-btn i { font-size: 1.25rem; }\n" +
                "    @media (max-width: 576px) {\n" +
                "      .vol-wrap, .rewind-wrap { display: none !important; }\n" +
                "      .pill-player { padding: 8px 14px; border-radius: 32px; }\n" +
                "      .pill-thumb { width: 38px; height: 38px; }\n" +
                "      .play-pill-btn { width: 38px; height: 38px; font-size: 1.15rem; }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<nav class=\"navbar sticky-top px-3 py-2\">\n" +
                "  <div class=\"container-fluid d-flex align-items-center justify-content-between\">\n" +
                "    <span class=\"navbar-brand text-success fw-bold d-flex align-items-center gap-2 mb-0 fs-5\">\n" +
                "      <i class=\"bi bi-spotify fs-3\"></i> SpotifyLite\n" +
                "    </span>\n" +
                "    <span class=\"badge rounded-pill text-bg-dark border border-secondary\"><i class=\"bi bi-cast me-1 text-success\"></i> Web Audio</span>\n" +
                "  </div>\n" +
                "</nav>\n" +
                "<div class=\"container mt-3\" style=\"max-width: 700px;\">\n" +
                "  <!-- Home View -->\n" +
                "  <div id=\"tabHome\">\n" +
                "    <div class=\"input-group mb-3\">\n" +
                "      <input type=\"text\" id=\"searchInput\" class=\"form-control search-input-pill\" placeholder=\"Search songs, artists, albums...\" onkeydown=\"if(event.key==='Enter') searchSongs(false)\">\n" +
                "    </div>\n" +
                "    <div id=\"loadingSpinner\" class=\"text-center my-4 d-none\">\n" +
                "      <div class=\"spinner-border spinner-border-sm text-success\" role=\"status\"></div>\n" +
                "      <span class=\"ms-2 text-secondary small\" id=\"spinnerText\">Searching YouTube Music...</span>\n" +
                "    </div>\n" +
                "    <div id=\"resultsList\" class=\"d-flex flex-column gap-2\"></div>\n" +
                "    <div class=\"text-center mt-3 d-none\" id=\"loadMoreContainer\">\n" +
                "      <button class=\"btn btn-sm btn-outline-secondary rounded-pill px-4 py-2\" onclick=\"searchSongs(true)\"><i class=\"bi bi-arrow-down-circle me-1 text-success\"></i> Load More</button>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <!-- Downloads View -->\n" +
                "  <div id=\"tabDownloads\" class=\"d-none\">\n" +
                "    <div class=\"d-flex align-items-center justify-content-between mb-3 px-1\">\n" +
                "      <h5 class=\"fw-bold mb-0 text-white\"><i class=\"bi bi-arrow-down-circle-fill text-success me-2\"></i> Downloaded Music</h5>\n" +
                "      <button class=\"btn btn-sm btn-outline-secondary rounded-pill px-3\" onclick=\"loadDownloads()\"><i class=\"bi bi-arrow-clockwise me-1\"></i> Refresh</button>\n" +
                "    </div>\n" +
                "    <div id=\"downloadsList\" class=\"d-flex flex-column gap-2\"></div>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<!-- Real Native Audio Player Element -->\n" +
                "<audio id=\"webAudio\" preload=\"auto\"></audio>\n" +
                "<!-- Floating Pill Player -->\n" +
                "<div class=\"pill-player-container\">\n" +
                "  <div class=\"pill-player\">\n" +
                "    <div class=\"d-flex align-items-center justify-content-between gap-2\">\n" +
                "      <div class=\"d-flex align-items-center gap-2 overflow-hidden flex-grow-1\" style=\"min-width: 130px;\">\n" +
                "        <img id=\"playerThumb\" src=\"https://placehold.co/44x44/141414/888888?text=♪\" class=\"pill-thumb\" alt=\"Cover\">\n" +
                "        <div class=\"overflow-hidden\">\n" +
                "          <div id=\"playerTitle\" class=\"fw-bold text-truncate text-white small\" style=\"max-width: 180px;\">Ready to play</div>\n" +
                "          <div id=\"playerArtist\" class=\"text-secondary text-truncate\" style=\"font-size: 0.75rem; max-width: 180px;\">Select a song</div>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "      <div class=\"d-flex align-items-center gap-1\">\n" +
                "        <button class=\"ctrl-icon-btn\" onclick=\"playPrevious()\" title=\"Previous\"><i class=\"bi bi-skip-start-fill fs-5\"></i></button>\n" +
                "        <button class=\"ctrl-icon-btn rewind-wrap\" onclick=\"seekRelative(-10)\" title=\"-10s\"><i class=\"bi bi-arrow-counterclockwise fs-6\"></i></button>\n" +
                "        <button id=\"playBtn\" class=\"play-pill-btn ms-1 me-1\" onclick=\"togglePlay()\"><i class=\"bi bi-play-fill\"></i></button>\n" +
                "        <button class=\"ctrl-icon-btn rewind-wrap\" onclick=\"seekRelative(10)\" title=\"+10s\"><i class=\"bi bi-arrow-clockwise fs-6\"></i></button>\n" +
                "        <button class=\"ctrl-icon-btn\" onclick=\"playNext()\" title=\"Next\"><i class=\"bi bi-skip-end-fill fs-5\"></i></button>\n" +
                "      </div>\n" +
                "      <div class=\"d-flex align-items-center gap-2 vol-wrap ps-1\">\n" +
                "        <i class=\"bi bi-volume-up text-secondary small\"></i>\n" +
                "        <input type=\"range\" class=\"form-range vol-slider\" id=\"volRange\" min=\"0\" max=\"1\" step=\"0.05\" value=\"1\" oninput=\"setVolume(this.value)\">\n" +
                "      </div>\n" +
                "    </div>\n" +
                "    <div class=\"px-2\">\n" +
                "      <div class=\"pill-progress-track\" onclick=\"handleProgressClick(event)\">\n" +
                "        <div id=\"progressFill\" class=\"pill-progress-fill\"></div>\n" +
                "      </div>\n" +
                "      <div class=\"d-flex justify-content-between text-secondary pt-1\" style=\"font-size: 0.68rem;\">\n" +
                "        <span id=\"timeCurrent\">00:00</span>\n" +
                "        <span id=\"timeTotal\">00:00</span>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<!-- Bottom Nav Bar -->\n" +
                "<div class=\"bottom-nav-bar\">\n" +
                "  <button id=\"btnTabHome\" class=\"nav-tab-btn active\" onclick=\"switchTab('home')\">\n" +
                "    <i class=\"bi bi-house-door-fill\"></i>\n" +
                "    <span>Home</span>\n" +
                "  </button>\n" +
                "  <button id=\"btnTabDownloads\" class=\"nav-tab-btn\" onclick=\"switchTab('downloads')\">\n" +
                "    <i class=\"bi bi-arrow-down-circle-fill\"></i>\n" +
                "    <span>Downloads</span>\n" +
                "  </button>\n" +
                "</div>\n" +
                "<script>\n" +
                "  const audio = document.getElementById('webAudio');\n" +
                "  let tracks = [];\n" +
                "  let currentIdx = -1;\n" +
                "  let currentFetchLimit = 25;\n" +
                "  let activeQuery = '';\n" +
                "  let currentTrack = null;\n" +
                "  function switchTab(tab) {\n" +
                "    if (tab === 'home') {\n" +
                "      document.getElementById('tabHome').classList.remove('d-none');\n" +
                "      document.getElementById('tabDownloads').classList.add('d-none');\n" +
                "      document.getElementById('btnTabHome').classList.add('active');\n" +
                "      document.getElementById('btnTabDownloads').classList.remove('active');\n" +
                "    } else {\n" +
                "      document.getElementById('tabHome').classList.add('d-none');\n" +
                "      document.getElementById('tabDownloads').classList.remove('d-none');\n" +
                "      document.getElementById('btnTabHome').classList.remove('active');\n" +
                "      document.getElementById('btnTabDownloads').classList.add('active');\n" +
                "      loadDownloads();\n" +
                "    }\n" +
                "  }\n" +
                "  async function searchSongs(isLoadMore = false) {\n" +
                "    const qInput = document.getElementById('searchInput').value.trim();\n" +
                "    if (!qInput) return;\n" +
                "    if (!isLoadMore) {\n" +
                "      activeQuery = qInput;\n" +
                "      currentFetchLimit = 25;\n" +
                "      tracks = [];\n" +
                "      document.getElementById('resultsList').innerHTML = '';\n" +
                "    } else {\n" +
                "      currentFetchLimit += 25;\n" +
                "    }\n" +
                "    document.getElementById('spinnerText').innerText = isLoadMore ? 'Loading more tracks...' : 'Searching YouTube...';\n" +
                "    document.getElementById('loadingSpinner').classList.remove('d-none');\n" +
                "    try {\n" +
                "      const res = await fetch(`/api/search?q=${encodeURIComponent(activeQuery)}&limit=${currentFetchLimit}`);\n" +
                "      tracks = await res.json();\n" +
                "      renderTracks();\n" +
                "      document.getElementById('loadMoreContainer').classList.remove('d-none');\n" +
                "    } catch (e) { alert('Search failed'); }\n" +
                "    document.getElementById('loadingSpinner').classList.add('d-none');\n" +
                "  }\n" +
                "  function renderTracks() {\n" +
                "    let html = '';\n" +
                "    tracks.forEach((t, i) => {\n" +
                "      const thumb = 'https://img.youtube.com/vi/' + t.id + '/mqdefault.jpg';\n" +
                "      html += `<div class=\"song-card p-2 d-flex align-items-center justify-content-between\">\n" +
                "        <div class=\"d-flex align-items-center gap-3 overflow-hidden flex-grow-1\" onclick=\"playTrackIndex(${i})\">\n" +
                "          <img src=\"${thumb}\" class=\"thumb-preview\" alt=\"Cover\">\n" +
                "          <div class=\"overflow-hidden\">\n" +
                "            <div class=\"fw-semibold text-truncate small\">${t.title}</div>\n" +
                "            <div class=\"text-secondary\" style=\"font-size: 0.75rem;\">${t.artist} • ${t.duration}</div>\n" +
                "          </div>\n" +
                "        </div>\n" +
                "        <button class=\"btn btn-outline-secondary btn-sm border-0 rounded-circle\" onclick=\"downloadTrack('${t.id}', '${encodeURIComponent(t.title)}')\" title=\"Download MP3\"><i class=\"bi bi-download text-secondary\"></i></button>\n" +
                "      </div>`;\n" +
                "    });\n" +
                "    document.getElementById('resultsList').innerHTML = html;\n" +
                "  }\n" +
                "  async function loadDownloads() {\n" +
                "    const listEl = document.getElementById('downloadsList');\n" +
                "    listEl.innerHTML = '<div class=\"text-center text-secondary py-4\"><div class=\"spinner-border spinner-border-sm text-success me-2\"></div>Scanning Music directory...</div>';\n" +
                "    try {\n" +
                "      const res = await fetch('/api/local-downloads');\n" +
                "      const files = await res.json();\n" +
                "      if (files.length === 0) {\n" +
                "        listEl.innerHTML = '<div class=\"text-center text-secondary py-5\"><i class=\"bi bi-music-note-beamed fs-1 d-block mb-2 text-muted\"></i>No downloaded songs yet.<br>Download songs from the Home tab.</div>';\n" +
                "        return;\n" +
                "      }\n" +
                "      let html = '';\n" +
                "      files.forEach((f, idx) => {\n" +
                "        html += `<div class=\"song-card p-2 d-flex align-items-center justify-content-between\">\n" +
                "          <div class=\"d-flex align-items-center gap-3 overflow-hidden flex-grow-1\" onclick=\"playLocalFile('${encodeURIComponent(f.id)}', '${encodeURIComponent(f.title)}')\">\n" +
                "            <div class=\"thumb-preview bg-dark d-flex align-items-center justify-content-center text-success border border-secondary border-opacity-25\">\n" +
                "              <i class=\"bi bi-file-earmark-music fs-4\"></i>\n" +
                "            </div>\n" +
                "            <div class=\"overflow-hidden\">\n" +
                "              <div class=\"fw-semibold text-truncate small\">${f.title}</div>\n" +
                "              <div class=\"text-secondary\" style=\"font-size: 0.75rem;\"><i class=\"bi bi-hdd-fill me-1 text-success\"></i>${f.duration} • Local Audio</div>\n" +
                "            </div>\n" +
                "          </div>\n" +
                "          <button class=\"btn btn-outline-success btn-sm border-0 rounded-circle\" onclick=\"playLocalFile('${encodeURIComponent(f.id)}', '${encodeURIComponent(f.title)}')\"><i class=\"bi bi-play-circle-fill fs-5\"></i></button>\n" +
                "        </div>`;\n" +
                "      });\n" +
                "      listEl.innerHTML = html;\n" +
                "    } catch(e) {\n" +
                "      listEl.innerHTML = '<div class=\"text-danger text-center py-4\">Failed to read local files.</div>';\n" +
                "    }\n" +
                "  }\n" +
                "  function playTrackIndex(idx) {\n" +
                "    if (idx < 0 || idx >= tracks.length) return;\n" +
                "    currentIdx = idx;\n" +
                "    const t = tracks[idx];\n" +
                "    currentTrack = t;\n" +
                "    document.getElementById('playerTitle').innerText = t.title;\n" +
                "    document.getElementById('playerArtist').innerText = t.artist;\n" +
                "    const thumbUrl = 'https://img.youtube.com/vi/' + t.id + '/hqdefault.jpg';\n" +
                "    document.getElementById('playerThumb').src = thumbUrl;\n" +
                "    audio.src = '/stream?id=' + encodeURIComponent(t.id);\n" +
                "    audio.play().catch(()=>{});\n" +
                "    setupMediaSession(t.title, t.artist, thumbUrl);\n" +
                "  }\n" +
                "  function playLocalFile(path, title) {\n" +
                "    const decTitle = decodeURIComponent(title);\n" +
                "    currentTrack = { title: decTitle, artist: 'Offline Storage', isLocal: true };\n" +
                "    document.getElementById('playerTitle').innerText = decTitle;\n" +
                "    document.getElementById('playerArtist').innerText = 'Offline Storage';\n" +
                "    document.getElementById('playerThumb').src = 'https://placehold.co/44x44/141414/1db954?text=♪';\n" +
                "    audio.src = '/stream-file?path=' + path;\n" +
                "    audio.play().catch(()=>{});\n" +
                "    setupMediaSession(decTitle, 'Offline Storage', 'https://placehold.co/512x512/141414/1db954?text=Offline');\n" +
                "  }\n" +
                "  function togglePlay() {\n" +
                "    if (!audio.src) return;\n" +
                "    if (audio.paused) {\n" +
                "      audio.play().catch(()=>{});\n" +
                "    } else {\n" +
                "      audio.pause();\n" +
                "    }\n" +
                "  }\n" +
                "  function seekRelative(sec) {\n" +
                "    if (audio.duration) audio.currentTime = Math.max(0, Math.min(audio.duration, audio.currentTime + sec));\n" +
                "  }\n" +
                "  function handleProgressClick(e) {\n" +
                "    if (!audio.duration) return;\n" +
                "    const rect = e.currentTarget.getBoundingClientRect();\n" +
                "    const ratio = (e.clientX - rect.left) / rect.width;\n" +
                "    audio.currentTime = ratio * audio.duration;\n" +
                "  }\n" +
                "  function setVolume(val) { audio.volume = val; }\n" +
                "  function playNext() {\n" +
                "    if (currentIdx + 1 < tracks.length) playTrackIndex(currentIdx + 1);\n" +
                "  }\n" +
                "  function playPrevious() {\n" +
                "    if (currentIdx > 0) playTrackIndex(currentIdx - 1);\n" +
                "  }\n" +
                "  async function downloadTrack(id, title) {\n" +
                "    await fetch(`/api/download?id=${id}&title=${title}`);\n" +
                "    alert('Download started in background!');\n" +
                "  }\n" +
                "  function formatTime(sec) {\n" +
                "    if (isNaN(sec)) return '00:00';\n" +
                "    const m = Math.floor(sec / 60), s = Math.floor(sec % 60);\n" +
                "    return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;\n" +
                "  }\n" +
                "  // --- Browser Events & Native Android MediaSession Integration ---\n" +
                "  audio.addEventListener('play', () => {\n" +
                "    document.getElementById('playBtn').innerHTML = '<i class=\"bi bi-pause-fill\"></i>';\n" +
                "    document.getElementById('playerThumb').classList.add('spinning');\n" +
                "    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';\n" +
                "  });\n" +
                "  audio.addEventListener('pause', () => {\n" +
                "    document.getElementById('playBtn').innerHTML = '<i class=\"bi bi-play-fill\"></i>';\n" +
                "    document.getElementById('playerThumb').classList.remove('spinning');\n" +
                "    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';\n" +
                "  });\n" +
                "  audio.addEventListener('timeupdate', () => {\n" +
                "    document.getElementById('timeCurrent').innerText = formatTime(audio.currentTime);\n" +
                "    document.getElementById('timeTotal').innerText = formatTime(audio.duration);\n" +
                "    const pct = audio.duration ? (audio.currentTime / audio.duration) * 100 : 0;\n" +
                "    document.getElementById('progressFill').style.width = pct + '%';\n" +
                "    if ('mediaSession' in navigator && audio.duration && 'setPositionState' in navigator.mediaSession) {\n" +
                "      try {\n" +
                "        navigator.mediaSession.setPositionState({\n" +
                "          duration: audio.duration,\n" +
                "          playbackRate: audio.playbackRate,\n" +
                "          position: audio.currentTime\n" +
                "        });\n" +
                "      } catch(e) {}\n" +
                "    }\n" +
                "  });\n" +
                "  audio.addEventListener('ended', () => { playNext(); });\n" +
                "  function setupMediaSession(title, artist, thumb) {\n" +
                "    if ('mediaSession' in navigator) {\n" +
                "      navigator.mediaSession.metadata = new MediaMetadata({\n" +
                "        title: title,\n" +
                "        artist: artist,\n" +
                "        album: 'SpotifyLite Web',\n" +
                "        artwork: [{ src: thumb, sizes: '512x512', type: 'image/jpeg' }]\n" +
                "      });\n" +
                "      navigator.mediaSession.setActionHandler('play', () => togglePlay());\n" +
                "      navigator.mediaSession.setActionHandler('pause', () => togglePlay());\n" +
                "      navigator.mediaSession.setActionHandler('seekbackward', (d) => seekRelative(-(d.seekOffset || 10)));\n" +
                "      navigator.mediaSession.setActionHandler('seekforward', (d) => seekRelative(d.seekOffset || 10));\n" +
                "      navigator.mediaSession.setActionHandler('previoustrack', () => playPrevious());\n" +
                "      navigator.mediaSession.setActionHandler('nexttrack', () => playNext());\n" +
                "      navigator.mediaSession.setActionHandler('seekto', (d) => {\n" +
                "        if (d.seekTime !== undefined) audio.currentTime = d.seekTime;\n" +
                "      });\n" +
                "    }\n" +
                "  }\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }
}
