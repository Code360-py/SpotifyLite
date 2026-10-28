# SpotifyLite

A lightweight, terminal-powered music player and responsive web client built directly for Android via Termux. It orchestrates YouTube audio streaming and local music playback using a native Java HTTP server, `yt-dlp`, and modern HTML5 Web Audio APIs—with zero heavyweight frameworks.

---

## Features

- **Pure HTML5 Audio Engine**: Direct browser streaming with smooth seeking, byte-range scrubbing, and automatic track-to-track auto-play.
- **Android Media Integration**: Native Lockscreen & Notification tray integration via HTML5 `navigator.mediaSession` with full artwork, scrubber, and playback controls (Play/Pause, Skip, Prev, Fast-Forward/Rewind).
- **Floating Pill Player UI**: Frosted-glass backdrop blur (`backdrop-filter`), spinning vinyl record animation, responsive touch controls, and progress tracking.
- **Dual Tab Interface**:
  - **Home**: Search YouTube Music with dynamic pagination ("Load More Songs").
  - **Downloads**: Built-in scanner and offline player for tracks stored in `~/storage/shared/Music`.
- **Background Downloader**: Rip high-quality MP3s straight to Android shared storage.
- **Lightweight Architecture**: Single-file Java backend using `com.sun.net.httpserver.HttpServer` with zero external jar dependencies.

---

## Prerequisites

Run these commands inside Termux to install the required environment:

```bash
pkg update && pkg install openjdk-17 python ffmpeg -y
pip install -U yt-dlp
termux-setup-storage
```

## Build & Compile
​To compile the web version into a clean, standalone executable JAR:
```bash
cd ~/SpotifyLite
rm -rf bin SpotifyLiteWeb.jar
mkdir -p bin
javac -d bin SpotifyLiteWeb.java
jar cfe SpotifyLiteWeb.jar SpotifyLiteWeb -C bin .
rm -rf bin
```
## Running the Application
```
java -jar ~/SpotifyLite/SpotifyLiteWeb.jar
```
Or configure a global command:
```bash
echo 'java -jar ~/SpotifyLite/SpotifyLiteWeb.jar "$@"' > $PREFIX/bin/spotifyliteweb
chmod +x $PREFIX/bin/spotifyliteweb
```
Then start it from anywhere by running:
```bash
spotifyliteweb
```

## License
MIT License. Built for local personal streaming and education.
