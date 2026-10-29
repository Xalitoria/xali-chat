# XaliChat

Paper chat plugin: formatted chat, private messages, nicknames, ignore/silence settings, spying, chat logging, interactive
chat tokens and optional DiscordSRV 3.0 (Ascension) integration. A small Velocity plugin relays what the servers send
each other.

## Modules

| Module | What it is |
|---|---|
| `chat/paper` | The Paper plugin, `xali-chat-<version>-paper.jar`. Everything below is this module unless it says otherwise. |
| `chat/velocity` | The Velocity plugin, `xali-chat-<version>-velocity.jar`: the [relay](#proxy-sync). It downloads kaml and kotlinx.serialization into `plugins/xalichat/libraries` the first time it starts (checksums are built into the jar), so the proxy needs internet access once. |
| `chat/api` | What other Paper plugins compile against to take part in XaliChat (see [Working with other plugins](#working-with-other-plugins)). It is shaded into the Paper jar, so the plugin that uses it lists XaliChat as a dependency. |
| `chat/common` | The wire format both plugins speak (signed frames, chunking, message encodings). No platform API, so both jars bundle it. Targets Java 17. |

The descriptors and the library loader of both plugins come from the [Gradle plugin](../plugin/README.md); the `paper { }` and
`velocity { }` blocks are in `chat/build.gradle.kts`.

`./gradlew :chat:paper:runServer` and `./gradlew :chat:velocity:runProxy` start the dev server and the dev proxy shared by
all modules (`run/folia` and `run/velocity`, see the [root README](../README.md)), with this plugin installed. Every jar is built into the repository's `build/libs/<module>/`, for example
`build/libs/chat/xali-chat-0.5.0-paper.jar`. The proxy there still needs its `velocity.toml` pointed at the server
and `player-info-forwarding-mode = "modern"`.

Formatting accepts legacy `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage tags, each limited
by the format permissions below.

## Commands

Every command's main label starts with `x`. Each alias works with and without the `x` (`/xbroadcast`, `/broadcast`,
`/xbc`, `/bc`), and each command is also a subcommand of `/xalichat` under its names without the `x`
(`/xalichat broadcast`, `/xalichat bc`). The tables below list the main label and the aliases without the `x`.

When another plugin (EssentialsX, for one) already owns a plain label such as `/reply` or `/nickname`, XaliChat takes it over
at startup and logs which labels it took. `/xsilence` has no `/mute` alias, so punishment plugins keep `/mute`.

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/xalichat reload` | `chat` | Reload the config, messages and `announcements.yml`. | `xalichat.reload` |
| `/xmessage <target> [message]` | `message`, `msg` | Send a private message, to a player on any server behind the proxy. | `xalichat.message` |
| `/xreply <message>` | `reply`, `r` | Answer the last player you exchanged a private message with, on any server. Forgotten when you leave the server. | `xalichat.message` |
| `/xspam say <repeat> <message>` | `spam` | Send a message repeatedly. | `xalichat.spam` |
| `/xspam stop` | | Stop your repeating message. | `xalichat.spam` |
| `/xignore <player>` | `ignore` | Toggle ignoring a player's chat and messages. | `xalichat.ignore` |
| `/xsilence players` | `silence` | Toggle hiding global chat from other players (your own setting). | `xalichat.setting.mute.players` |
| `/xsilence broadcasts` | `silence` | Toggle hiding broadcasts and announcements (your own setting). | `xalichat.setting.mute.broadcasts` |
| `/xcommandspy` | `commandspy` | Toggle seeing other players' commands. | `xalichat.admin.spy.command` |
| `/xsocialspy` | `socialspy` | Toggle seeing private messages. | `xalichat.admin.spy.social` |
| `/xnickname <nickname\|reset>` | `nickname`, `nick` | Set or clear your nickname. | `xalichat.command.nickname` |
| `/xnickname <player\|uuid> <nickname\|reset>` | `nickname`, `nick` | Set or clear another player's nickname, offline included. | `xalichat.command.nickname.others` |
| `/xbroadcast <message>` | `broadcast`, `bcast`, `bc` | Announce to every online player on this server and the console. | `xalichat.admin.broadcast` |
| `/xgbroadcast <message>` | `gbroadcast`, `gbcast`, `gbc` | The same on every server behind the proxy. | `xalichat.admin.broadcast.global` |
| `/xannounce <player> <announcement>` | `announce`, `ann` | Play an [announcement](#announcements) on this server for that player. | `xalichat.admin.announce` |
| `/xgannounce <player> <announcement>` | `gannounce`, `gann` | Play it on every server its `servers` list names. | `xalichat.admin.announce.global` |
| `/xview <id>` | `view` | Open an item/inventory/ender chest snapshot from chat. Used by the clickable tokens. | none |

The `g` commands (global) travel through the proxy, so they need [Velocity forwarding](#proxy-sync) and a player online to
carry the message. Without them they run on this server only and tell you so.

### `/chatlog`

Search logged chat. Every filter is optional and filters combine.

| Subcommand | Description | Permission |
|---|---|---|
| `/chatlog [player:<name\|uuid>] [since:<dur>] [text:<word>] [server:<name\|*>] [page:<n>]` | Search and page through logged chat, 9 entries per page. `since` takes durations like `30m`, `2h`, `1d`; `server` defaults to this server and `*` searches all. `page:<n>` alone reuses your last search for 5 minutes. | `xalichat.admin.chatlog` |
| `/chatlog help` | Show usage. | `xalichat.admin.chatlog` |
| `/chatlog export <filename> [filters]` | Write the matching entries as plaintext to `plugins/XaliChat/exports/<filename>.txt`. Takes the same filters as a search (except `page`). | `xalichat.admin.chatlog.export` |
| `/chatlog import <filename>` | View a previously exported file in the chat menu. Read-only: it never writes to the stored logs. | `xalichat.admin.chatlog.import` |

Export lines look like `2026-09-26 14:03:11 | <uuid> | <username> | <message>`. Filenames may only contain letters,
numbers, `.`, `_` and `-`, so they always stay inside the `exports` folder.

## Announcements

`plugins/XaliChat/announcements.yml` maps a key to an announcement. The proxy's copy is the master and is created, with two switched-off examples, on its first start; a server's file holds only the keys that name it. A server with no file reads what Redis holds first and creates its file from that, empty if no key names it; with Redis off it writes an empty file at once.

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
| `servers` | all | The servers that hold and run it, matched against their names in `velocity.toml` with `*` and `?` wildcards (`skyblock-*`). A server's `database.server-name` in `config.yml` has to be the same name. Empty means every server. `/xannounce` ignores the list. |
| `messages` | none | Chat lines, one message each; `""` is a blank line. |
| `commands` | none | Console commands, run in order after the messages. |

An announcement with a `delay` or `repeat` runs by itself on every server that matches `servers`. One with neither only
runs from `/xannounce` or `/xgannounce`.

- **Messages** go to every online player who hasn't muted broadcasts, and to the console. They take `&` codes,
  `&#RRGGBB` and MiniMessage tags (`<gradient:red:blue>`, `<click:open_url:...>`).
- **Placeholders** resolve for the player the announcement is run for: `{name}`, `{uuid}` (dashed), `%player_name%` and
  any PlaceholderAPI placeholder. A timer has no player, so each viewer gets their own (the console gets none).
  They work in commands too. Text from `/xbroadcast` is never expanded.
- **`delay`** is an internal command: it pauses the rest of the list instead of running anything. Each token is a
  number and a unit, added together: `t` ticks, `ms`, `s`, `m`, `h`, `d`; a bare number is seconds. `delay 2s 10t` waits
  2.5 seconds.
- Keys should be single words, since the commands take them as one argument.

### Where announcements live

Announcements are shared through [Redis](#redis), so the servers and the proxy agree on one set without a player online.
The proxy's `announcements.yml` holds every announcement; a server's file holds only the ones whose `servers` name it.
You can edit an announcement on any server, or the master on the proxy, and it ends up where it belongs:

- **Saving the file** offers each key that changed (new, edited or deleted) to Redis, which tells everyone else; each
  server the key names applies it. Nothing needs a reload, and the timers restart by themselves. Only keys whose content
  changed count: comments and layout don't.
- **The newest save wins.** Every key carries the time its file was saved, and an older copy never replaces a newer
  one, so an edit made on a server while it was down loses to a later one made elsewhere. This compares clocks, so keep
  the machines in sync.
- **A first start** has no file: the server reads what Redis holds and creates `announcements.yml` from the keys that
  name it (an empty file if none do, or after ten seconds if Redis doesn't answer; what arrives later is added).
- **Connecting** (startup, `/xalichat reload` and every five minutes) reads everything Redis holds and applies what is
  newer, then offers everything in the file, so a Redis that was wiped or down while you edited catches up. A key that
  was deleted elsewhere, or no longer names this server, is dropped from the file then.
- A file that fails to parse is logged, the announcements already loaded stay, and nothing is sent.

Updates from Redis are written into your file, which drops its comments; a file you save yourself is read as it is. The
times are kept in `announcements.state.json` beside it (on the proxy too), so don't copy a file without it.

### Redis

Put the same section in the `config.yml` of every server and of the proxy plugin (`plugins/xalichat/config.yml`):

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

It needs a restart. Redis holds the hash `<prefix>:announcements` (a key to `<modified>|<json>`, nothing after the bar for
a removal) and tells the channel `<prefix>:announcements:changed` the key whenever a save is taken. A save is taken
only if it is newer than the stored one, which Redis decides in a script, so two saves at once can't both win. Unlike
the plugin channel below, nothing here is signed: anyone who can write to that hash can add announcements that run
console commands, so give Redis a password and keep it off the public internet. If it can't be reached, it is retried
(1 second, doubling up to 30) and announcements keep working from the files.

### Broadcast formatting

`/xbroadcast` wraps the message in `commands.broadcast.format` of the language file. The sender's formatting is limited by
`xalichat.format.broadcast.*` (see [Formatting](#formatting)); a tag they may not use is removed, and so is a code
(`&c` without `.color`). Announcements are written by admins and use everything.

Broadcasts and announcements parse with their own `Colorizer`, not the shared color parser, so CMI gradients
(`{#a0a>}`) aren't supported there; use `<gradient:#a0a:#fff>` instead.

## Proxy sync

Turning on Velocity forwarding switches on three things between the servers and the proxy plugin, all over the
`xalichat:main` plugin channel: `/xgbroadcast`, `/xgannounce` and the player list for `/msg` completion
(announcement sync uses [Redis](#redis) instead). The signing secret is the forwarding secret the server already has: `secret` under
`proxies.velocity` in Paper's `config/paper-global.yml` (with `enabled: true`), read once at startup. The proxy plugin
gets the same value from the file named by `forwarding-secret-file` in `velocity.toml` (`forwarding.secret` by default),
so setting up Velocity's modern forwarding is all it takes. Without it these features are off. The secret signs every one
of these messages: a client can send to a plugin channel too, and a forged announcement would run console commands.

A plugin message needs a player's connection to ride on, so a message needs a player online. The proxy side is
`chat/velocity`; this is the format it and the server speak (the code is in `chat/common`). All integers are big-endian
and strings use Java's `writeUTF`.

| Frame | Layout | Meaning |
|---|---|---|
| `SIGNED` | `UTF "SIGNED"`, 32-byte HMAC-SHA256 of the rest, then the message | Wraps every message below. The proxy computes the HMAC with the forwarding secret, as UTF-8 bytes. |
| `CHUNK` | `UTF "CHUNK"`, `UTF id`, `int index`, `int total`, bytes | One slice (at most 30 000 bytes) of a message too big for one plugin message (Bukkit's limit is 32 766). The slices, in index order, are the whole `SIGNED` frame. At most 64 slices, and an unfinished transfer is dropped after 30 seconds. |
| `BROADCAST` | `UTF "BROADCAST"`, `boolean bypassMute`, Adventure JSON of the message | Server to proxy from `/xgbroadcast`; proxy to every server, including the one that sent it. |
| `ANNOUNCE` | `UTF "ANNOUNCE"`, `UTF key`, `UTF uuid`, `UTF name` (may be empty) | Server to proxy from `/xgannounce`; proxy to every server, including the sender. Servers not in the announcement's `servers` list skip it. |

Unsigned or wrongly signed frames are ignored.

### Velocity relay

Install `xali-chat-<version>-velocity.jar` in the proxy's `plugins` folder; it needs no config unless you use Redis. It finds the forwarding
secret the way Velocity does (the `VELOCITY_FORWARDING_SECRET` variable, else the file named by `forwarding-secret-file`
in `velocity.toml`), registers the channel with the servers, and then:

- keeps the master `plugins/xalichat/announcements.yml` (with its `announcements.state.json`) and shares it through
  [Redis](#redis) when `redis.enable` is on. Edit the master by hand and it is shared the same way;
- relays a global broadcast or announcement to every server, the sender included, and logs it to its own console
  (`[Broadcast from <server>] <text>`; how far it was relayed is only logged with `debug: true`);
- sends every server the names of everyone on the proxy, for tab-completing `/msg`, whenever someone joins, leaves or
  vanishes (a burst of those sends one list, after `tab-completion.debounce`). With PremiumVanish on the proxy, a
  vanished player is left out for each player who can't see them, as PremiumVanish decides;
- finds the player a `/msg` is for when they are on another server (unless `cross-server-messages` is off, see below) (by name, or the start of one), passes the message
  to that server, and tells the sender's server how it went: delivered, no such player online, or ignored (the
  receiving server checks the ignore list, since it is the one that has it). Social spies are told on both servers;
- drops anything a player sends on the channel, and anything with a missing or wrong signature.

Private messages between servers can be switched off for the whole proxy (`cross-server-messages.enable` in its
`config.yml`), limited to some servers (`cross-server-messages.servers`, wildcards like `skyblock-*`; a message needs the
sender's and the receiver's server both listed), or switched off for one server with `cross-server-messages: false` in
that server's `config.yml`. A server that is out gets "not found" for players elsewhere and is not found itself. A
vanished player (SuperVanish/PremiumVanish) the sender can't see counts as not found, on this server and through the proxy.

The first start writes `plugins/xalichat/config.yml` (`debug`, `tab-completion`, `cross-server-messages`, `redis`) and a
master `announcements.yml` holding two switched-off examples, so there is something to edit.

`servers` patterns are matched against the server names in `velocity.toml`, and each server checks them against its own
`database.server-name`, so keep the two the same.

Only a file that is valid YAML is synced. A save that doesn't parse (on the proxy or a server) is logged and changes
nothing; the last working version is written beside it as `announcements.yml.bak`, so the fix is an edit away.

## Working with other plugins

`chat/api` (`mc.xalitoria.chat.api`) is how a plugin takes part without XaliChat knowing about it. List XaliChat as a
dependency of the plugin (`join-classpath` on, optional if the plugin can do without), and compile against
`compileOnly(project(":chat:api"))`. Both are found through Bukkit's services manager.

- **`PrivateMessageGate`** is registered by the plugin: `servicesManager.register(PrivateMessageGate::class.java, gate, plugin, ServicePriority.Normal)`.
  XaliChat asks every gate before it delivers a private message to a player on its server, for a sender on that server
  and for one that reached it through the proxy. If any says no, the sender is told `"{0} isn't accepting messages from you."`
  (`commands.message.declined` in the language file) and nothing is delivered. The question is asked on the main thread
  for every message, so a gate answers from memory. Senders with `xalichat.bypass.message.ignore` skip the gates.
  [XaliFriends](../friends/README.md) uses it for players who take messages from friends only.
- **`NetworkPlayers`** is provided by XaliChat: `isOnline(name, viewer)` says whether a player is online anywhere behind the
  proxy (as the proxy last told this server) and not vanished from the viewer. Without a proxy only this server's players count.

A plugin that carries its own copy of the Kotlin runtime can't register Kotlin-typed things with another plugin
(a `KSerializer` or a `KClass` from its copy is a different class from the other plugin's), which is why both of these
use only Bukkit and Java types.

## Interactive chat

Typed tokens in chat are replaced with hoverable, clickable components. Disable the whole feature with
`interactive-chat: false` in the main config.

| Token | Shows | Permission |
|---|---|---|
| `[item]` | The item in your main hand. | `xalichat.interactive.item` |
| `[inv]` | A snapshot of your inventory (`/xview`). | `xalichat.interactive.inventory` |
| `[ender]` | A snapshot of your ender chest (`/xview`). | `xalichat.interactive.ender` |
| `[ping]` | Your latency. | `xalichat.interactive.ping` |

With DiscordSRV installed the tokens are sent to Discord as plain text, MiniMessage tags and colours are stripped from
outgoing messages, and colours in Discord messages are limited by `xalichat.format.discord` of the linked player
(unlinked users get none).

## Permissions

### Formatting

Each prefix below accepts the same sub-nodes (`<prefix>.gradient`, `.color`, `.shadow`, `.decor.<name|*>`,
`.tag.<name|*>`). Formatting you lack is stripped from the message.

| Prefix | Applies to |
|---|---|
| `xalichat.format.global` | Public chat (default prefix). |
| `xalichat.format.discord` | Messages coming from Discord. |
| `xalichat.format.broadcast` | `/xbroadcast` and `/xgbroadcast`. |
| `xalichat.format.nickname` | Your own nickname. |
| `xalichat.format.nickname.others` | Nicknames set on others. |

Decoration names: `obfuscated`/`magic`/`k`, `bold`/`b`/`l`, `strikethrough`/`st`/`m`, `underline`/`u`/`n`,
`italic`/`i`/`em`/`o`, `reset`/`r`. Tag names: `click`, `head`, `hover`, `insert`, `key`, `score`, `sprite`,
`data`/`nbt`, `selector`/`sel`.

### Bypass

| Permission | Effect |
|---|---|
| `xalichat.bypass.ignore` | Others' ignore lists don't hide your chat. |
| `xalichat.bypass.message.ignore` | You can't be added to an ignore list, and a plugin that limits who may message a player (see below) doesn't stop your private messages. |
| `xalichat.bypass.mute.players` | Your chat still reaches players who muted global chat. |
| `xalichat.bypass.mute.broadcasts` | Your broadcasts and announcements still reach players who muted broadcasts. |

### Everything else

`xalichat.reload`, `xalichat.message`, `xalichat.spam`, `xalichat.ignore`, `xalichat.ping`, `xalichat.setting.mute`,
`xalichat.setting.mute.players`, `xalichat.setting.mute.broadcasts`, `xalichat.command.nickname`,
`xalichat.command.nickname.others`, `xalichat.admin.spy.command`, `xalichat.admin.spy.social`,
`xalichat.admin.chatlog`, `xalichat.admin.chatlog.export`, `xalichat.admin.chatlog.import`,
`xalichat.admin.broadcast`, `xalichat.admin.broadcast.global`, `xalichat.admin.announce`,
`xalichat.admin.announce.global` and the four `xalichat.interactive.*` nodes are described in the tables above.
