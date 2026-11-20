# Viber integration — Samsung Guard

Reads the Viber conversation branch straight out of Viber's own SQLite database
(`/data/data/com.viber.voip/databases/viber_messages`) and sends new messages to
the Telegram owner chat. Outgoing: opens the target chat via the `viber://chat?number=`
deep link so the owner can type and send manually.

## Why not the ContentProvider

`com.viber.voip.provider.vibermessages` / `vibercontacts` / `gallery` / `internal_files` are
all `exported=false` in the APK manifest, and `adb content query` returns
"Could not find provider" / "No result found" for every path. `method/copyAllDB`
from the shell `content` tool crashes with `NullPointerException` (missing
Bundle args). The provider is reachable only from Viber's own process.

Root is available on the target device, so the implementation reads the database
files directly as `root`.

## Read path

1. `su -c "cat /data/data/com.viber.voip/databases/viber_messages > ...snapshot.db"`
   plus the same for `viber_messages-wal`. Retried until copied sizes equal the
   live sizes (atomic-ish snapshot despite the live writer).
2. `ViberDb.rebuild()` replays the WAL itself: every frame in order, later frame
   wins for the same page → a consistent plain `.db`. (A straight copy lands
   mid-frame and breaks SQLite's WAL checksum.)
3. `MiniSqlite` parses the table b-tree leaf pages of `messages` /
   `conversations` / `participants_info`. No `sqlite3` binary is required — it is
   not present on this ROM.

## Fields used (`messages` table)

| column | meaning |
|---|---|
| `_id` | row id |
| `conversation_id` | links to `conversations._id` |
| `send_type` | 0 = incoming, 1 = outgoing |
| `msg_date` | ms since epoch |
| `body` | text, or media thumbnail URI |
| `extra_mime` | 0 text, 1 photo, 8 sticker, 10 video, 1000 file, … |
| `destination_uri` | `content://media/external/images/media/N` — readable by any app |
| `extra_uri` | internal_files thumbnail URI (not exported) |
| `msg_info` | JSON with `FileName`, `FileSize`, `MediaMetadata`, dimensions |

Media: `extra_uri` is Viber's internal provider (we cannot read it);
`destination_uri` is a public MediaStore URI and opens fine via `ContentResolver`.
Fallback: root-copies `/sdcard/Pictures/Viber/<FileName>`.

## Column order in the pager (important)

`MiniSqlite.cell()` returns raw record columns. Two rules bite:

1. Serial types in the record header are **varints**, not single bytes. Any
   TEXT/BLOB longer than 63 bytes gets type >= 128 and occupies two bytes;
   reading one byte per type shifts every following column.
2. `INTEGER PRIMARY KEY AUTOINCREMENT` is an alias of the rowid, so its column
   is stored as a NULL placeholder in the record. `cell()` substitutes the real
   rowid there. `sqlite_master` has no such column, so its col 0 stays as-is.

With both handled, pager indices match `pragma table_info` exactly — no +1
shifts anywhere.

## Write path (sending to Viber)

No exported API exists for programmatic sending. Supported flow:

- `ViberSender.openChat(ctx, "+375000000000")` → `viber://chat?number=...` opens the
  1:1 chat in Viber (verified on device).
- The owner types and taps "Отправить" themselves. Viber writes the outgoing row
  (`send_type=1`) into the same `messages` table, which the reader will pick up
  on the next scan.

This is deliberately **owner-confirmed only** — no silent auto-send into Viber,
mirroring D-03 (owner confirmation before any mutation).

## Polling

`ViberMonitor` runs inside `GuardService` on the single-thread scheduler:
every 60 s it scans the watched conversation for rows newer than the last seen
`msg_date` and forwards them to Telegram. The first scan only records the
timeline position (`primed at ts=…`) so the existing history is not replayed.

Text messages go as `<dir> Viber <time>` + sender number + body. Media
(`extra_mime != 0`) is exported via `exportMedia()` and uploaded with
`TelegramClient.sendFile()` (multipart `sendDocument`); if the file cannot be
read, a text notice is sent instead.

Root check (`su -c true`) is done once at init; if root disappears, Viber
monitoring stays silent instead of crashing.

## Known limits / gotchas

- WAL replay assumes big-endian frame headers and page size from the WAL
  header (verified on this build: magic 0x377f0682, page 4096).
- Only conversations with `deleted = 0` are scanned; secret chats and
  communities are not handled specially (`conversation_type` is exposed so
  filtering can be added).
- `participant_id` in `messages` references `participants._id`, which references
  `participants_info._id`; the number is resolved via `participantNumber()`.
- Big media (video/files >10 MB) copy through the app process; keep the cache
  trimmed.
