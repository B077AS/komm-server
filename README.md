<h1 align="center">
  <img src="src/main/resources/icon.png" alt="Komm icon" width="80"><br>
  komm-server
</h1>

<p align="center">
  <b>The self-hosted community server for <a href="https://kommvoice.com">Komm</a> - a free, self-hosted voice, video &amp; text chat platform.</b><br>
  Voice &amp; video rooms · Screen sharing · Rich messaging · Soundboards · Roles &amp; permissions · Moderation
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 4" src="https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Embedded PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-embedded-4169E1?logo=postgresql&logoColor=white">
  <img alt="LiveKit SFU" src="https://img.shields.io/badge/LiveKit-embedded%20SFU-FF6352">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

---

## What is Komm?

Komm is a chat platform built around one idea: **your community's messages and voice traffic belong on hardware you control.** Every community runs on its own server - WebRTC voice channels, HD screen sharing, rich messaging, soundboards, roles & permissions and moderation - without handing your conversations to anyone else. Free, no ads, no tracking.

| Piece | Role | Who runs it |
|---|---|---|
| [komm](https://github.com/B077AS/komm) | Desktop client (Windows & Linux) and its launcher | Everyone |
| **komm-server** (this repo) | A community's own server: channels, messages, voice, permissions. Database and media server are built in | Community owners |
| [komm-hub](https://github.com/B077AS/komm-hub) | Accounts, friends, DMs and the CA that vouches for servers | Almost nobody - most people use [kommvoice.com](https://kommvoice.com) |

This repo is what a **community owner** runs. Every message, voice packet and uploaded file lives on your machine; the hub only handles accounts and introductions and **never sees your community's content.**

## Set up your server

Everything is configured from the [Komm](https://github.com/B077AS/komm) desktop client. The server needs no config files, no database setup, no domain name and no reverse proxy.

### 1. Create the installation in the client

Create a new installation in the Komm client: give it a name and pick its ports (the defaults are fine unless something on the machine already uses them). Its card then shows a one-time **verification code** (**Get Verification Code**). You'll paste it in the next step; a code works once.

### 2. Install the server - pick one way

Both installers are on the [latest release](https://github.com/B077AS/komm-server/releases/latest).

**Windows**
1. Install Java 21 if you don't have it, e.g. [Eclipse Temurin 21](https://adoptium.net) with "Set JAVA_HOME" ticked.
2. Run `Komm-Server-Setup.exe` and paste the verification code when asked.

It installs komm-server as a Windows Service (starts at boot, restarts on crash) in `C:\ProgramData\Komm\Server`, and adds a Start-menu shortcut and a tray icon.

**Linux (systemd)**
```bash
sudo apt install openjdk-21-jre-headless          # Java 21 or newer, if you don't have it
curl -LO https://github.com/B077AS/komm-server/releases/latest/download/komm-server-linux-x64.tar.gz
tar xzf komm-server-linux-x64.tar.gz && cd komm-server-linux-x64
sudo ./install.sh --code <your verification code>   # leave --code out to be asked
```
It registers a systemd unit and starts the server in `/opt/komm-server`. Options: `--prefix DIR`, `--data-dir DIR`. `sudo ./uninstall.sh` removes it again (your data stays unless you add `--purge`).

**Docker** (Linux host; nothing to install but Docker, Java is in the image)
```bash
curl -LO https://raw.githubusercontent.com/B077AS/komm-server/main/docker/docker-compose.yml
KOMM_SETUP_TOKEN=<your verification code> docker compose up -d
```
The image is `ghcr.io/b077as/komm-server` (`linux/amd64`). The compose file uses `network_mode: host` so the ports you chose in the client apply directly and voice can advertise the machine's real addresses; on Docker Desktop, turn on its host networking setting first. The code is only read on the first start; after that you can leave it set or remove it.

### 3. Open the ports

If your community connects over the internet, forward these on your router and firewall (use the ports you chose in the client if you changed them):

| Port | Protocol | What for |
|---|---|---|
| **8090** | TCP | the server: REST, WebSocket and voice signaling |
| **7881** | TCP | voice media (fallback) |
| **7882** | UDP | voice media |

Port 7880 is LiveKit's internal signal port: it listens on loopback only and must stay closed.

**Want a domain instead of your IP?** Enter it as the installation's **Public Address** in the client (step 1) and point its DNS straight at your machine: players then connect to the name, which also survives IP changes, and voice advertises the IP the name resolves to. That is all it takes; no proxy is needed.

You can put a proxy in front too (to share one public IP between several servers, or keep the server on a private network), with these rules:

- **Pass TLS through untouched.** Use a TCP proxy (nginx `stream`, HAProxy in tcp mode), not an HTTP one. Clients verify the server's hub-issued certificate, not its hostname, so a proxy that terminates TLS with its own certificate (Cloudflare's proxy, a plain `proxy_pass`, Caddy with Let's Encrypt) is rejected.
- **Keep the port numbers.** The ports come from the client (step 1) and the server binds exactly those, so the proxy listens on the same numbers and has to run on another IP or machine than the server.
- **Voice media needs forwarding too.** An HTTP proxy cannot carry it: forward 7881/TCP and 7882/UDP as well (nginx `stream` does both), with the Public Address pointing at the proxy so that is the IP voice advertises. Every voice packet then takes an extra hop, so pointing the name straight at the server is the simpler choice.

```nginx
stream {
    server { listen 8090;      proxy_pass 10.0.0.5:8090; }   # server port (yours may differ): API, WebSocket, voice signaling
    server { listen 7881;      proxy_pass 10.0.0.5:7881; }   # voice media, TCP
    server { listen 7882 udp;  proxy_pass 10.0.0.5:7882; }   # voice media, UDP
}
```

### 4. That's it

There is nothing left to configure: the server sets itself up on its first start.

- **Certificate and ports:** it activates with the hub, receives its hub-CA certificate and the ports you chose, and serves **HTTPS** straight away. No restart, no certificate to buy or renew.
- **Voice address:** it tells its media server to advertise the address the hub gives players for it: your Public Address, else the IP the hub sees it connect from. That is what makes voice work behind a home router or on a cloud VM whose public IP is NAT-ed (IPv4 only). The hub sends the current address every time the server connects, so a changed home IP is picked up on the next connection, and a Public Address that is a DNS name is looked up again at every start. If the hub sees the wrong IP (say the server reaches it through a VPN), set `KOMM_NODE_IP`.
- **Database, keys, media server:** built in, created on first start.

Your installation turns **online** in the client within seconds. Share an invite link: friends connect **directly** to your server, nothing passes through the hub.

## Everyday use

| | Windows / Linux installer | Docker |
|---|---|---|
| Status | `kommserver status` | `docker compose ps` |
| Logs | `kommserver logs -f` | `docker compose logs -f` |
| Start / stop / restart | `kommserver start \| stop \| restart` | `docker compose start \| stop \| restart` |
| **Update** | `kommserver update` | `docker compose pull && docker compose up -d` |
| Data (back this up) | `data/` in the install folder | the `komm-data` volume (`/data`) |

`kommserver` is installed with the server (on Linux, `start`, `stop`, `update` and `install-service` need `sudo`; on Windows use a Command Prompt opened as Administrator).

**Updates:** `kommserver update` downloads only what changed (usually just the server jar), swaps it in, restarts, and puts the previous version back automatically if the new one fails to start ([jpack](https://github.com/B077AS/jpack) does this). With Docker a new version is a new image; to stay on a version, use its tag (`ghcr.io/b077as/komm-server:0.0.9`) instead of `latest`. Either way your data is never touched and the database migrates itself.

**What's in the data folder:** `komm-postgres-data/` (the database - your messages), `keys/` (the server's key pair and its hub-issued certificate), `uploads/` (attachments, soundboard audio) and `logs/`. The folder can live elsewhere: `install.sh --data-dir`, or the `KOMM_DATA_DIR` environment variable.

## If something goes wrong

A server that cannot finish activating does not crash-loop; it tells you why and waits.

- **"The hub rejected the verification code"** - mistyped, or already used. Copy the code again from the installation's card in the client. Installer: run `kommserver install-service --code <code>` (or put it in `data/setup-token.txt`); the running server picks it up within a second, no restart. Docker: fix `KOMM_SETUP_TOKEN` and run `docker compose up -d`.
- **"cannot reach the hub"** - the hub is down or the machine is offline. The server retries by itself (5 s, then 10, 20 ... up to a minute) and carries on when the hub is back.
- **Docker shows `unhealthy`** - the container is healthy only once the server is up and activated, so this usually means one of the two above. Check `docker compose logs`.
- **Friends connect but voice doesn't work** - the media ports (7881/TCP, 7882/UDP) aren't reachable from outside, or the server advertises the wrong address: look for `[SFU] Advertising ...` in the log and set `KOMM_NODE_IP` if it is wrong.

**Moving to another machine:** stop the server, and on the new machine copy `komm-postgres-data/`, `keys/` and `uploads/` into the data folder *before* installing. The installer finds the certificate, doesn't ask for a code and starts with all your data.

## What the server does

- **Text channels** - rich messages with editing, deletion, reactions, attachments (up to 50 MB), typing indicators and read state
- **Voice & video** - WebRTC audio, camera and screen sharing on an **embedded LiveKit SFU**, end-to-end encrypted
- **Soundboards** - upload sounds per server and trigger them in voice channels
- **Roles & permissions** - custom roles plus per-channel overrides, resolved per user in real time
- **Moderation** - kick, ban, server mute & deafen, move or disconnect members

## How it works

```
┌─────────────────────────── komm-server ───────────────────────────┐
│                                                                   │
│ Spring Boot · port 8090           Embedded PostgreSQL             │
│ REST API + WebSocket              zero-config database            │
│ auth · permissions · uploads      data/komm-postgres-data/        │
│                                                                   │
│ Embedded LiveKit SFU (voice & video)                              │
│ signal 127.0.0.1:7880, proxied at /rtc on port 8090               │
│ media: TCP 7881 · UDP 7882                                        │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

1. **Trust, once:** at its first start the server sends the hub a certificate request and the verification code; the hub's CA signs it. That certificate is both the server's identity towards the hub (X.509 mutual auth) and its HTTPS/WSS certificate. Clients check it against the hub CA and the installation identity inside it, which is stronger than a hostname check.
2. **Joining:** a client gets a short-lived (60 s) single-use ticket from the hub and connects **directly** to your server. The server verifies it against the hub's public key and issues its own tokens (15-minute access, 30-day refresh) signed with a key pair generated locally in `keys/`. From then on the hub is out of the loop.
3. **Voice:** media is encrypted by WebRTC (SRTP/DTLS) and the SFU relays only ciphertext frames; clients exchange keys through the server.

## For developers

```bash
mvn clean package -DskipTests              # the server jar
mvn spring-boot:run                        # run it (everything goes into ./data/)
mvn -f launcher/pom.xml clean package      # the kommserver command (separate project in launcher/)
mvn clean package -DskipTests -Pjpack      # install folder + update files in target/jpack/
docker build -f docker/Dockerfile -t komm-server .   # image around target/komm-server-*.jar
```

- **Running the jar alone:** `java -jar komm-server.jar` (Java 21+) works on its own: it keeps everything in `./data/`, and with the code in `data/setup-token.txt` or `KOMM_SETUP_TOKEN` it activates and serves HTTPS on its first start. It is not a service and does not update itself.
- **A source build points at a local hub** (`http://localhost:8085`, see `api.url` / `websocket.url` in `application.properties`). To develop end to end, run [komm-hub](https://github.com/B077AS/komm-hub) locally and create an installation against it from the client.
- **Activation** happens in `PreBootActivation`, before Spring starts, so Tomcat is configured with the certificate and ports on the very first boot. A rejected code or an unreachable hub makes it wait (state in `activation.status`), never exit; `StartupValidator` then records the installation. The container health check is the `ready` file, which exists only while the server is up and activated.
- **Voice address:** the hub's activation answer and an `ADDRESS_UPDATE` message on every connect carry the address players use; `SfuLauncher` writes it as LiveKit's `node_ip` (IPv4 only; `KOMM_NODE_IP` wins) and restarts LiveKit when it changes.
- **Releases** are built by GitHub Actions (`.github/workflows/release.yml`) when a release is published: the version comes from the tag, the hub URLs are switched to production, the jars are built once, and jobs package them with jpack for Linux and Windows and push the Docker image. Settings: [`src/jpack/jpack.json`](src/jpack/jpack.json), installers: [`packaging/`](packaging/), image: [`docker/`](docker/).
- **Permissions:** channels have two layers, role-based (`ChannelRolePermission`) and per-user overrides (`ChannelUserPermission`); `PermissionService` merges them, cached for 60 s.
- **Stack:** Java 21, Spring Boot 4, embedded PostgreSQL ([zonky](https://github.com/zonkyio/embedded-postgres)), embedded [LiveKit](https://livekit.io), JJWT + BouncyCastle, Caffeine, Lombok.

Defaults live in `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8090` | REST + WebSocket port |
| `sfu.signal-port` / `sfu.tcp-port` / `sfu.media-port` | `7880` / `7881` / `7882` | LiveKit ports; the signal port is loopback-only and proxied at `/rtc` |
| `sfu.node-ip` (env `KOMM_NODE_IP`) | empty | The address LiveKit advertises for voice; empty means what the hub gives players for this server |
| `api.url` / `websocket.url` | `localhost:8085` | The hub this server registers with |
| `komm.data-dir` (env `KOMM_DATA_DIR`) | `./data` | The data folder; installs set it to `<install>/data`, the image to `/data` |
| `komm.setup-token.file` (env `KOMM_SETUP_TOKEN`) | `setup-token.txt` | Where the verification code is read from on the first start |
| `komm.tls.cert-file` | `keys/tls-cert.pem` | Hub-issued certificate; its presence means "activated" and the server serves HTTPS/WSS |
| `jwt.access-token.expiration` / `jwt.refresh-token.expiration` | `900` / `2592000` | Token lifetimes in seconds |
| `spring.servlet.multipart.max-file-size` | `50MB` | Attachment size limit |

## FAQ

**Do I need to know anything about databases or WebRTC?** No. The database and the media server are built in: create the installation in the client, install, done.

**Can the hub read my community's messages?** No. Clients connect directly to your server after a one-time ticket exchange; messages, voice and files never pass through the hub.

**Is traffic encrypted?** Yes: HTTPS/WSS with a certificate the hub CA signs for exactly your installation, and voice is additionally encrypted end to end.

## License

This project is licensed under the [MIT License](LICENSE).
