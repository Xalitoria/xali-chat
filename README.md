# XaliChat

Paper chat plugin: formatted chat, private messages, nicknames, ignore/mute settings, spying, chat logging, interactive
chat tokens and optional DiscordSRV 3.0 (Ascension) integration.

Formatting accepts legacy `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage tags, each limited
by the format permissions below.

## Commands

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/xalichat reload` | `/xchat`, `/chat` | Reload the config and messages. | `xalichat.reload` |
| `/xmessage <target> [message]` | `/xmsg`, `/msg` | Send a private message. | `xalichat.message` |
| `/xalispam say <repeat> <message>` | `/xspam`, `/spam` | Send a message repeatedly. | `xalichat.spam` |
| `/xalispam stop` | | Stop your repeating message. | `xalichat.spam` |
| `/xignore <player>` | | Toggle ignoring a player's chat and messages. | `xalichat.ignore` |
| `/xmute players` | | Toggle hiding global chat from other players (your own setting). | `xalichat.setting.mute.players` |
| `/xmute broadcasts` | | Toggle hiding `/broadcast` messages (your own setting). | `xalichat.setting.mute.broadcasts` |
| `/commandspy` | | Toggle seeing other players' commands. | `xalichat.admin.spy.command` |
| `/socialspy` | | Toggle seeing private messages. | `xalichat.admin.spy.social` |
| `/nickname <nickname\|reset>` | `/nick` | Set or clear your nickname. | `xalichat.command.nickname` |
| `/nickname <player\|uuid> <nickname\|reset>` | `/nick` | Set or clear another player's nickname, offline included. | `xalichat.command.nickname.others` |
| `/broadcast <message>` | `/bc` | Announce to every online player and the console. | `xalichat.admin.broadcast` |
| `/xview <id>` | | Open an item/inventory/ender chest snapshot from chat. Used by the clickable tokens. | none |

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
| `xalichat.format.broadcast` | `/broadcast`. |
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
| `xalichat.bypass.mute.broadcasts` | Your broadcasts still reach players who muted broadcasts. |

### Everything else

`xalichat.reload`, `xalichat.message`, `xalichat.spam`, `xalichat.ignore`, `xalichat.ping`, `xalichat.setting.mute`,
`xalichat.setting.mute.players`, `xalichat.setting.mute.broadcasts`, `xalichat.command.nickname`,
`xalichat.command.nickname.others`, `xalichat.admin.spy.command`, `xalichat.admin.spy.social`,
`xalichat.admin.chatlog`, `xalichat.admin.chatlog.export`, `xalichat.admin.chatlog.import`,
`xalichat.admin.broadcast` and the four `xalichat.interactive.*` nodes are described in the tables above.
