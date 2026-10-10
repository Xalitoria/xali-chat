# XaliChat

Paper chat plugin: formatted chat, private messages, nicknames, ignore/mute settings, spying, chat logging, interactive
chat tokens and optional DiscordSRV 3.0 (Ascension) integration.

Formatting accepts legacy `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage tags, each limited
by the format permissions below.

## Commands

Every command's main label starts with `x`. Each alias works with and without the `x` (`/xbroadcast`, `/broadcast`,
`/xbc`, `/bc`), and each command is also a subcommand of `/xalichat` under its names without the `x`
(`/xalichat broadcast`, `/xalichat bc`). The tables below list the main label and the aliases without the `x`.

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/xalichat reload` | `chat` | Reload the config, messages and `announcements.yml`. | `xalichat.reload` |
| `/xmessage <target> [message]` | `message`, `msg` | Send a private message. | `xalichat.message` |
| `/xspam say <repeat> <message>` | `spam`, `alispam` | Send a message repeatedly. | `xalichat.spam` |
| `/xspam stop` | | Stop your repeating message. | `xalichat.spam` |
| `/xignore <player>` | `ignore` | Toggle ignoring a player's chat and messages. | `xalichat.ignore` |
| `/xmute players` | `mute` | Toggle hiding global chat from other players (your own setting). | `xalichat.setting.mute.players` |
| `/xmute broadcasts` | `mute` | Toggle hiding broadcasts and announcements (your own setting). | `xalichat.setting.mute.broadcasts` |
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

`plugins/XaliChat/announcements.yml` maps a key to an announcement. A first run writes a disabled `example`.

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
| `servers` | all | Servers that run it automatically or from a global command. They match `database.server-name` in `config.yml`, so give each server the name your proxy knows it by. `/xannounce` ignores the list. |
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

Saving the file reloads it: the timers restart and, with the proxy set up, the file is pushed to the proxy. Your own
comments stay, as the plugin only rewrites the file when it receives it from the proxy. A file that fails to parse is
logged and the announcements already loaded stay. `/xalichat reload` does the same.

### Broadcast formatting

`/xbroadcast` wraps the message in `commands.broadcast.format` of the language file. The sender's formatting is limited by
`xalichat.format.broadcast.*` (see [Formatting](#formatting)); a tag they may not use is removed, and so is a code
(`&c` without `.color`). Announcements are written by admins and use everything.

Broadcasts and announcements parse with their own `Colorizer`, not the shared color parser, so CMI gradients
(`{#a0a>}`) aren't supported there; use `<gradient:#a0a:#fff>` instead.

## Proxy sync

There is nothing to configure in `config.yml`. Turning on Velocity forwarding switches on three things between the
server and the proxy plugin, all over the `xalichat:main` plugin channel: pushing `announcements.yml` when it is saved,
`/xgbroadcast` and `/xgannounce`. The signing secret is the forwarding secret the server already has: `secret` under
`proxies.velocity` in Paper's `config/paper-global.yml` (with `enabled: true`), read once at startup. The proxy plugin
gets the same value from the file named by `forwarding-secret-file` in `velocity.toml` (`forwarding.secret` by default),
so setting up Velocity's modern forwarding is all it takes. Without it these features are off. The secret signs every one
of these messages: a client can send to a plugin channel too, and a forged config would run console commands.

A plugin message needs a player's connection to ride on, so a message waits for the first player online. The proxy side
isn't part of this repository; this is what it has to speak. All integers are big-endian and strings use Java's
`writeUTF`.

| Frame | Layout | Meaning |
|---|---|---|
| `SIGNED` | `UTF "SIGNED"`, 32-byte HMAC-SHA256 of the rest, then the message | Wraps every message below. The proxy computes the HMAC with the forwarding secret, as UTF-8 bytes. |
| `CHUNK` | `UTF "CHUNK"`, `UTF id`, `int index`, `int total`, bytes | One slice (at most 30 000 bytes) of a message too big for one plugin message (Bukkit's limit is 32 766). The slices, in index order, are the whole `SIGNED` frame. At most 64 slices, and an unfinished transfer is dropped after 30 seconds. |
| `CONFIG_SYNC` | `UTF "CONFIG_SYNC"`, `UTF "announcements.yml"`, file bytes | Server to proxy when the file is saved; proxy to servers to apply it. A server never sends back what it just received, so the proxy can relay it to every other server without looping. |
| `BROADCAST` | `UTF "BROADCAST"`, `boolean bypassMute`, Adventure JSON of the message | Server to proxy from `/xgbroadcast`; proxy to every server, including the one that sent it. |
| `ANNOUNCE` | `UTF "ANNOUNCE"`, `UTF key`, `UTF uuid`, `UTF name` (may be empty) | Server to proxy from `/xgannounce`; proxy to every server, including the sender. Servers not in the announcement's `servers` list skip it. |

The proxy also needs to `minecraft:register` the channel with each server, as Velocity does for channels a proxy plugin
registers. Unsigned or wrongly signed frames are ignored.

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
| `xalichat.bypass.message.ignore` | You can't be added to an ignore list. |
| `xalichat.bypass.mute.players` | Your chat still reaches players who muted global chat. |
| `xalichat.bypass.mute.broadcasts` | Your broadcasts and announcements still reach players who muted broadcasts. |

### Everything else

`xalichat.reload`, `xalichat.message`, `xalichat.spam`, `xalichat.ignore`, `xalichat.ping`, `xalichat.setting.mute`,
`xalichat.setting.mute.players`, `xalichat.setting.mute.broadcasts`, `xalichat.command.nickname`,
`xalichat.command.nickname.others`, `xalichat.admin.spy.command`, `xalichat.admin.spy.social`,
`xalichat.admin.chatlog`, `xalichat.admin.chatlog.export`, `xalichat.admin.chatlog.import`,
`xalichat.admin.broadcast`, `xalichat.admin.broadcast.global`, `xalichat.admin.announce`,
`xalichat.admin.announce.global` and the four `xalichat.interactive.*` nodes are described in the tables above.
