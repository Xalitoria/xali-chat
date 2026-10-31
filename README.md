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
| Plan | No | Chat and command log on Plan's pages, see [Plan](#plan). |
| SuperVanish, PremiumVanish | No | Vanished players are hidden from player lists and private messages. |
| Essentials | No | Loaded after XaliChat; plain labels it owns are taken over, see [Commands](#commands). |

### Modules

| Module | Description |
|---|---|
| `chat/paper` | The Paper plugin, `xali-chat-<version>-paper.jar`. Everything below is this module unless it says otherwise. |
| `chat/velocity` | The Velocity plugin, `xali-chat-<version>-velocity.jar`: the [relay](#velocity-relay). |
| `chat/api` | What other Paper plugins compile against to take part in XaliChat, see [Working with other plugins](#working-with-other-plugins). Shaded into the Paper jar. |
| `chat/common` | The wire format both plugins speak (signed frames, chunking, message encodings) and the announcement sync. No platform API; both jars bundle it. Targets Java 17. |

### Building and installing

```console
user $ ./gradlew :chat:paper:shadowJar :chat:velocity:shadowJar
```

Copy `build/libs/chat/xali-chat-<version>-paper.jar` into each server's `plugins` folder and
`build/libs/chat/xali-chat-<version>-velocity.jar` into the proxy's `plugins` folder.

> [!NOTE]
> The Velocity plugin downloads kaml and kotlinx.serialization into `plugins/xalichat/libraries` the first time it
> starts. The checksums are built into the jar. The proxy needs internet access once.

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
| `plugins/XaliChat/nicknames.db` | Nicknames, unless `nickname.remote` is on. |
| `plugins/XaliChat/exports/` | Output of `/chatlog export`. |
| `plugins/XaliChat/filter.yml` | Chat filter. **Not active:** the file is read, but nothing in chat is checked against it yet. |
| `plugins/xalichat/config.yml` (proxy) | Settings of the [Velocity relay](#velocity-relay). |

`/xalichat reload` reads the configuration, the messages and `announcements.yml` again.

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
| `plan` | on | See [Plan](#plan). |
| `logging` | database | `chat.database`, `commands.database` and `commands.ignored-commands`. Without the database, entries go to the server log as `CHATLOG|...` and `CMDLOG|...` lines. |

`database.type: mariadb` uses MariaDB's driver and `mysql` MySQL's. The MySQL driver cannot read MariaDB 11's metadata,
so use `mariadb` for a MariaDB server.

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
| `/xalichat reload` | `chat` | Reload the config, messages and `announcements.yml`. | `xalichat.reload` |
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
| `/xbroadcast <message>` | `broadcast`, `bcast`, `bc` | Announce to every online player on this server and the console. | `xalichat.admin.broadcast` |
| `/xgbroadcast <message>` | `gbroadcast`, `gbcast`, `gbc` | The same on every server behind the proxy. | `xalichat.admin.broadcast.global` |
| `/xannounce <player> <announcement>` | `announce`, `ann` | Play an [announcement](#announcements) on this server for that player. | `xalichat.admin.announce` |
| `/xgannounce <player> <announcement>` | `gannounce`, `gann` | Play it on every server its `servers` list names. | `xalichat.admin.announce.global` |
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
  messages:
  - "========================================"
  - ""
  - "Join the Discord: https://discord.gg/rosaic"
  - ""
  - "========================================"
  commands:
  - "playsound @a pling"
  - "delay 2s 10t" # 2.5 seconds total
  - "another-command"
"rank-titan":
  servers:
  - "skyblock"
  messages:
  - "%player_name% purchased Titan rank!"
  - "Get one at https://store.rosaic.net"
  commands:
  - "lp group {uuid} promote skyblock server=skyblock"
  - "playsound @a yay"
```

| Key | Default | Meaning |
|---|---|---|
| `enable` | `true` | `false` switches it off everywhere, including the commands. |
| `delay` | `0` | Seconds before the first automatic run. |
| `repeat` | `0` | Seconds between automatic runs; `0` runs it once. |
| `servers` | all | The servers that hold and run it, matched against their names in `velocity.toml` with `*` and `?` wildcards (`skyblock-*`). A server's `database.server-name` in `config.yml` must be the same name. Empty means every server. `/xannounce` ignores the list. |
| `messages` | none | Chat lines, one message each; `""` is a blank line. |
| `commands` | none | Console commands, run in order after the messages. |

An announcement with a `delay` or `repeat` runs by itself on every server that matches `servers`. One with neither runs
only from `/xannounce` or `/xgannounce`.

- **Messages** go to every online player who has not muted broadcasts, and to the console. They accept `&` codes,
  `&#RRGGBB` and MiniMessage tags (`<gradient:red:blue>`, `<click:open_url:...>`).
- **Placeholders** resolve for the player the announcement runs for: `{name}`, `{uuid}` (dashed), `%player_name%` and
  any PlaceholderAPI placeholder. A timer has no player, so each viewer gets their own (the console gets none).
  Placeholders work in commands too. Text from `/xbroadcast` is never expanded.
- **`delay`** is an internal command that pauses the rest of the list instead of running anything. Each token is a
  number and a unit, added together: `t` ticks, `ms`, `s`, `m`, `h`, `d`. A bare number is seconds. `delay 2s 10t` waits
  2.5 seconds.
- Keys should be single words, since the commands take them as one argument.

### How announcements are shared

Announcements are shared through [Redis](#redis), so the servers and the proxy agree on one set without a player online.
The proxy's `announcements.yml` holds every announcement; a server's file holds only the ones whose `servers` name it. An
announcement can be edited on any server, or in the master on the proxy, and ends up where it belongs:

- **Saving the file** offers each key that changed (new, edited or deleted) to Redis, which tells everyone else. Each
  server the key names applies it. Nothing needs a reload, and the timers restart by themselves. Only keys whose content
  changed count; comments and layout do not.
- **The newest save wins.** Every key carries the time its file was saved, and an older copy never replaces a newer
  one. An edit made on a server while it was down loses to a later one made elsewhere.
- **A first start** has no file: the server reads what Redis holds and creates `announcements.yml` from the keys that
  name it. The file is empty if none do, or after ten seconds if Redis does not answer; what arrives later is added.
- **Connecting** (startup, `/xalichat reload` and every five minutes) reads everything Redis holds and applies what is
  newer, then offers everything in the file, so a Redis that was wiped or down during an edit catches up. A key that
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
```

`/xalichat reload` closes the connection and opens a new one from these settings, so a changed host or password needs no
restart on a server. The proxy has no reload and needs a restart.

A server asks Redis for the latest as soon as it connects, on a reload, and every 5 minutes after. It first sends what
it has saved that Redis has not taken, then reads the whole set and applies the keys that name it, then gives Redis
anything it lacks (which refills a Redis that lost its data). With Redis down, announcements keep running from the
files, saves are kept and go up when it is back, and the connection is retried (1 second, doubling up to 30).

Redis holds the hash `<prefix>:announcements` (a key to `<modified>|<json>`, nothing after the bar for a removal) and
publishes the key to the channel `<prefix>:announcements:changed` whenever a save is taken. Redis decides in a script
which save of a key is newest, so two saves at once cannot both win.

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
| `ANNOUNCE` | `UTF "ANNOUNCE"`, `UTF key`, `UTF uuid`, `UTF name` (may be empty) | Server to proxy from `/xgannounce`; proxy to every server, including the sender. Servers not in the announcement's `servers` list skip it. |

Unsigned or wrongly signed frames are ignored.

### Velocity relay

Install `xali-chat-<version>-velocity.jar` in the proxy's `plugins` folder. It needs no configuration unless Redis is
used. The relay finds the forwarding secret the way Velocity does (the `VELOCITY_FORWARDING_SECRET` variable, else the
file named by `forwarding-secret-file` in `velocity.toml`), registers the channel with the servers, and then:

- keeps the master `plugins/xalichat/announcements.yml` (with its `announcements.state.json`) and shares it through
  [Redis](#redis) when `redis.enable` is on. A master edited by hand is shared the same way;
- relays a global broadcast or announcement to every server, the sender included, and logs it to its own console
  (`[Broadcast from <server>] <text>`; how far it was relayed is logged only with `debug: true`);
- sends every server the names of everyone on the proxy, for tab completion of `/msg`, whenever someone joins, leaves
  or vanishes (a burst of those sends one list, after `tab-completion.debounce`). With PremiumVanish on the proxy, a
  vanished player is left out for each player who cannot see them, as PremiumVanish decides;
- finds the player a `/msg` is for when they are on another server (by name, or the start of one), passes the message to
  that server, and tells the sender's server how it went: delivered, no such player online, or ignored (the receiving
  server checks the ignore list, since it holds it). Social spies are told on both servers. This is skipped when
  `cross-server-messages` is off;
- drops anything a player sends on the channel, and anything with a missing or wrong signature.

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

`chat/api` (`mc.xalitoria.chat.api`) lets a plugin take part without XaliChat knowing about it. List XaliChat as a
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
default), XaliChat adds its chat and command log to Plan's pages as an extension called XaliChat. The extension is
registered at startup, so a change to the `plan` section needs a restart. If Plan reloads, the extension is registered
again.

| Where | What |
|---|---|
| Player page, *Chat* tab | The number of messages the player sent on this server, and a table of their latest `plan.rows` (25 by default, at most 100). |
| Player page, *Commands* tab | The same for commands, as logged: without the labels in `logging.commands.ignored-commands`, so private messages and logins never show. |
| Server page | Messages and commands on this server in the last 24 hours. |

Only what `logging.chat.database` and `logging.commands.database` write to the database can be shown, so the chat and
command parts are left out when those are off (or with `plan.chat` or `plan.commands` set to `false`). Plan keeps 50
characters of a table value, so a long message or command is cut with an ellipsis; `/chatlog` has the full text. Plan
refreshes a player's data when they join and leave, and the server's now and then. Everyone who may see a player's page
in Plan sees their chat, so hand out Plan's web permissions accordingly. The Plan classes are touched only when Plan is
present. Plan is an optional dependency that loads before XaliChat.

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

Decoration names: `obfuscated`/`magic`/`k`, `bold`/`b`/`l`, `strikethrough`/`st`/`m`, `underline`/`u`/`n`,
`italic`/`i`/`em`/`o`, `reset`/`r`. Tag names: `click`, `head`, `hover`, `insert`, `key`, `score`, `sprite`,
`data`/`nbt`, `selector`/`sel`.

### Bypass

| Permission | Effect |
|---|---|
| `xalichat.bypass.ignore` | Others' ignore lists do not hide the player's chat. |
| `xalichat.bypass.message.ignore` | The player cannot be added to an ignore list, and a plugin that limits who may message a player (see [Working with other plugins](#working-with-other-plugins)) does not stop their private messages. |
| `xalichat.bypass.mute.players` | The player's chat still reaches players who muted global chat. |
| `xalichat.bypass.mute.broadcasts` | The player's broadcasts and announcements still reach players who muted broadcasts. |

### Everything else

`xalichat.reload`, `xalichat.message`, `xalichat.spam`, `xalichat.ignore`, `xalichat.ping`, `xalichat.setting.mute`,
`xalichat.setting.mute.players`, `xalichat.setting.mute.broadcasts`, `xalichat.command.nickname`,
`xalichat.command.nickname.others`, `xalichat.admin.spy.command`, `xalichat.admin.spy.social`,
`xalichat.admin.chatlog`, `xalichat.admin.chatlog.export`, `xalichat.admin.chatlog.import`,
`xalichat.admin.broadcast`, `xalichat.admin.broadcast.global`, `xalichat.admin.announce`,
`xalichat.admin.announce.global` and the four `xalichat.interactive.*` nodes are described in the tables above.

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

## Development

Sources are under `chat/<submodule>/kotlin`, tests under `chat/<submodule>/test` (see the
[root README](../README.md#source-layout)).

| Test | Covers |
|---|---|
| `chat/common` `Protocol` | Frames, signing, chunking and the message encodings. |
| `chat/common` `AnnouncementStore` | Reading, writing and merging `announcements.yml`. |
| `chat/common` `AnnouncementSync` | The sync between several participants, including skewed clocks. Needs a real Redis: skipped unless `XALI_REDIS_TEST` is set. |
| `chat/paper` `Colorizer` | Formatting permissions: what is stripped and what stays. |
| `chat/paper` `PlanExtension` | Plan's own validation of the extension's annotations, parameters and return types. |
| `chat/paper` `Filter`, `FilterPermutations` | Tools for the unfinished chat filter. |

```console
user $ ./gradlew :chat:common:test :chat:paper:test
user $ docker run -d --rm --name xali-redis-test -p 6390:6379 redis:7-alpine redis-server --requirepass secret
user $ XALI_REDIS_TEST=127.0.0.1:6390 XALI_REDIS_PASSWORD=secret ./gradlew :chat:common:test --rerun-tasks
```

Where things are: commands in `chat/paper/.../command`, event listeners in `listener`, ProtocolLib and Plan hooks in
`external`, the database in `data/db`, the configuration classes in `data/config`, announcements and interactive chat in
`modal`. The announcement sync itself has no platform code and lives in `chat/common/.../announce`, shared by both
plugins.

## See also

- [XaliFriends](../friends/README.md) — friend system that limits private messages to friends through the gate API
- [XaliMenu](../menu/README.md) — inventory menus that use the same text formatting
- [Gradle plugin](../xali-gradle/README.md) — descriptors and library loading
- [Plan](https://github.com/plan-player-analytics/Plan) — player analytics
