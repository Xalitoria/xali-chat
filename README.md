# XaliChat

**XaliChat** is a chat plugin for Paper and Folia. It provides formatted chat, private messages, nicknames, ignore and
silence settings, command and social spying, chat logging, interactive chat tokens, scheduled announcements and an
optional DiscordSRV 3.0 (Ascension) integration. A small Velocity plugin relays what the servers send each other.

## Installation

### Requirements

| Plugin | Needed | Purpose |
|---|---|---|
| PlaceholderAPI | Yes | Placeholders in chat, messages and announcements. |
| SignedVelocity | Yes | Keeps signed chat working behind Velocity. |
| ProtocolLib | No | Colored tab list, scoreboard and signs, and chat signing (`protocolor` in `config.yml`). |
| Vault, VaultUnlocked | No | Prefix, suffix and group for the chat format. |
| DiscordSRV-Ascension | No | Chat bridge to Discord. |
| Plan | No | Chat, command and private message log on Plan's pages, see [Plan](#plan). On a network, Plan on the proxy serves it. |
| SuperVanish, PremiumVanish | No | Vanished players are hidden from player lists and private messages. |
| Essentials | No | Loaded after XaliChat; plain labels it owns are taken over, see [Commands](#commands). |

### Modules

| Module | Description |
|---|---|
| `chat/paper` | The Paper plugin, `xali-chat-<version>-paper.jar`. Everything below is this module unless it says otherwise. |
| `chat/velocity` | The Velocity plugin, `xali-chat-<version>-velocity.jar`: the [relay](#velocity-relay), and the [log page](#on-a-network-plan-on-the-proxy) when Plan's web UI runs on the proxy. |
| `chat/api` | What other Paper plugins compile against to take part in XaliChat, see [Working with other plugins](#working-with-other-plugins). Shaded into the Paper jar. |
| `chat/common` | What both plugins share: the wire format (signed frames, chunking, message encodings), the announcement sync, and the log tables, queries and Plan log page. No platform API; both jars bundle it. Targets Java 17. |

How the modules fit together is under [Architecture](#architecture).

### Building and installing

```console
user $ ./gradlew :chat:paper:shadowJar :chat:velocity:shadowJar
```

Copy `build/libs/chat/xali-chat-<version>-paper.jar` into each server's `plugins` folder and
`build/libs/chat/xali-chat-<version>-velocity.jar` into the proxy's `plugins` folder.

> [!NOTE]
> The Paper plugin's libraries (the database drivers, Exposed, Jedis and the Xali utility library) are downloaded by
> Paper when the plugin loads, so a server needs internet access on its first start.
>
> The Velocity plugin downloads its libraries (kaml, kotlinx.serialization, Jedis, and Exposed with the database drivers
> for the [log page](#on-a-network-plan-on-the-proxy)) into `plugins/xalichat/libraries` the first time it starts. The
> checksums are built into the jar. The proxy needs internet access once.

For a dev server, `./gradlew :chat:paper:runServer` and `./gradlew :chat:velocity:runProxy` start the shared Folia server
and Velocity proxy with this plugin installed (see the [root README](../README.md#dev-servers)). The dev proxy still
needs its `velocity.toml` pointed at the server and `player-info-forwarding-mode = "modern"`.

The descriptors and the library loader come from the [Gradle plugin](../xali-gradle/README.md); the `paper { }` and
`velocity { }` blocks are in `chat/build.gradle.kts`.

## Configuration

### Files

| File | Holds |
|---|---|
| `plugins/XaliChat/config.yml` | Main settings, see [below](#configyml). Created on the first start. |
| `plugins/XaliChat/language/<language>.yml` | Every message, written with the defaults on the first start. `language` in `config.yml` picks the file (`en-us`). |
| `plugins/XaliChat/announcements.yml` | [Announcements](#announcements). |
| `plugins/XaliChat/announcements.state.json` | Save times of the announcements. Keep it with the file. |
| `plugins/XaliChat/xalichat.db` | The database when `database.type` is `sqlite` (the file name is `database.sqlite.filename`). |
| `plugins/XaliChat/nicknames.db` | Nicknames, when `database` is shared (MariaDB or MySQL) and `nickname.remote` is off. |
| `plugins/XaliChat/exports/` | Output of `/chatlog export`. |
| `plugins/xalichat/config.yml` (proxy) | Settings of the [Velocity relay](#velocity-relay), and of the [log page](#on-a-network-plan-on-the-proxy) there. |
| `plugins/xalichat/announcements.yml`, `announcements.state.json`, `libraries/` (proxy) | The master announcements with their save times, and the libraries the proxy plugin downloaded. |

`/xalichat reload` reads the configuration, the messages and `announcements.yml` again. On the proxy, `/xalichatv reload`
reloads the proxy's own plugin and `/xalichatv greload` the proxy and every server, see [Velocity relay](#velocity-relay).

### config.yml

| Key | Default | Meaning |
|---|---|---|
| `debug` | `false` | Verbose logging. |
| `language` | `en-us` | Name of the language file. |
| `chat-format` | `<{0}> {1}` | `{0}` is the name, `{1}` the message. `{vault_prefix}`, `{vault_suffix}`, `{vault_group}` and PlaceholderAPI placeholders work. |
| `interactive-chat` | `true` | Expand [`[item]`, `[inv]`, `[ender]` and `[ping]`](#interactive-chat). Applies on reload. |
| `protocolor` | `true` | ProtocolLib packet listeners. Turn off to rule out a ProtocolLib conflict without removing the plugin. Applies on reload. |
| `cross-server-messages` | `true` | Private messages to and from other servers behind the proxy, see [Velocity relay](#velocity-relay). |
| `database` | SQLite | `type` (`sqlite`, `mysql` or `mariadb`), `table-prefix` (`xalichat_`; a new prefix means new, empty tables), `server-name`, and the `sqlite` and `mysql` connection settings. Servers that share a database need different `server-name` values. |
| `redis` | off | Announcement sharing, see [Redis](#redis). |
| `nickname` | on | `enable`, `remote` (keep nicknames in the database), `max-length` (16, at most 64), `unique`. |
| `characters` | `allow` | Limits chat to [basic characters](#limiting-characters): `allow`, `alert`, `strip` or `block`. |
| `plan` | on | See [Plan](#plan). |
| `logging` | database | `chat.database`, `commands.database` and `commands.ignored-commands`. Without the database, entries go to the server log as `CHATLOG|...` and `CMDLOG|...` lines. `messages.enable` (off) also records private messages, in the database only. |

`database.type: mariadb` uses MariaDB's driver and `mysql` MySQL's. The MySQL driver cannot read MariaDB 11's metadata,
so use `mariadb` for a MariaDB server.

### Limiting characters

`characters` limits chat to what a basic keyboard types: ASCII letters, numbers, symbols and spaces (`U+0020` to
`U+007E`). Emoji, accents, other alphabets, tabs, line breaks and `§` are outside it.

| Key | Default | Meaning |
|---|---|---|
| `mode` | `allow` | What happens to a message with characters outside the set: |
| | | `allow`: nothing, the limit is off. |
| | | `alert`: the message is sent as it is; the console and players with `xalichat.admin.alert.characters` are told who used which characters. |
| | | `strip`: the characters are removed and the rest is sent, and the sender is told. A message with nothing left is refused. |
| | | `block`: the message is refused and the sender is told which characters are not allowed. |
| `messages` | `true` | The same applies to private messages (`/msg`, `/reply`). The console is never limited. |
| `allow` | empty | Characters allowed on top, written together: `"éñü"` for accents. |

A mode that is none of the four is reported on reload and treated as `allow`. The settings apply on reload.

**What is never limited**

- **Other plugins' characters.** The check is made on what the player typed, not on what plugins made of it, so a
  glyph that Nexo, ItemsAdder or an emoji plugin swaps in for `:name:` never counts. Private-use characters, which those
  plugins draw their glyphs from, are allowed wherever they come from. Under `strip`, only the characters the player typed
  are removed, and the message keeps its colors and events.
- **Operators**, and players with `xalichat.bypass.characters`.

An alert names the player and the characters but not the message, as it may be private. What was typed is still written
to the chat log when a message is refused or stripped. The messages are `commands.characters.blocked`, `stripped`,
`alert-chat` and `alert-message` in the language file. The older phrase filter (`filter.yml`) is switched off: it is not
loaded, and no file is created.

### Formatting syntax

Chat and messages accept legacy `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage tags, each
limited by the [format permissions](#formatting).

## Usage

### Commands

Every command's main label starts with `x`. Each alias works with and without the `x` (`/xbroadcast`, `/broadcast`,
`/xbc`, `/bc`), and each command is also a subcommand of `/xalichat` under its name without the `x`
(`/xalichat broadcast`, `/xalichat bc`). The tables list the main label and the aliases without the `x`.

When another plugin (EssentialsX, for one) already owns a plain label such as `/reply` or `/nickname`, XaliChat takes it
over at startup and logs which labels it took. `/xsilence` has no `/mute` alias, so punishment plugins keep `/mute`.

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/xalichat reload` | `chat` | Reload the config, messages and `announcements.yml`; the announcements that run are the ones in the file now. | `xalichat.reload` |
| `/xmessage <target> [message]` | `message`, `msg` | Send a private message to a player on any server behind the proxy. | `xalichat.message` |
| `/xreply <message>` | `reply`, `r` | Answer the last player exchanging private messages with the sender, on any server. Forgotten when the sender leaves the server. | `xalichat.message` |
| `/xspam say <repeat> <message>` | `spam` | Send a message repeatedly. | `xalichat.spam` |
| `/xspam stop` | | Stop the repeating message. | `xalichat.spam` |
| `/xignore <player>` | `ignore` | Toggle ignoring a player's chat and messages. | `xalichat.ignore` |
| `/xsilence players` | `silence` | Toggle hiding global chat from other players (a personal setting). | `xalichat.setting.mute.players` |
| `/xsilence broadcasts` | `silence` | Toggle hiding broadcasts and announcements (a personal setting). | `xalichat.setting.mute.broadcasts` |
| `/xcommandspy` | `commandspy` | Toggle seeing other players' commands. | `xalichat.admin.spy.command` |
| `/xsocialspy` | `socialspy` | Toggle seeing private messages. | `xalichat.admin.spy.social` |
| `/xnickname <nickname\|reset>` | `nickname`, `nick` | Set or clear the sender's nickname. | `xalichat.command.nickname` |
| `/xnickname <player\|uuid> <nickname\|reset>` | `nickname`, `nick` | Set or clear another player's nickname, offline players included. | `xalichat.command.nickname.others` |
| `/xbroadcast <message>` | `broadcast`, `bcast`, `bc` | Announce to every online player on this server. The console gets a line saying who sent it and to how many, not the message. | `xalichat.admin.broadcast` |
| `/xgbroadcast <message>` | `gbroadcast`, `gbcast`, `gbc` | The same on every server behind the proxy. | `xalichat.admin.broadcast.global` |
| `/xannounce <announcement> [player]` | `announce`, `ann` | Play an [announcement](#announcements) on this server, for that player if one is given. | `xalichat.admin.announce` |
| `/xgannounce <announcement> [player]` | `gannounce`, `gann` | Play it on every server its `servers` list names. | `xalichat.admin.announce.global` |
| `/xview <id>` | `view` | Open an item, inventory or ender chest snapshot from chat. Used by the clickable tokens. | none |

The `g` commands (global) travel through the proxy, so they need [Velocity forwarding](#proxy-sync) and a player online
to carry the message. Without them they run on this server only and say so.

`/xbroadcast` wraps the message in `commands.broadcast.format` of the language file. The sender's formatting is limited
by `xalichat.format.broadcast.*` (see [Formatting](#formatting)): a tag the sender may not use is removed, and so is a
code (`&c` without `.color`). Announcements are written by administrators and use everything. Broadcasts and
announcements parse with their own `Colorizer`, not the shared color parser, so CMI gradients (`{#a0a>}`) are not
supported there; use `<gradient:#a0a:#fff>` instead.

### Searching the chat log

`/chatlog` searches logged chat. Every filter is optional, and filters combine.

| Subcommand | Description | Permission |
|---|---|---|
| `/chatlog [player:<name\|uuid>] [since:<dur>] [text:<word>] [server:<name\|*>] [page:<n>]` | Search and page through logged chat, 9 entries per page. `since` takes durations such as `30m`, `2h` or `1d`. `server` defaults to this server and `*` searches all. `page:<n>` alone reuses the last search for 5 minutes. | `xalichat.admin.chatlog` |
| `/chatlog help` | Show usage. | `xalichat.admin.chatlog` |
| `/chatlog export <filename> [filters]` | Write the matching entries as plain text to `plugins/XaliChat/exports/<filename>.txt`. Takes the same filters as a search, except `page`. | `xalichat.admin.chatlog.export` |
| `/chatlog import <filename>` | View a previously exported file in the chat menu. Read-only: it never writes to the stored logs. | `xalichat.admin.chatlog.import` |

Export lines look like `2026-09-26 14:03:11 | <uuid> | <username> | <message>`. Filenames may only contain letters,
numbers, `.`, `_` and `-`, so they always stay inside the `exports` folder.

### Interactive chat

Typed tokens in chat are replaced with hoverable, clickable components. `interactive-chat: false` in `config.yml`
disables the feature.

| Token | Shows | Permission |
|---|---|---|
| `[item]` | The item in the main hand. | `xalichat.interactive.item` |
| `[inv]` | A snapshot of the inventory (`/xview`). | `xalichat.interactive.inventory` |
| `[ender]` | A snapshot of the ender chest (`/xview`). | `xalichat.interactive.ender` |
| `[ping]` | The latency. | `xalichat.interactive.ping` |

With DiscordSRV installed, the tokens are sent to Discord as plain text, MiniMessage tags and colors are stripped from
outgoing messages, and colors in Discord messages are limited by `xalichat.format.discord` of the linked player.
Unlinked users get none.

## Announcements

`plugins/XaliChat/announcements.yml` maps a key to an announcement. The proxy's copy is the master. It is created on the
proxy's first start with two switched-off examples. A server's file holds only the keys that name it. A server without a
file reads what Redis holds first and creates its file from that, empty if no key names it. With Redis off, it writes an
empty file at once.

**File** `announcements.yml`

```yaml
"discord":
  enable: true # default
  delay: 300 # seconds
  repeat: 1800 # seconds
  actions:
  - message:
    - "========================================"
    - ""
    - "Join the Discord: https://discord.gg/rosaic"
    - ""
    - "========================================"
  - command: "playsound @a pling"
  - delay: 2s 10t # 2.5 seconds total
  - command: "another-command"
"rank-titan":
  servers:
  - "skyblock"
  actions:
  - condition: "%discordsrv_linked%"
    'true':
    - message: "%player_name% purchased Titan rank!"
    - condition: '%luckperms_group% != "titan"'
      'true':
      - command: "lp user {uuid} parent add titan"
      - command: "playsound minecraft:entity.player.levelup master @a"
      'false':
      - message: "You already have this rank"
    'false':
    - message:
      - "&cLink your Discord first"
      - "<click:open_url:'https://discord.gg/rosaic'>Join here</click>"
```

| Key | Default | Meaning |
|---|---|---|
| `enable` | `true` | `false` switches it off everywhere, including the commands. |
| `delay` | `0` | Seconds before the first automatic run. |
| `repeat` | `0` | Seconds between automatic runs; `0` runs it once. |
| `servers` | all | The servers that hold and run it, matched against their names in `velocity.toml` with `*` and `?` wildcards (`skyblock-*`). A server's `database.server-name` in `config.yml` must be the same name. Empty means every server. `/xannounce` ignores the list. |
| `actions` | none | What it does, in order, see [Actions](#actions). |

An announcement with a `delay` or `repeat` runs by itself on every server that matches `servers`. One with neither runs
only from `/xannounce` or `/xgannounce`.

Saving `announcements.yml` does not change what runs: the announcements and their timers are the ones from the last
start or reload, so an edit is safe to leave half done. `/xalichat reload` runs the file on that server, and
`/xalichatv greload` on every server at once. Saving still [shares](#how-announcements-are-shared) the change.

`/xannounce <announcement> [player]` runs it for the player, if one is given. Without one it runs for nobody in
particular, like a timer does.

#### Actions

`actions` is a list; each item is a map with exactly one of these keys, and they run top to bottom.

| Key | Value | Does |
|---|---|---|
| `message` | A line, or a list of lines | Sends chat lines to every online player who has not muted broadcasts, and to the console (unlike broadcasts, whose text the console does not repeat). `""` is a blank line. They accept `&` codes, `&#RRGGBB` and MiniMessage tags (`<gradient:red:blue>`, `<click:open_url:...>`). |
| `command` | A command | Runs it as the console, a leading `/` is optional. |
| `delay` | A time | Pauses the rest of the list. Tokens are a number and a unit, added together: `t` ticks, `ms`, `s`, `m`, `h`, `d`. A bare number is seconds, so `1` is a second and `2s 10t` is 2.5 seconds. |
| `condition` | A test | Runs the actions under `'true'` if it holds and those under `'false'` if it does not. Either can be left out or empty; both can hold more conditions. |

A file with an action that has none of these keys (a typo, say), more than one, or a bad `delay` fails to parse, like any
other invalid YAML.

- **Conditions** are a value that is true or false by itself, or two values with an operator between them. Placeholders
  are expanded first. `true`, `yes`, `on` and `1` (in any case) are true; everything else is false, including a
  placeholder that did not resolve. The operators are `==`, `!=`, `>=`, `<=`, `>` and `<`. Two numbers are compared as
  numbers; otherwise `==` and `!=` compare the text exactly and the others are false. Quotes around a value are dropped
  (`%luckperms_group% != "titan"`), and the operator is found in the text as you wrote it, so a placeholder whose value
  contains `==` does not change the test.
- **Placeholders** resolve for the player the announcement runs for: `{name}`, `{uuid}` (dashed), `%player_name%` and
  any PlaceholderAPI placeholder, in messages, commands and conditions. With no player (a timer, or no player given to
  the command), each viewer of a message gets their own, but commands and conditions see no player, so a player
  placeholder in them stays as written. Text from `/xbroadcast` is never expanded.
- Keys should be single words, since the commands take them as one argument.

> [!NOTE]
> The older format with `messages:` and `commands:` lists is still read: the messages become one `message` action and the
> commands follow it as `command` actions, a `delay 2s` command becoming a `delay` action. A file is rewritten with
> `actions` the next time the plugin writes it (a Redis update); until then it is read as written.

### How announcements are shared

Announcements are shared through [Redis](#redis), so the servers and the proxy agree on one set without a player online.
The proxy's `announcements.yml` holds every announcement; a server's file holds only the ones whose `servers` name it. An
announcement can be edited on any server, or in the master on the proxy, and ends up where it belongs:

- **Saving the file** offers each key that changed (new, edited or deleted) to Redis, which tells everyone else. Each
  server the key names writes it into its own file. Nothing is reloaded by it: the new announcements run after a
  `/xalichat reload` there, or a `/xalichatv greload` for every server. Only keys whose content changed count; comments
  and layout do not. Every update that changes something is logged, with or without `debug`:
  `Synced announcements.yml from Redis: updated a, b; removed c; it runs after /xalichat reload here, or /xalichatv greload on every server`,
  and `Sent 1 changed announcement (a) to Redis` for a save made there.
- **The newest save wins.** Every key carries the time its file was saved, and an older copy never replaces a newer
  one. An edit made on a server while it was down loses to a later one made elsewhere.
- **A first start** has no file: the server reads what Redis holds and creates `announcements.yml` from the keys that
  name it. The file is empty if none do, or after ten seconds if Redis does not answer; what arrives later is added.
- **Connecting** (startup, `/xalichat reload` and a reconnect after the connection was lost) reads everything Redis holds and writes what is
  newer into the file; after a startup or a reload that is also what runs, after a reconnect it waits for the next
  reload. It then offers everything in the file, so a Redis that was wiped or down during an edit catches up. A key that
  was deleted elsewhere, or no longer names this server, is dropped from the file then.
- A file that fails to parse is logged, the announcements already loaded stay, and nothing is sent. The last working
  version is written beside it as `announcements.yml.bak`.

Updates from Redis are written into the file, which drops its comments; a file saved by hand is read as it is. The
times are kept in `announcements.state.json` beside it (on the proxy too), so a file should not be copied without it.

### Redis

Add the same section to the `config.yml` of every server and of the proxy plugin (`plugins/xalichat/config.yml`).

**File** `config.yml`

```yaml
redis:
  enable: true # default false: announcements stay in each server's own file
  host: localhost
  port: 6379
  username: "" # empty when Redis has no ACL users
  password: ""
  ssl: false
  prefix: xalichat # starts every key and channel; change it to keep two networks apart
  log-pings: true # tell the others when logs were written, see below
```

`/xalichat reload` closes the connection and opens a new one from these settings, so a changed host or password needs no
restart on a server, and `/xalichatv reload` does the same on the proxy.

A server asks Redis for the latest as soon as it connects, on a reload, and after a lost connection; a saved file is
sent as soon as it changes, and Redis tells the others. It first sends what
it has saved that Redis has not taken, then reads the whole set and applies the keys that name it, then gives Redis
anything it lacks (which refills a Redis that lost its data). With Redis down, announcements keep running from the
files, saves are kept and go up when it is back, and the connection is retried (1 second, doubling up to 30).

Redis holds the hash `<prefix>:announcements` (a key to `<modified>|<json>`, nothing after the bar for a removal) and
publishes the key to the channel `<prefix>:announcements:changed` whenever a save is taken. Redis decides in a script
which save of a key is newest, so two saves at once cannot both win.

**Log pings.** With `log-pings` on, a server that has written chat, commands or private messages to the shared database
publishes a ping to `<prefix>:logs:changed`, at most once a second: the first write starts a one-second window and one
ping goes out when it ends, so a busy chat sends one a second and never one per message. A ping carries no log, only the
server's name; it is sent after the batch has reached the database. Every server listening counts the pings it hears and
its own writes, and Plan's [log page](#the-log-page) uses that count to answer "anything new?" without reading the
database. Without a connection to Redis (or with `redis.enable` or `log-pings` off) nothing is heard from the others, so
the page reads the database as before. While connected the count is also bumped every 30 seconds, so a ping that was lost
costs a late read and not a missing entry.

**Time.** `<modified>` is Redis's clock, not the servers'. A sender says only how long ago it saved (a length of time by
its own clock), and Redis counts back from its own time. A server whose clock is wrong therefore cannot win or lose a
save by it, and a save made while Redis was down still counts as the age it has. A save that has not been sent is marked
pending in `announcements.state.json` and is never compared with Redis's times until it has been sent.

**Limits**

- The last save wins, so a stale copy open in an editor undoes an edit made elsewhere when it is saved. Reload the file
  in the editor first. A save made while an update from Redis was being applied is kept, and the update is written on
  top of it only by the next sync.
- A save from while a server was offline loses to a newer one made in the meantime (a warning names the key).
- Keys that stop naming a server leave its file at the next sync.

> [!WARNING]
> The hash is the only copy servers can refill each other from. A Redis that restarts empty is not a disaster (the next
> sync gives back what the servers hold), but a deletion made while a server was off can come back from it. Turn on
> persistence: `appendonly yes` with `appendfsync everysec` for Redis or Valkey, with the data directory on a volume
> that survives the container (in a Pelican panel, the egg's data folder). RDB snapshots (`save 60 1`) alone are enough
> if losing the last minute is acceptable.

> [!WARNING]
> Unlike the plugin channel below, nothing here is signed. Anyone who can write to the hash can add announcements that
> run console commands. Give Redis a password and keep it off the public internet.

## Proxy sync

Velocity forwarding switches on three things between the servers and the proxy plugin, all over the `xalichat:main`
plugin channel: `/xgbroadcast`, `/xgannounce` and the player list for `/msg` completion. Announcement sync uses
[Redis](#redis) instead.

The signing secret is the forwarding secret the server already has: `secret` under `proxies.velocity` in Paper's
`config/paper-global.yml` (with `enabled: true`), read once at startup. The proxy plugin takes the same value from the
file named by `forwarding-secret-file` in `velocity.toml` (`forwarding.secret` by default), so setting up Velocity's
modern forwarding is all it takes. Without it these features are off. The secret signs every message, because a client
can send to a plugin channel too, and a forged announcement would run console commands.

A plugin message needs a player's connection to ride on, so a message needs a player online. This is the format the
proxy and the servers speak (the code is in `chat/common`). All integers are big-endian, and strings use Java's
`writeUTF`.

| Frame | Layout | Meaning |
|---|---|---|
| `SIGNED` | `UTF "SIGNED"`, 32-byte HMAC-SHA256 of the rest, then the message | Wraps every message below. The HMAC is computed with the forwarding secret, as UTF-8 bytes. |
| `CHUNK` | `UTF "CHUNK"`, `UTF id`, `int index`, `int total`, bytes | One slice (at most 30 000 bytes) of a message too big for one plugin message (Bukkit's limit is 32 766). The slices, in index order, are the whole `SIGNED` frame. At most 64 slices; an unfinished transfer is dropped after 30 seconds. |
| `BROADCAST` | `UTF "BROADCAST"`, `boolean bypassMute`, Adventure JSON of the message | Server to proxy from `/xgbroadcast`; proxy to every server, including the sender. |
| `ANNOUNCE` | `UTF "ANNOUNCE"`, `UTF key`, `UTF uuid` (empty for no player), `UTF name` (may be empty) | Server to proxy from `/xgannounce`; proxy to every server, including the sender. Servers not in the announcement's `servers` list skip it. |

Unsigned or wrongly signed frames are ignored.

### Velocity relay

Install `xali-chat-<version>-velocity.jar` in the proxy's `plugins` folder. It needs no configuration unless Redis is
used or Plan runs on the proxy (see the [log page](#on-a-network-plan-on-the-proxy)). The relay finds the forwarding secret the way Velocity does (the `VELOCITY_FORWARDING_SECRET` variable, else the
file named by `forwarding-secret-file` in `velocity.toml`), registers the channel with the servers, and then:

- keeps the master `plugins/xalichat/announcements.yml` (with its `announcements.state.json`) and shares it through
  [Redis](#redis) when `redis.enable` is on. A master edited by hand is shared the same way;
- relays a global broadcast or announcement to every server, the sender included, and logs it to its own console
  (`[Broadcast from <server>] relayed to <n> of <m> servers`, without the text);
- sends every server the names of everyone on the proxy, for tab completion of `/msg`, whenever someone joins, leaves
  or vanishes (a burst of those sends one list, after `tab-completion.debounce`). With PremiumVanish on the proxy, a
  vanished player is left out for each player who cannot see them, as PremiumVanish decides;
- finds the player a `/msg` is for when they are on another server (by name, or the start of one), passes the message to
  that server, and tells the sender's server how it went: delivered, no such player online, or ignored (the receiving
  server checks the ignore list, since it holds it). Social spies are told on both servers. This is skipped when
  `cross-server-messages` is off;
- drops anything a player sends on the channel, and anything with a missing or wrong signature;
- with Plan on the proxy, serves the [log page](#on-a-network-plan-on-the-proxy) from the servers' database;
- has the command `/xalichatv`: `reload` reads the proxy's `config.yml` and the master `announcements.yml` again and
  reconnects to Redis with the settings as they are now (a `config.yml` that doesn't parse is logged and the settings
  stay); `greload` does that and also asks every server to run its `/xalichat reload`. They need `xalichatv.reload` and
  `xalichatv.greload` (the console may always). The request is a signed message like the others, and a plugin message
  can only travel with a player, so a server with nobody on is not reached: `greload` names those (and the servers
  without XaliChat count as such), and `/xalichat reload` has to be run on them.

Private messages between servers can be switched off for the whole proxy (`cross-server-messages.enable` in its
`config.yml`), limited to some servers (`cross-server-messages.servers`, wildcards such as `skyblock-*`; a message needs
the sender's and the receiver's server both listed), or switched off for one server with `cross-server-messages: false`
in that server's `config.yml`. A server that is out gets "not found" for players elsewhere and is not found itself. A
vanished player (SuperVanish or PremiumVanish) that the sender cannot see counts as not found, on this server and
through the proxy.

The first start writes `plugins/xalichat/config.yml` (`debug`, `tab-completion`, `cross-server-messages`, `redis`) and a
master `announcements.yml` with two switched-off examples, so there is something to edit.

`servers` patterns are matched against the server names in `velocity.toml`, and each server checks them against its own
`database.server-name`, so keep the two the same.

## Working with other plugins

`chat/api` (`net.xalitoria.chat`) lets a plugin take part without XaliChat knowing about it. List XaliChat as a
dependency of the plugin (`join-classpath` on; optional if the plugin can do without) and compile against
`compileOnly(project(":chat:api"))`. Both interfaces are found through Bukkit's services manager.

- **`PrivateMessageGate`** is registered by the plugin:
  `servicesManager.register(PrivateMessageGate::class.java, gate, plugin, ServicePriority.Normal)`. XaliChat asks every
  gate before it delivers a private message to a player on its server, for a sender on that server and for one that
  reached it through the proxy. If any gate says no, the sender is told `"{0} isn't accepting messages from you."`
  (`commands.message.declined` in the language file) and nothing is delivered. The question is asked on the main thread
  for every message, so a gate answers from memory. Senders with `xalichat.bypass.message.ignore` skip the gates.
  [XaliFriends](../friends/README.md) uses it for players who take messages from friends only.
- **`NetworkPlayers`** is provided by XaliChat. `isOnline(name, viewer)` says whether a player is online anywhere behind
  the proxy (as the proxy last told this server) and not vanished from the viewer. Without a proxy only this server's
  players count.

A plugin that carries its own copy of the Kotlin runtime cannot register Kotlin-typed objects with another plugin (a
`KSerializer` or a `KClass` from its copy is a different class from the other plugin's). Both interfaces therefore use
only Bukkit and Java types.

### Plan

With [Plan](https://github.com/plan-player-analytics/Plan) (Player Analytics) installed and `plan.enable` on (the
default), XaliChat adds its logs to Plan in two ways: an extension called XaliChat on Plan's player and server pages,
and a searchable [log page](#the-log-page). Both are registered at startup, so a change to the `plan` section needs a
restart. XaliChat hooks in whenever Plan enables, whichever of the two loads first, and again if Plan reloads. The Plan
classes are touched only when Plan is present. A network has one web server (usually the proxy's), so see
[On a network](#on-a-network-plan-on-the-proxy) for where the page is served from.

| Where | What |
|---|---|
| Player page, *Chat* tab | The number of messages the player sent on this server, and a table of their latest `plan.rows` (25 by default, at most 100). |
| Player page, *Commands* tab | The same for commands, as logged: without the labels in `logging.commands.ignored-commands`, so private messages and logins never show. |
| Server page | Chat messages, commands and, with `logging.messages.enable`, private messages sent from this server, in the last 24 hours and in total, and tables of the latest `plan.rows` of chat and commands. |
| Network page (proxy) | The same counts over every server, from the proxy's own extension, see [On a network](#on-a-network-plan-on-the-proxy). Private messages are counted once any are logged. |

A command is logged with the namespace of the plugin that owns it, as the command map knows it: `/gmc audizian` is
logged as `/essentials:gmc audizian` and `/pay audizian 100` as `/eco:pay audizian 100`. A label typed with a
namespace is kept, and one the command map doesn't know is logged as typed. Commands the Velocity proxy handles itself
(`/server`, `/glist`) never reach the server, so the proxy's XaliChat sends them to the player's server over the
[relay](#velocity-relay), which logs them like its own (as `velocity:<label>` for the proxy's own commands, with the same
`logging.commands` rules). Without the proxy's XaliChat they are not logged.

Only what `logging.chat.database` and `logging.commands.database` write to the database can be shown, so the chat and
command parts are left out when those are off (or with `plan.chat` or `plan.commands` set to `false`). Plan keeps 50
characters of a table value, so a long message or command is cut with an ellipsis. Plan refreshes a player's data when
they join and leave, and the server's now and then. Everyone who may see a player's page in Plan sees their chat, so hand
out Plan's web permissions accordingly.

#### The log page

> [!IMPORTANT]
> The page is served by the XaliChat that is on the same machine as Plan's web server. On a network that is usually the
> proxy: Plan on the servers then only feeds the shared database and switches its own web server off, so a log page
> registered by the servers' XaliChat is never reachable. See [On a network](#on-a-network-plan-on-the-proxy).

With `plan.page` on (the default) Plan's web server also serves `/xalichat`: chat, commands and private messages as one
list, newest at the top, from every server that shares the database and back to the first entry logged. It looks like a
debug console: each log has its own color, names get theirs, and times are in the browser's own time zone.

| Control | Does |
|---|---|
| *Chat*, *Commands*, *Messages* | Show or hide that log. At least one stays on, and the choice is remembered. *Messages* is there for users with `xalichat.messages`, and only while `logging.messages.enable` is on. |
| Search bar | Text that has to be in a message, command or name, marked in the list. `player:<name or uuid>` keeps one player (a private message matches either end) and `server:<name>` one server. `/xalichat/?q=player:Steve` opens the page with a search. The search is part of the database query, as are the logs shown. |
| Timeline | Bars over the list showing how many entries of each log there are over time, for the logs and search as they are (about 120 bars, bigger ones for a longer log). Drag on it to pick a stretch of time for the list, drag its edges or its middle to change it, click a bar to pick that stretch, double click for all of it. *12h*, *24h*, *7d*, *30d* and *All* pick the last stretch of time. A stretch that ends at the right edge stays open, so the list keeps following new entries. The arrow at its left hides it. |
| Click on a name or server | Keeps only that player or server, as if typed into the search bar. |
| Right click on an entry | *Jump to message* clears the search (keeping its server) and the picked time and shows the log around the entry, with older entries below and newer ones above. *Back to latest* or Escape returns to the list. *Show only* the player or server, *Copy line* and *Copy text* are there too. |

The page brings 50 entries, and 50 more whenever you scroll to the bottom, for the logs and the search as they are then.
At the top, the list follows new entries (it asks every 3 seconds, and the server answers without touching the
database while the [Redis pings](#redis) say nothing was written); scrolled down, a *N new* button waits instead of moving
the list. Private messages are only recorded with `logging.messages.enable`, on the sender's server.

##### In Plan's plugin pages

The log is also put into XaliChat's card on Plan's plugin pages, above the card's counts, by a script (`/xalichat/plan.js`)
added to Plan's own page. The log is on XaliChat's own page only, not on the `plugins-overview` page that lists every
plugin. It needs a user with `xalichat.log`; others see no log there. The counts, which Plan lists
one under another, are laid out as a row of tiles under the log.

| Plan page | Shows |
|---|---|
| `/player/<uuid>/plugins/<server>` | That player's entries on that server. On the proxy's page, on every server. |
| `/server/<uuid>/plugins/XaliChat` (the *XaliChat* entry in the sidebar, in Plan 5.8) | That server's log. A proxy's page shows every server's. |
| `/network/plugins/XaliChat` (Plan 5.8) | Every server's log, with a picker for one server. Needs the proxy's XaliChat, see [On a network](#on-a-network-plan-on-the-proxy). |

A server is matched to Plan's by name, ignoring case: `database.server-name` in XaliChat's config has to be the name Plan
has for the server, or the log is not limited to it. On a player's page, a right click on an entry has *Open in
<server> log*, which goes to that server's plugins page with the entry focused. The same page can be opened with
`?xalichat=<entry key>` added to its address. Where a server of that name isn't known to Plan, it opens `/xalichat/`
with the entry focused (`?focus=<key>`).

A server's page and a player's page show only that server's entries, so they have no server column and no server picker. The
network's page and `/xalichat` have both: the picker (shown when more than one server has logged) limits the list to one
server, and a click on a server name or *Show only <server>* in the right-click menu does the same.

**Plugin versions.** The footer of the log names the versions in use, and the tooltip lists them by server:

| Page | Footer |
|---|---|
| A server's or player's page | `XaliChat Server 0.10.1 · Proxy 0.10.1`: that server's version and the proxy's. Without a proxy (Plan on the server) it is the one version. |
| The network's page, `/xalichat` | `XaliChat Proxy 0.10.1 · Servers 0.10.1 ×2, 0.10.0`: the proxy's, then each version in use with how many servers run it. |

The proxy knows its own version. A server tells the database its version when it starts and every five minutes after, in
two columns of the `servers` table (`version`, `seen`, added by the plugin on its own); a server counts as in use when it
last reported in the past 15 minutes, so one that was shut down drops out of the list. The footer turns amber when more
than one version is in use, such as in the middle of an upgrade. A server on a release that doesn't report is left out of the
list, and a server's page shows only the proxy's version until its server name in Plan matches `database.server-name`.

While the log is in the card, Plan's own tables of the latest entries are hidden (the log has them, searchable and without
the 50 characters). The counts stay. Without the log (a user without `xalichat.log`, or a Plan without
`PAGE_EXTENSION_RESOURCES`) the tables are all there is.

The page takes Plan's theme: it reads the theme, sidebar color and night mode this browser has selected in Plan (or
Plan's defaults) and builds the same colors from them, so it follows a theme or night mode switched in Plan and a custom
theme saved in the browser. If Plan's theme can't be read it keeps Plan's default look. The kind colors are the ones the
extension's tables use: light blue for chat and deep purple for commands, plus orange for private messages.

The page uses Plan's login, so it only works with Plan's login switched on. Give a Plan user the web permission
`xalichat.log` to open it, and `xalichat.messages` to see private messages as well. Plan registers them when XaliChat
does, and they are not Minecraft permissions. Plan treats a permission as given by any shorter one it starts with, which
is why the two are not nested. Without a login nobody is let in.

##### On a network: Plan on the proxy

When the web UI (`plan.example.com`) is Plan's on the Velocity proxy, the proxy's XaliChat has to serve the page, and
for that it reads the same database as the servers. Install the Velocity jar next to Plan on the proxy and fill in its
`plugins/xalichat/config.yml`:

```yaml
plan:
  page: true        # serve /xalichat and the log in XaliChat's card; false leaves Plan alone
  messages: true    # whether xalichat.messages users may read private messages
database:
  type: "mariadb"   # as on the servers: sqlite, mariadb or mysql
  table-prefix: "xalichat_"
  mysql:
    host: "db.example.com"
    port: 3306
    database: "xalichat"   # the servers' database, not Plan's
    username: "xalichat"
    password: "..."
```

The proxy only reads: it never creates or alters a table (the servers do), so a database user that may `SELECT` on the
`xalichat_` tables is enough. A server that has written nothing yet is simply missing from the list. Everything else on the
page, including the right-click jump and the timeline, works as it does on a server. The `redis` section is the servers',
and with it on and `redis.log-pings` the page stays quiet until a server wrote something.

The page is registered when Plan's web server is up, and the log shows in XaliChat's card on `/server/<id>/plugins/XaliChat`
once the servers have registered their XaliChat extension in Plan (they do that themselves).

The network's page, `/network/plugins/XaliChat`, has a card because the proxy registers an extension of its own with Plan
(it is what Plan lists as a network plugin). It counts what the logs of every server hold: chat messages and commands in the
last 24 hours and in total, and private messages once any are logged and `plan.messages` is on. The log of every server is
framed into that card, with a picker for one server. `/xalichatv reload` reads
the `database` section again, except `table-prefix`, which needs a restart. If the database can't be reached the proxy says
so in its log and leaves the page off. The servers' own `plan.page` can stay on, it is harmless where Plan has no web server.

## Permissions

### Formatting

Each prefix accepts the same sub-nodes: `<prefix>.gradient`, `.color`, `.shadow`, `.decor.<name|*>` and
`.tag.<name|*>`. Formatting the sender lacks is stripped from the message.

| Prefix | Applies to |
|---|---|
| `xalichat.format.global` | Public chat (default prefix). |
| `xalichat.format.discord` | Messages coming from Discord. |
| `xalichat.format.broadcast` | `/xbroadcast` and `/xgbroadcast`. |
| `xalichat.format.nickname` | The player's own nickname. |
| `xalichat.format.nickname.others` | Nicknames set on others. |
| `xalichat.format.sign` | Text written on signs. |

Decoration names: `obfuscated`/`magic`/`k`, `bold`/`b`/`l`, `strikethrough`/`st`/`m`, `underline`/`u`/`n`,
`italic`/`i`/`em`/`o`, `reset`/`r`. Tag names: `click`, `head`, `hover`, `insert`, `key`, `score`, `sprite`,
`data`/`nbt`, `selector`/`sel`.

### Bypass

| Permission | Effect |
|---|---|
| `xalichat.bypass.message.ignore` | The player cannot be added to an ignore list, others' ignore lists do not hide their chat or private messages, and a plugin that limits who may message a player (see [Working with other plugins](#working-with-other-plugins)) does not stop their private messages. |
| `xalichat.bypass.mute.players` | The player's chat still reaches players who muted global chat. |
| `xalichat.bypass.mute.broadcasts` | The player's broadcasts and announcements still reach players who muted broadcasts. |
| `xalichat.bypass.characters` | Chat and private messages are not limited by [`characters`](#limiting-characters). Operators are never limited. |

### Everything else

`xalichat.reload`, `xalichat.message`, `xalichat.spam`, `xalichat.ignore`, `xalichat.setting.mute`,
`xalichat.setting.mute.players`, `xalichat.setting.mute.broadcasts`, `xalichat.command.nickname`,
`xalichat.command.nickname.others`, `xalichat.admin.spy.command`, `xalichat.admin.spy.social`,
`xalichat.admin.chatlog`, `xalichat.admin.chatlog.export`, `xalichat.admin.chatlog.import`,
`xalichat.admin.broadcast`, `xalichat.admin.broadcast.global`, `xalichat.admin.announce`,
`xalichat.admin.announce.global`, `xalichat.admin.alert.characters` (told when `characters.mode` is `alert`) and the four `xalichat.interactive.*` nodes are described in the tables above.

## Troubleshooting

### Announcements do not reach a server

1. Check that `redis.enable` is `true` in the `config.yml` of that server **and** of the proxy plugin, with the same
   `prefix`.
2. Check that the announcement's `servers` list matches the server's name in `velocity.toml` and that the server's
   `database.server-name` is the same name.
3. Run `/xalichat reload` on the server. With Redis off, the log says `Redis is off (redis.enable in config.yml)`; a rejected update is logged as `Dropped an update from Redis`.

### `/xgbroadcast` or `/xgannounce` only run on one server

The proxy plugin is missing, or the forwarding secret could not be read. The command says it ran on this server only.
Check `proxies.velocity.enabled` and `secret` in `config/paper-global.yml` and `forwarding-secret-file` in
`velocity.toml`, then see [Proxy sync](#proxy-sync).

### The proxy plugin does not start offline

It downloads its libraries once into `plugins/xalichat/libraries`. Start it once with internet access.

### A label such as `/reply` belongs to another plugin

XaliChat takes over plain labels at startup and logs which it took. If another plugin registers them later, load order
decides; list XaliChat as a dependency of that plugin or use the `x` form (`/xreply`).

## Architecture

XaliChat is two plugins and three ways for them to talk. The Paper plugin does nearly everything a player sees; the
Velocity plugin relays between servers and, where Plan's web UI runs on the proxy, serves the log page.

```
              ┌────────── Redis (optional): announcements, log pings ──────────┐
              │                                                                │
 players ▶ Paper server(s) ◀── signed plugin messages (xalichat:main) ──▶ Velocity proxy
           XaliChat (paper)                                              XaliChat (velocity)
              │ writes the logs                                             │ reads the logs and
              ▼                                                             ▼ adds /xalichat to Plan
        shared database (SQLite, MariaDB or MySQL) ─────────────────▶ Plan's web UI (on the proxy)
```

| Channel | Carries | Needs | Trust |
|---|---|---|---|
| Plugin messages on `xalichat:main` | Global broadcasts and announcements, private messages with their answer, the player list, reload requests. See [Proxy sync](#proxy-sync). | Velocity forwarding, and a player online to ride on. | Every message is HMAC-signed with the forwarding secret; the proxy drops anything a player sends on the channel. |
| Redis | The announcement set (a hash and a `changed` channel) and log pings. See [Redis](#redis). | A Redis shared by the servers and the proxy. Optional. | Not signed: protect it with a password. |
| The database | Chat, command and private message logs, the `servers` table, ignores, settings and (optionally) nicknames. | Credentials on every server; on the proxy only to read the logs. | Database credentials. |

Nothing depends on all three. Without Redis each server keeps its own announcements and the log page reads the database
every time; without a proxy the global commands run locally; without Plan there is no log page.

### Modules and what they may depend on

```
chat/api ───────────────┐
                        ▼
chat/common ──────▶ chat/paper  (shades common and api; libraries come from Paper's loader)
     │
     └────────────▶ chat/velocity  (shades common; downloads its libraries itself)
```

- **`chat/common`** has no Bukkit or Velocity API and targets Java 17, so both jars can bundle it. It holds the wire format
  (`protocol`), the announcement model and sync (`announce`), and everything the log page needs (`logs`): the table
  definitions, `LogRepository`, `LogPage` (the Plan resolver), `LogPings` and `LogStorage`. Its libraries (kaml,
  kotlinx.serialization, Jedis, Exposed, coroutines, Plan's API) are `compileOnly`: whoever packages it supplies them.
  `plan/log.html` and `plan/plan.js` are its resources.
- **`chat/paper`** is the plugin proper and the composition root: `XaliChat.onEnable` wires everything. Paper's library
  loader downloads util, the database drivers, Exposed and Jedis at startup (`loader.json`), so none of them is shaded.
- **`chat/velocity`** is small: the relay, `/xalichatv`, the vanish filter, and the log page's database connection and
  Plan hook. Velocity cannot download libraries, so a generated loader does, checking each against a built-in SHA-256.
- **`chat/api`** is the only part other plugins compile against, and it uses only Bukkit and Java types, because a
  plugin with its own copy of the Kotlin runtime cannot share Kotlin-typed objects with another.

### Inside the Paper plugin

`XaliChat.onEnable` runs in this order, and a failure in the first three disables the plugin: coroutine scope and
ProtocolLib; the configuration files; the database (Hikari pool, Exposed, schema statements generated
by `MigrationUtils`, this server's row in `servers`). After those: log writers and pings, ProtocolLib listeners,
Brigadier commands (registered in the `COMMANDS` lifecycle event), the proxy messenger, announcements, vanish
support, the Plan hook (whenever Plan enables, whichever loads first), the services other plugins use, interactive chat,
DiscordSRV, and the event listeners.

| Package | Role |
|---|---|
| `command` | One object per command, all built on `XCommand`: it registers each alias with and without the `x`, takes labels over from other plugins, and re-exposes the tree under `/xalichat`. |
| `listener` | `ChatEvent` (the chat pipeline), `PlayerCacheListener` (loads a player's ignores, settings and nickname on join, drops them on quit), command logging and spying, sign formatting. |
| `data/config` | The three YAML files as serializable classes, read again by `/xalichat reload`. |
| `data/db` | `DatabaseManager` (the pool and the schema), repositories, and `IgnoreCache` / `SettingCache`: in-memory, loaded on join, written through by their commands, so chat never waits on the database. |
| `data/log`, `concurrent` | `LogService` and `LogWriter`: entries are queued, written in batches of up to 200 off the main thread, and a failed or timed-out write falls back to `CHATLOG\|` / `CMDLOG\|` lines in the server log, which `/chatlog` can read back. Private messages have no such fallback. |
| `modal/announce`, `modal/ic` | The [announcement](#announcements) runner and [interactive chat](#interactive-chat) (item, inventory and ender chest snapshots, at most 200 kept, read on the thread that owns the player). |
| `external` | Everything that names another plugin's classes: ProtocolLib, Vault, vanish plugins, DiscordSRV, Plan. Each is touched only when that plugin is present. |
| `concurrent/ProxyMessenger` | The server end of `xalichat:main`: signs and sends, reassembles chunks, and acts on what the proxy sends. |

**Threads.** Chat events arrive asynchronously. Database work runs on a coroutine scope (`Dispatchers.IO`), and results
that touch a player or the world go back to the server through `util/Scheduling.kt`, which is built on the schedulers of
[`util`](../util/README.md#usage) (0.2.1 and later), never the Bukkit scheduler (which Folia doesn't have): a reply to a player or a change to one runs on the thread that owns them (their region on Folia,
the main thread on Paper), and console commands and conditions on the global region. Plan calls the log page off the
server thread, so the page reads the database directly.

### Following a chat message

1. `AsyncChatEvent` reaches `ChatEvent`. The text as typed is queued for the chat log first, so a message that is
   refused below, or that a viewer drops, is still logged.
2. [`characters`](#limiting-characters) checks what the sender typed (`originalMessage`, so other plugins' glyphs don't count) unless they are an operator or have the bypass, and alerts, strips or refuses.
3. The format is built: placeholders are expanded on the template only, so typed text is never resolved; `[item]` and the
   other tokens become hover and click components. The result is shared by every viewer unless the format has
   per-viewer `%rel_` placeholders.
4. Viewers who ignore the sender, or have muted global chat, are removed, unless the sender holds the bypass.

### Following a private message

`/msg` first applies [`characters`](#limiting-characters) to a player's text, then resolves the receiver here (players the sender can see, then the console). Someone on this server is
messaged at once, the ignore list and any `PrivateMessageGate` are checked, spies are told, and the sender's server logs
it (when `logging.messages.enable` is on). Someone elsewhere goes as a signed `WHISPER` to the proxy, which finds the
player by name or prefix and hands it to their server; that server checks ignore, gates and vanish, delivers it, and
answers with a `WHISPER_ACK` the proxy passes back. The sender waits at most five seconds for it. Each server tells its own spies.

### Following an announcement

`announcements.yml` is the file; `AnnouncementStore` knows when each key last changed (by content, not layout) and keeps
removals as stamps so an old copy cannot bring a key back. `AnnouncementSync` offers saved keys to Redis, where a script
decides which save of a key is newest, and receives the others' changes. What actually runs is a separate in-memory map
(`running`) that only a start or `/xalichat reload` replaces, so saving and syncing can never change what a timer does
halfway. The proxy runs the same store with every key; a server's holds only the keys that name it.

### The log pipeline and Plan

- **Write.** Servers write the three log tables; after each batch `LogPings` bumps a local counter and tells the others
  through Redis.
- **Read.** `LogRepository` merges the three tables into one newest-first stream (so a search, a page, a window around an
  entry and the timeline's histogram are each one query shape), reading through `LogStorage`, which holds the database and
  the table prefix. A server points `LogStorage` at its own pool; the proxy opens a read-only one.
- **Serve.** `LogPage` is a Plan resolver at `/xalichat`: the page, `/xalichat/log` (JSON) and `/xalichat/plan.js`, which
  Plan adds to its own page to frame the log into XaliChat's card. Plan's login and web permissions
  (`xalichat.log`, `xalichat.messages`) decide who may read it, and the page refuses everyone when Plan's login is off.
- **Where.** A Plan registers the page in its own process, and only the Plan whose web server is running serves it.
  Standalone, that is the server's; in a network it is the proxy's, which is why the Velocity plugin repeats the
  registration with its own read-only connection (see [On a network](#on-a-network-plan-on-the-proxy)). The cards
  themselves come from the extension each server's Plan registers in the shared Plan database.
- **Fresh.** The page asks for what is newer than what it has and sends the counter it saw; while pings are being heard
  and the counter is unchanged the answer is empty and nothing is read.

### Decisions worth knowing

- **Signed plugin channel, unsigned Redis.** The channel can be written by clients, so every message is signed; Redis is
  assumed to be private.
- **Database for logs, Redis for announcements.** Logs are large and queried; announcements are small, need "newest wins"
  and must reach a server with nobody on it, which a plugin message cannot.
- **Caches, not queries, in the chat path.** Ignore and mute state is loaded on join; the chat event never touches the database.
- **Config survives edits.** A file that fails to parse changes nothing, and the last good text is kept as `.bak`.
- **One definition of the log tables**, in `chat/common`, so the writer on a server and the reader on the proxy cannot drift.

### Known limits

- A plugin message needs a player online on the sending side, so global commands and `/xalichatv greload` cannot reach an empty server.
- The table prefix is read once per process (a new one needs a restart), on servers and on the proxy.
- Private messages are only logged on the sender's server, in the database, and only when asked for.

## Development

Sources are under `chat/<submodule>/kotlin`, tests under `chat/<submodule>/test` (see the
[root README](../README.md#source-layout)).

| Test | Covers |
|---|---|
| `chat/common` `Protocol` | Frames, signing, chunking and the message encodings. |
| `chat/common` `AnnouncementStore` | Reading, writing and merging `announcements.yml`. |
| `chat/common` `Actions` | The `actions` tree: reading and writing it through the file and the wire, the old `messages` and `commands` format, the errors for a bad action, delays and conditions. |
| `chat/common` `AnnouncementSync` | The sync between several participants, including skewed clocks. Needs a real Redis: skipped unless `XALI_REDIS_TEST` is set. |
| `chat/paper` `Colorizer` | Formatting permissions: what is stripped and what stays. |
| `chat/paper` `PlanExtension` | Plan's own validation of the extension's annotations, parameters and return types. |
| `chat/common` `LogPings` | Log pings: one a window however many writes, none to the sender itself, and a plain count without Redis. Needs a real Redis: skipped unless `XALI_REDIS_TEST` is set. |
| `chat/paper` `RemoteDatabase` | The schema setup and the log page's queries (newest-first search, an escaped `LIKE`, the timeline's grouping) on a real MariaDB and MySQL server, each with its own driver. Needs both: skipped unless `XALI_MARIADB_TEST` and `XALI_MYSQL_TEST` (`host:port`, root, empty `xalichat` database, over TLS) and `XALI_DB_PASSWORD` are set. |
| `chat/paper` `LogSearch` | The merged log search and its focus window, the timeline's histogram, the page's search syntax and framed scope, the pings' short answer, and who the log page lets in. It tests code that lives in `chat/common` (`logs`) and runs here because it needs the Paper side's database setup. Uses a temporary SQLite database. |
| `chat/paper` `Characters` | The basic-character rule: what it allows (private-use glyphs and `allow` included), strips and reports, and stripping a component without losing its colors. |
| `chat/paper` `Filter`, `FilterPermutations` | Tools for the unfinished phrase filter, which the plugin does not load. |

```console
user $ ./gradlew :chat:common:test :chat:paper:test
user $ docker run -d --rm --name xali-redis-test -p 6390:6379 redis:7-alpine redis-server --requirepass secret
user $ XALI_REDIS_TEST=127.0.0.1:6390 XALI_REDIS_PASSWORD=secret ./gradlew :chat:common:test --rerun-tasks
```

The database tests need a MariaDB and a MySQL server with an empty `xalichat` database each (the same root password on both):

```console
user $ docker run -d --rm --name xali-maria-test -e MARIADB_ROOT_PASSWORD=pw -e MARIADB_DATABASE=xalichat -p 3307:3306 mariadb:11
user $ docker run -d --rm --name xali-mysql-test -e MYSQL_ROOT_PASSWORD=pw -e MYSQL_DATABASE=xalichat -p 3308:3306 mysql:9.3
user $ XALI_MARIADB_TEST=127.0.0.1:3307 XALI_MYSQL_TEST=127.0.0.1:3308 XALI_DB_PASSWORD=pw ./gradlew :chat:paper:test --rerun-tasks
```

Where things are: commands in `chat/paper/.../command`, event listeners in `listener`, ProtocolLib and Plan hooks in
`external`, the database in `data/db`, the configuration classes in `data/config`, announcements and interactive chat in
`modal`. What both plugins use has no platform code and lives in `chat/common`: the announcement sync in `announce`, the
wire format in `protocol`, and in `logs` the log tables, `LogRepository`, the Plan `LogPage` and its HTML and script
(`chat/common/resources/plan`). The proxy's own pieces are in `chat/velocity`. See [Architecture](#architecture) for how
they interact.

## See also

- [XaliFriends](../friends/README.md) — friend system that limits private messages to friends through the gate API
- [XaliMenu](../menu/README.md) — inventory menus that use the same text formatting
- [Gradle plugin](../xali-gradle/README.md) — descriptors and library loading
- [Plan](https://github.com/plan-player-analytics/Plan) — player analytics
