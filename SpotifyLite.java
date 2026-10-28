import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SpotifyLite {

    private static final String SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/mpv-socket";
    private static final String NOTIF_ID = "spotifylite_player";
    private static final String ACTION_FIFO = "/data/data/com.termux/files/usr/tmp/spotifylite-actions";
    private static final Path CACHE_FILE = Paths.get(System.getProperty("user.home"), ".spotifylite", "cache.json");
    private static final Path FAV_FILE = Paths.get(System.getProperty("user.home"), ".spotifylite", "favorites.json");

    private static Process mpvProcess = null;
    private static final List<Track> currentSearchResults = new ArrayList<>();
    private static final List<Track> favoritesList = new ArrayList<>();
    private static int currentTrackIndex = -1;

    private static volatile boolean isPlaying = false;
    private static volatile boolean isPaused = false;
    private static volatile boolean exitApp = false;
    private static volatile int currentVolume = 100;
    private static Track activeTrack = null;

    static class Track {
        String id;
        String title;
        String duration;
        String artist;

        Track(String id, String title, String duration, String artist) {
            this.id = id;
            this.title = title;
            this.duration = duration;
            this.artist = artist;
        }

        String toJson() {
            return String.format("{\"id\":\"%s\",\"title\":\"%s\",\"duration\":\"%s\",\"artist\":\"%s\"}",
                    escape(id), escape(title), escape(duration), escape(artist));
        }

        private String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static void main(String[] args) {
        initDirectories();
        initMpvDaemon();
        initActionFifo();
        loadFavorites();

        startNotificationActionListener();
        startProgressTracker();
        printMainBanner();

        Scanner scanner = new Scanner(System.in);

        while (!exitApp) {
            String prompt = isPlaying ? "\033[1;32m[Player] > \033[0m" : "\033[1;34m[SpotifyLite] > \033[0m";
            System.out.print(prompt);

            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/q")) {
                break;
            } else if (input.equalsIgnoreCase("/play")) {
                togglePause(false);
            } else if (input.equalsIgnoreCase("/pause")) {
                togglePause(true);
            } else if (input.equalsIgnoreCase("/rewind") || input.equalsIgnoreCase("/r")) {
                sendMpvCommand("{\"command\": [\"seek\", -10]}");
                System.out.println("⏪ Rewound 10s");
            } else if (input.equalsIgnoreCase("/ff") || input.equalsIgnoreCase("/f")) {
                sendMpvCommand("{\"command\": [\"seek\", 10]}");
                System.out.println("⏩ Fast-forward 10s");
            } else if (input.startsWith("/vol ")) {
                handleVolume(input.substring(5).trim());
            } else if (input.equalsIgnoreCase("/next") || input.equalsIgnoreCase("/n")) {
                handleNext();
            } else if (input.equalsIgnoreCase("/previous") || input.equalsIgnoreCase("/prev") || input.equalsIgnoreCase("/p")) {
                handlePrevious();
            } else if (input.equalsIgnoreCase("/fav")) {
                handleSaveFavorite();
            } else if (input.equalsIgnoreCase("/favs")) {
                displayFavorites();
            } else if (input.startsWith("/search ")) {
                handleSearch(input.substring(8).trim());
            } else if (input.matches("^[1-5]$")) {
                handleSelection(Integer.parseInt(input));
            } else if (input.startsWith("/download")) {
                handleDownload(input);
            } else {
                handleSearch(input);
            }
        }

        shutdown();
        System.out.println("\nExiting SpotifyLite. Goodbye!");
    }

    private static void initDirectories() {
        try {
            Files.createDirectories(Paths.get(System.getProperty("user.home"), ".spotifylite"));
        } catch (Exception ignored) {}
    }

    private static void printMainBanner() {
        System.out.println("==================================================");
        System.out.println("             SpotifyLite CLI v2.0                 ");
        System.out.println("==================================================");
        System.out.println(" Commands:");
        System.out.println("   <song> / /search <term>  - Search top 5 tracks");
        System.out.println("   1-5                      - Play selected track");
        System.out.println("   /play, /pause            - Play / Pause");
        System.out.println("   /next, /prev             - Playlist navigation");
        System.out.println("   /rewind, /ff             - Seek +/- 10 seconds");
        System.out.println("   /vol <0-130>             - Set volume");
        System.out.println("   /fav                     - Bookmark playing song");
        System.out.println("   /favs                    - View saved favorites");
        System.out.println("   /download [1-5]          - Save MP3 to Music");
        System.out.println("   /quit                    - Exit");
        System.out.println("==================================================");
    }

    private static void initMpvDaemon() {
        new File(SOCKET_PATH).delete();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "mpv",
                    "--idle",
                    "--no-video",
                    "--ao=opensles,aaudio,pulse",
                    "--volume=" + currentVolume,
                    "--input-ipc-server=" + SOCKET_PATH
            );
            pb.redirectError(new File("/dev/null"));
            pb.redirectOutput(new File("/dev/null"));
            mpvProcess = pb.start();
            Thread.sleep(300);
        } catch (Exception e) {
            System.err.println("Player daemon initialization failed: " + e.getMessage());
        }
    }

    private static void initActionFifo() {
        try {
            new File(ACTION_FIFO).delete();
            new ProcessBuilder("mkfifo", ACTION_FIFO).start().waitFor();
        } catch (Exception ignored) {}
    }

    private static void handleSearch(String query) {
        if (query.isEmpty()) return;
        System.out.println("\nSearching YouTube for: " + query + "...");
        currentSearchResults.clear();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "ytsearch5:" + query,
                    "--print", "%(id)s||%(title)s||%(duration_string)s||%(uploader)s",
                    "--no-warnings"
            );
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int rank = 1;

            System.out.println("\n------------------- Top Results -------------------");
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|\\|");
                if (parts.length >= 4) {
                    Track track = new Track(parts[0], parts[1], parts[2], parts[3]);
                    currentSearchResults.add(track);
                    System.out.printf("[%d] %s (%s) - %s%n", rank++, track.title, track.duration, track.artist);
                }
            }
            p.waitFor();

            if (currentSearchResults.isEmpty()) {
                System.out.println("No tracks found.");
            } else {
                saveCache();
                System.out.println("--------------------------------------------------");
                System.out.println("Type 1-5 to stream immediately.");
            }
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
        }
    }

    private static void handleSelection(int choice) {
        if (choice < 1 || choice > currentSearchResults.size()) {
            System.out.println("Invalid choice. Enter 1 to " + currentSearchResults.size());
            return;
        }
        currentTrackIndex = choice - 1;
        playTrack(currentSearchResults.get(currentTrackIndex));
    }

    private static void playTrack(Track track) {
        activeTrack = track;
        System.out.println("\nResolving audio stream: " + track.title + "...");

        try {
            ProcessBuilder urlPb = new ProcessBuilder(
                    "yt-dlp",
                    "-f", "bestaudio/ba",
                    "-g",
                    "https://www.youtube.com/watch?v=" + track.id
            );
            Process urlProc = urlPb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(urlProc.getInputStream()));
            String streamUrl = r.readLine();
            urlProc.waitFor();

            if (streamUrl != null && !streamUrl.trim().isEmpty()) {
                String cmd = String.format("{\"command\": [\"loadfile\", \"%s\", \"replace\"]}", streamUrl.trim());
                sendMpvCommand(cmd);
                isPlaying = true;
                isPaused = false;
                System.out.println("\n🎵 Now Playing: " + track.title);
                System.out.println("👤 Artist:      " + track.artist);
                updateNotification();
            } else {
                System.out.println("Failed to fetch stream URL.");
            }
        } catch (Exception e) {
            System.err.println("Playback error: " + e.getMessage());
        }
    }

    private static void togglePause(boolean pause) {
        sendMpvCommand("{\"command\": [\"set_property\", \"pause\", " + pause + "]}");
        isPaused = pause;
        System.out.println(pause ? "⏸ Paused." : "▶ Resumed.");
        updateNotification();
    }

    private static void handleVolume(String val) {
        try {
            int vol = Integer.parseInt(val);
            vol = Math.max(0, Math.min(130, vol));
            currentVolume = vol;
            sendMpvCommand("{\"command\": [\"set_property\", \"volume\", " + vol + "]}");
            System.out.println("🔊 Volume set to: " + vol + "%");
        } catch (Exception e) {
            System.out.println("Invalid volume. Use: /vol <0-130>");
        }
    }

    private static void handleNext() {
        if (currentSearchResults.isEmpty() || currentTrackIndex == -1) return;
        if (currentTrackIndex + 1 < currentSearchResults.size()) {
            currentTrackIndex++;
            playTrack(currentSearchResults.get(currentTrackIndex));
        } else {
            System.out.println("End of playlist.");
        }
    }

    private static void handlePrevious() {
        if (currentSearchResults.isEmpty() || currentTrackIndex == -1) return;
        if (currentTrackIndex - 1 >= 0) {
            currentTrackIndex--;
            playTrack(currentSearchResults.get(currentTrackIndex));
        } else {
            System.out.println("Already at the first track.");
        }
    }

    private static void handleSaveFavorite() {
        if (activeTrack == null) {
            System.out.println("No track currently playing.");
            return;
        }
        for (Track t : favoritesList) {
            if (t.id.equals(activeTrack.id)) {
                System.out.println("Track is already in your favorites.");
                return;
            }
        }
        favoritesList.add(activeTrack);
        saveFavorites();
        System.out.println("★ Added to Favorites: " + activeTrack.title);
    }

    private static void displayFavorites() {
        if (favoritesList.isEmpty()) {
            System.out.println("No favorites saved yet. Type /fav while listening to save.");
            return;
        }
        currentSearchResults.clear();
        currentSearchResults.addAll(favoritesList);
        System.out.println("\n----------------- Saved Favorites -----------------");
        for (int i = 0; i < favoritesList.size(); i++) {
            Track t = favoritesList.get(i);
            System.out.printf("[%d] %s (%s) - %s%n", i + 1, t.title, t.duration, t.artist);
        }
        System.out.println("--------------------------------------------------");
        System.out.println("Type 1-" + favoritesList.size() + " to play a favorite.");
    }

    private static void updateNotification() {
        if (activeTrack == null || !isPlaying) return;
        try {
            String toggleText = isPaused ? "Play" : "Pause";
            String toggleAction = isPaused ? "play" : "pause";

            ProcessBuilder pb = new ProcessBuilder(
                    "termux-notification",
                    "-i", NOTIF_ID,
                    "--title", activeTrack.title,
                    "--content", activeTrack.artist + " (" + activeTrack.duration + ")",
                    "--priority", "high",
                    "--ongoing",
                    "--button1", toggleText,
                    "--button1-action", "echo " + toggleAction + " > " + ACTION_FIFO,
                    "--button2", "Next",
                    "--button2-action", "echo next > " + ACTION_FIFO,
                    "--button3", "Prev",
                    "--button3-action", "echo prev > " + ACTION_FIFO
            );
            pb.start();
        } catch (Exception ignored) {}
    }

    private static void cancelNotification() {
        try {
            new ProcessBuilder("termux-notification-remove", NOTIF_ID).start();
        } catch (Exception ignored) {}
    }

    private static void startNotificationActionListener() {
        Thread t = new Thread(() -> {
            while (!exitApp) {
                try (BufferedReader reader = new BufferedReader(new FileReader(ACTION_FIFO))) {
                    String action;
                    while ((action = reader.readLine()) != null) {
                        action = action.trim();
                        if (action.equals("play")) togglePause(false);
                        else if (action.equals("pause")) togglePause(true);
                        else if (action.equals("next")) handleNext();
                        else if (action.equals("prev")) handlePrevious();
                    }
                } catch (Exception e) {
                    try { Thread.sleep(400); } catch (Exception ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void startProgressTracker() {
        Thread progressThread = new Thread(() -> {
            while (!exitApp) {
                try {
                    Thread.sleep(1000);
                    if (!isPlaying) continue;

                    Double pos = getMpvPropertyDouble("time-pos");
                    Double dur = getMpvPropertyDouble("duration");

                    if (pos != null && dur != null && dur > 0) {
                        int barLength = 16;
                        double fraction = Math.min(1.0, pos / dur);
                        int filled = (int) (fraction * barLength);

                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < barLength; i++) {
                            if (i < filled) sb.append("█");
                            else sb.append("░");
                        }
                        sb.append(String.format("] %s / %s %s Vol:%d%%",
                                formatSeconds(pos.intValue()),
                                formatSeconds(dur.intValue()),
                                (isPaused ? "⏸" : "▶"),
                                currentVolume));

                        // ANSI codes to jump to bottom line, print bar, and return cleanly
                        System.out.print("\033[s");           // Save cursor
                        System.out.print("\033[999;1H");       // Move to bottom row
                        System.out.print("\033[2K");           // Clear row
                        System.out.print("\033[1;36m" + sb.toString() + "\033[0m"); // Cyan bar
                        System.out.print("\033[u");           // Restore cursor
                        System.out.flush();
                    }
                } catch (Exception ignored) {}
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();
    }

    private static String formatSeconds(int totalSecs) {
        return String.format("%02d:%02d", totalSecs / 60, totalSecs % 60);
    }

    private static Double getMpvPropertyDouble(String prop) {
        String resp = queryMpv("{\"command\": [\"get_property\", \"" + prop + "\"]}");
        if (resp == null) return null;
        try {
            int dataIdx = resp.indexOf("\"data\":");
            if (dataIdx != -1) {
                int start = dataIdx + 7;
                int end = resp.indexOf(",", start);
                if (end == -1) end = resp.indexOf("}", start);
                if (end != -1) return Double.parseDouble(resp.substring(start, end).trim());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void sendMpvCommand(String jsonCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder("socat", "-", SOCKET_PATH);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write((jsonCommand + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static String queryMpv(String jsonCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder("socat", "-", SOCKET_PATH);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write((jsonCommand + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCache() {
        try {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < currentSearchResults.size(); i++) {
                sb.append("  ").append(currentSearchResults.get(i).toJson());
                if (i < currentSearchResults.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            Files.writeString(CACHE_FILE, sb.toString());
        } catch (Exception ignored) {}
    }

    private static void saveFavorites() {
        try {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < favoritesList.size(); i++) {
                sb.append("  ").append(favoritesList.get(i).toJson());
                if (i < favoritesList.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            Files.writeString(FAV_FILE, sb.toString());
        } catch (Exception ignored) {}
    }

    private static void loadFavorites() {
        if (!Files.exists(FAV_FILE)) return;
        try {
            String content = Files.readString(FAV_FILE);
            String[] entries = content.split("\\{");
            for (String entry : entries) {
                if (!entry.contains("id")) continue;
                String id = extractJsonField(entry, "id");
                String title = extractJsonField(entry, "title");
                String dur = extractJsonField(entry, "duration");
                String artist = extractJsonField(entry, "artist");
                if (id != null && title != null) {
                    favoritesList.add(new Track(id, title, dur != null ? dur : "0:00", artist != null ? artist : "Unknown"));
                }
            }
        } catch (Exception ignored) {}
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start == -1) return null;
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private static void handleDownload(String input) {
        Track target = null;
        String[] tokens = input.split("\\s+");

        if (tokens.length > 1 && tokens[1].matches("^[1-5]$")) {
            int idx = Integer.parseInt(tokens[1]) - 1;
            if (idx < currentSearchResults.size()) target = currentSearchResults.get(idx);
        } else if (currentTrackIndex != -1 && currentTrackIndex < currentSearchResults.size()) {
            target = currentSearchResults.get(currentTrackIndex);
        }

        if (target == null) {
            System.out.println("Specify a track index (e.g. /download 1) or play one first.");
            return;
        }

        final Track downloadTarget = target;
        System.out.println("\nDownloading: " + downloadTarget.title);

        new Thread(() -> {
            try {
                String musicDir = System.getProperty("user.home") + "/storage/shared/Music";
                new File(musicDir).mkdirs();
                String outPath = musicDir + "/%(title)s.%(ext)s";

                ProcessBuilder pb = new ProcessBuilder(
                        "yt-dlp",
                        "-x",
                        "--audio-format", "mp3",
                        "--audio-quality", "0",
                        "-o", outPath,
                        "https://www.youtube.com/watch?v=" + downloadTarget.id
                );
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
                System.out.println("\n[✓] Download finished: " + downloadTarget.title);
            } catch (Exception e) {
                System.err.println("Download error: " + e.getMessage());
            }
        }).start();
    }

    private static void shutdown() {
        exitApp = true;
        cancelNotification();
        if (mpvProcess != null && mpvProcess.isAlive()) {
            sendMpvCommand("{\"command\": [\"quit\"]}");
            mpvProcess.destroy();
        }
        new File(SOCKET_PATH).delete();
        new File(ACTION_FIFO).delete();
    }
}
