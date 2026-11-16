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

Komm is a modern chat platform built around a simple idea: **your community's messages and voice traffic belong on hardware you control.** Every community runs on its own self-hosted server - crystal-clear WebRTC voice channels, HD screen sharing, rich messaging, soundboards, roles & permissions, moderation tools and global hotkeys - without handing your conversations to anyone else. Free, no ads, no tracking, on Windows 10/11 and Linux (both X11 and Wayland, with native PipeWire support).

The platform has three pieces - you choose how many to run:

| Piece | Role | Who runs it |
|---|---|---|
| [komm](https://github.com/B077AS/komm) | Desktop client for Windows & Linux, plus the launcher that installs and updates it (same repo) | Everyone |
| **komm-server** (this repo: the server + its `kommserver` command in [`launcher/`](launcher/)) | A community's own server: channels, messages, voice rooms, permissions. Embedded database, installed as an OS service | Community owners |
| [komm-hub](https://github.com/B077AS/komm-hub) | The network's directory: accounts, friends, DMs, and the CA that vouches for servers | Almost nobody - most people use [kommvoice.com](https://kommvoice.com) |

This repo is the piece a **community owner** runs. Everything that happens inside your community - every message, every voice packet, every uploaded file - lives here, on your machine. The hub only handles accounts and introductions; **it never sees your community's content.**

## Spin up a community in minutes

You don't clone this repo to host a server - the server jar itself is generic and unconfigured; everything installation-specific is set up from the **Komm desktop client**, not a website:

1. **Create your installation in the [Komm](https://github.com/B077AS/komm) client** - name it, pick your ports, and the client generates a P-384 CSR for it locally. This gets you a one-time verification code, shown on that installation's card (**Get Verification Code**, while it's still unverified).
2. **Install the server** - run the Windows installer or the Linux `install.sh` from the [latest release](https://github.com/B077AS/komm-server/releases/latest) (see [Running your server](#running-your-server)). It registers komm-server as an OS service and asks for the verification code. Besides Java 21, there is nothing else to install: PostgreSQL and the LiveKit media server are embedded - no database setup, no config files, no reverse proxy.
3. **It verifies itself** - on first boot the server presents that setup token and its CSR to the hub; the hub's built-in CA signs the certificate and the installation goes **online** over a persistent WebSocket. That certificate is also your server's **TLS identity**: from the next start it serves HTTPS/WSS automatically - no domain name, no certbot, no reverse proxy.
4. **Invite your people** - share an invite link. Friends connect **directly** to your server; messages and voice never pass through the hub.

## What the server does

- **Text channels** - rich messages with editing, deletion, reactions, file attachments (up to 50 MB), typing indicators and per-channel read state
- **Voice & video channels** - WebRTC audio, camera and HD screen sharing, powered by an **embedded LiveKit SFU** that the server manages as a child process
- **Soundboards** - upload sounds per server and trigger them in voice channels
- **Roles & permissions** - custom roles plus per-channel overrides, resolved per user in real time (see [Permission model](#permission-model))
- **Moderation** - kick, ban/unban, server mute & deafen, move members between voice channels, disconnect users
- **Connection insight** - live ping/latency reporting per user

## Architecture

**Trust bootstrap** (once, when this server is created - see [Spin up a community in minutes](#spin-up-a-community-in-minutes) above):

```
┌────────────────┐                                  ┌────────────────┐
│  Komm Server   │ ───────────────────────────────► │    komm-hub    │
│  (this repo)   │ ◄─────────────────────────────── │       CA       │
└────────────────┘                                  └────────────────┘
```

**Runtime** (every session):

```
┌────────────┐     ticket + TLS      ┌───────────────┐
│   Client   │ ────────────────────► │  Komm Server  │  ← channels, messages,
│ (Komm app) │ (CN-pinned to hub CA) │  (this repo)  │    voice, files, permissions
└─────┬──────┘                       └───────┬───────┘
      │ account, friends, DMs                │ X.509 mutual auth (mTLS)
      ▼                                      ▼
┌──────────────────────────────────────────────┐
│              komm-hub  ·  CA                 │  ← accounts, friends, DMs,
│  accounts · directory · certificate signing  │    directory, website
└──────────────────────────────────────────────┘
```

**How a client joins your server:**

1. The client asks the hub for a ticket. The hub verifies membership, checks that your installation is online and its certificate is not revoked, and issues a short-lived (**60 s**) single-purpose JWT ticket.
2. The client connects **directly** to your server with that ticket. `AuthService` verifies the ticket against the hub's public key (stored locally in the `Installation` table) and `UsedTicketStore` guarantees it can never be replayed.
3. On success, the server issues **its own** tokens: a 15-minute access token and a 30-day refresh token, signed with an EC key pair the server generates locally in `data/keys/` on first boot. From this point the hub is out of the loop entirely.

**Everything in one process:**

```
┌──────────────────────────── komm-server JAR ────────────────────────────┐
│                                                                         │
│  Spring Boot (port 8090)          Embedded PostgreSQL (port 5555)       │
│  REST API · WebSocket events      zero-config, data in                  │
│  auth · permissions · uploads     data/komm-postgres-data/              │
│                                                                         │
│  Embedded LiveKit SFU - extracted & launched for your OS                │
│  signal 7880 · TCP fallback 7881 · media (UDP) 7882                     │
└─────────────────────────────────────────────────────────────────────────┘
```

Startup order matters and is handled for you: the embedded PostgreSQL comes up first (schema auto-managed by Hibernate), the JWT keys are loaded or generated, then `SfuLauncher` extracts the LiveKit binary bundled for your OS (Windows and Linux binaries both ship inside the JAR) and starts it, and finally the REST + WebSocket layer opens on port 8090.

### Key subsystems

| Package | Responsibility |
|---|---|
| `controller/` | REST endpoints: auth, servers, channels, messages, members, permissions, soundboards |
| `service/` | Business logic: messaging, reactions, bans/kicks, permission resolution, attachment cleanup |
| `websocket/` | Real-time events - `handlers/` dispatch incoming frames, `senders/` push to clients; separate session managers for app clients and the hub link |
| `sfu/` | Manages the embedded LiveKit process and mints per-user room tokens (`LiveKitTokenService`) |
| `security/` | JWT creation/validation (jjwt + BouncyCastle), `JwtAuthFilter`, `UsedTicketStore` (ticket replay prevention) |
| `model/db/` | JPA entities: `Server`, `Channel`, `Message`, `ServerMember`, roles, permissions, bans, soundboards, … |

### Permission model

Channels have two independent permission layers:

- **Role-based** - `ChannelRolePermission` applies to every member holding a `ServerCustomRole`
- **User-specific** - `ChannelUserPermission` overrides for individual users

`PermissionService` merges both layers into the effective permission set for each user/channel pair, with a Caffeine cache (60 s TTL) keeping hot lookups instant. Permission changes propagate to connected clients over WebSocket immediately.

## Security model

- **X.509 mutual authentication** - your server proves its identity to the hub with a certificate signed by the hub's CA (P-384 elliptic curve), obtained automatically on first boot.
- **Automatic TLS** - the hub-signed certificate doubles as your server's HTTPS/WSS certificate. Clients validate it against the hub CA and check the installation identity embedded in it - stronger than a hostname check, since a hijacked IP or DNS entry can't present a valid certificate for your installation. Voice media is independently encrypted by WebRTC (SRTP/DTLS).
- **Short-lived connection tickets** - clients join with single-purpose 60-second JWT tickets issued by the hub; `UsedTicketStore` makes each one strictly single-use.
- **Local ES-signed sessions** - after joining, sessions use the server's own locally generated EC key pair. No shared secrets, nothing to leak.
- **Data stays with you** - messages, voice, files and permissions never leave your machine. The hub cannot read any of it.

## Requirements

- **Java 21 or newer, installed on the machine** - that's it (no Java is bundled; the server uses `JAVA_HOME`, else `java` on the `PATH`). PostgreSQL is embedded, the LiveKit media server is embedded, and the certificate exchange is automatic. On Ubuntu/Debian: `sudo apt install openjdk-21-jre-headless`; on Windows e.g. [Eclipse Temurin 21](https://adoptium.net) with "Set JAVA_HOME" ticked.
- Reachability: forward/open port **8090** (REST + WebSocket) and the media ports **7880** (signal), **7881** (TCP fallback) and **7882/UDP** (media) if your community connects over the internet.

## Running your server

komm-server is installed as a real OS service (a Windows Service, or a systemd unit on Linux): it starts at boot, restarts on crash, and updates in place with one command. Both downloads are on this repo's [latest release](https://github.com/B077AS/komm-server/releases/latest).

**Windows:** run `Komm-Server-Setup.exe`. It installs into one folder (default `C:\ProgramData\Komm\Server`), asks for your verification code, registers the service and starts it. A Start-menu shortcut and a tray icon give you the `kommserver` command and the service's status.

**Linux:**
```bash
curl -LO https://github.com/B077AS/komm-server/releases/latest/download/komm-server-linux-x64.tar.gz
tar xzf komm-server-linux-x64.tar.gz
cd komm-server-linux-x64
sudo ./install.sh                  # options: --prefix DIR (default /opt/komm-server), --data-dir DIR, --code CODE
```
It asks for your verification code, registers the systemd unit and starts the server. Everything lives in the install folder; the only file outside it is the `kommserver` command in `/usr/local/bin`. `sudo ./uninstall.sh` in the install folder removes it again (your data stays unless you add `--purge`).

### The install folder

```
komm-server(.exe)          the jpack stub: the service runs it, it starts the server
jpack.json                 what is installed (version, parts)
app/  launcher/            the server jar and the kommserver command - replaced by updates
data/                      everything the server writes - back this up
  komm-postgres-data/      the embedded PostgreSQL data: your messages live here
  keys/                    the server's EC key pair and its hub-issued TLS certificate (tls-cert.pem)
  uploads/                 file attachments and soundboard audio
  logs/                    rolling logs (10 MB per file, 30-day retention, 1 GB cap)
  sfu/  pg-runtime/        the LiveKit and PostgreSQL programs, unpacked again at every start
```

The data folder can live elsewhere: `install.sh --data-dir` on Linux, or the `KOMM_DATA_DIR` environment variable.

### The `kommserver` command

```
kommserver status              is it running, which version, where are the files
kommserver update              download the newest release and restart the server on it
kommserver start | stop | restart
kommserver logs [-f]           the server log (-f keeps following it)
kommserver install-service     register the OS service again (safe to repeat)
kommserver uninstall-service   remove the service (files and data stay)
```
On Linux, `start`, `stop`, `update` and `install-service` need `sudo`; on Windows, a Command Prompt opened as Administrator.

### Updates

`kommserver update` is the whole update. Installs are built with [jpack](https://github.com/B077AS/jpack): it reads this repo's latest release, downloads only the parts that changed (usually just the server jar), verifies each by SHA-256, stops the server, swaps the new files in, starts it again and waits until it answers. If the new version fails at its first start, the previous one is put back automatically. Your data folder is never touched.

### Running the jar directly

For development or a quick test you can also run the plain `komm-server.jar` from the release yourself (`java -jar komm-server.jar`, Java 21+). It keeps everything in a `data/` folder in the directory you start it from; put your verification code into `data/setup-token.txt` first. A jar run this way is not a service and does not update itself.

## Building from source (developers)

```bash
# Build the server jar
mvn clean package -DskipTests

# Run (writes everything into ./data/)
mvn spring-boot:run

# The kommserver command (separate Maven project in launcher/)
mvn -f launcher/pom.xml clean package

# The installable folder + update files (needs the launcher jar above)
mvn clean package -DskipTests -Pjpack
# -> target/jpack/image/<platform>/   the install folder (stub, jpack.json, app/, launcher/)
#    target/jpack/release/            the files updates are served from
```

Note that a source build has no setup token baked in - a real one is generated when you create an installation in the [Komm](https://github.com/B077AS/komm) client and is supplied at runtime via `data/setup-token.txt` (see `komm.setup-token.file` below), never baked into the jar. To develop end-to-end, run a local [komm-hub](https://github.com/B077AS/komm-hub) (default `http://localhost:8085`, matching `api.url` / `websocket.url` in `application.properties`) and create an installation against it from the client.

The install settings (no bundled Java, the server as the start entry, the `kommserver` command as a console entry, `-Dkomm.data-dir=<install>/data`) are in [`src/jpack/jpack.json`](src/jpack/jpack.json); the installers are in [`packaging/`](packaging/) (`install.sh` / `uninstall.sh` for Linux, an Inno Setup script for Windows).

**Official releases** are built automatically by GitHub Actions (`.github/workflows/release.yml`) whenever a release is published: the project version is stamped from the release tag, the hub URLs are switched from the localhost development defaults to the production hub at `kommvoice.com`, and the server and launcher jars are built once. A Linux and a Windows job then package those same jars with jpack and attach to the release every update file (the manifest `jpack-<platform>.json` last), `komm-server-linux-x64.tar.gz`, `Komm-Server-Setup.exe` and the plain `komm-server.jar`. Download names carry no version, so `releases/latest/download/...` links never change.

### Configuration at a glance

Everything ships with working defaults in `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8090` | REST + WebSocket port |
| `sfu.signal-port` / `sfu.tcp-port` / `sfu.media-port` | `7880` / `7881` / `7882` | Embedded LiveKit ports |
| `api.url` / `websocket.url` | `localhost:8085` | The hub this server registers with |
| `jwt.access-token.expiration` | `900` (15 min) | Client access-token lifetime (seconds) |
| `jwt.refresh-token.expiration` | `2592000` (30 days) | Refresh-token lifetime (seconds) |
| `komm.data-dir` (or env `KOMM_DATA_DIR`) | `./data` | The data folder; every file path below is relative to it. Installs set it to `<install>/data` |
| `komm.tls.cert-file` | `keys/tls-cert.pem` | Hub-issued TLS certificate; when present the server boots in HTTPS/WSS mode (delete to fall back to plain HTTP) |
| `komm.attachments.base-path` / `komm.soundboards.base-path` | `uploads/…` | Upload storage |
| `spring.servlet.multipart.max-file-size` | `50MB` | Attachment size limit |

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4 (Web MVC, Security, WebSocket, Data JPA, Validation, Cache) |
| Database | Embedded PostgreSQL ([io.zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres)) - zero configuration, schema auto-managed by Hibernate |
| Media | Embedded [LiveKit](https://livekit.io) SFU (Windows & Linux binaries bundled) + LiveKit server SDK for room tokens |
| Auth & crypto | JJWT (EC-signed JWTs), BouncyCastle |
| Caching | Caffeine (permission resolution) |
| Misc | Lombok, Gson, Commons IO/Lang |

## Related repositories

| Repo | What it is |
|---|---|
| [komm](https://github.com/B077AS/komm) | Desktop client (JavaFX, Windows & Linux) - where you create and manage installations - and its auto-updating launcher (Windows installer & Linux AppImage) |
| komm-server | This repo - self-hosted community server (embedded database) and its `kommserver` command - Windows installer & Linux tarball |
| [komm-hub](https://github.com/B077AS/komm-hub) | Accounts, friends & DMs, server directory, CA, and the kommvoice.com website |

## FAQ

**Do I need to know anything about databases or WebRTC to host a server?** No. The database and the media server are embedded - create the installation in the Komm client, run the installer, done.

**Can the hub read my community's messages?** No. Clients connect directly to your server after a one-time ticket exchange; messages, voice and files never pass through the hub.

**Is traffic to my server encrypted?** Yes. Your server serves HTTPS/WSS with a certificate the hub CA signs automatically at setup - clients verify it belongs to exactly your installation. No domain or certificate purchase needed. Voice audio is additionally encrypted by WebRTC (SRTP).

**Can I move my server to another machine?** Yes - stop it, and on the new machine copy `komm-postgres-data/`, `keys/` and `uploads/` into the data folder the install will use (e.g. `/opt/komm-server/data/`, or `C:\ProgramData\Komm\Server\data\`) *before* installing. The installer then finds the certificate, doesn't ask for a code, and starts your server with all its data.

**How do updates work?** Run `kommserver update`: it downloads only what changed, restarts the server on the new version, and puts the old one back if the new one fails to start. Your data folder is never touched. See [Updates](#updates).

## License

This project is licensed under the [MIT License](LICENSE).
