#!/system/bin/sh
# Guard helper — runs as root from /data/adb/service.d/ at boot.
#
# Two jobs:
#  1) Once a minute, copy Viber's SQLite DB (+ WAL) where the app can read it.
#     SELinux forbids `su` from the app's uid, so the app cannot do this itself.
#  2) Poll the app's `outbox/` for reply commands and deliver them into Viber.
#     The app cannot tap the screen either, so the send happens here.
#
# Reply command file: <epoch>.req
#   line 1: phone number, e.g. +375000000000
#   line 2: base64(UTF-8 text)
# On completion a matching <epoch>.ack is written, containing "ok" on success.
#
# Delivery uses ACTION_SEND, so the text travels inside the Intent and any
# Unicode survives (`input text` cannot type Cyrillic at all). The app then
# drives Viber's "Переслать в" picker: type the number, tap the resolved row,
# tap the FAB. Button positions come from a live uiautomator dump — never
# hard-coded, so keyboard state does not break it.

APP=com.cybermiller.guard
VDIR=/data/data/com.viber.voip/databases
FILES=/sdcard/Android/data/$APP/files
OUT="$FILES/viber"
INBOX="$FILES/outbox"
ACK="$FILES/outbox_ack"
XML=/data/local/tmp/guard_ui.xml

mkdir -p "$OUT" "$INBOX" "$ACK" 2>/dev/null
chmod 777 "$INBOX" "$ACK" 2>/dev/null

# Print "x y": centre of the first node whose resource-id is exactly $1.
find_node() {
    rm -f "$XML"
    uiautomator dump "$XML" >/dev/null 2>&1 || return 0
    [ -f "$XML" ] || return 0
    grep -o "resource-id=\"$1\"[^>]*bounds=\"[^\"]*\"" "$XML" |
        head -n 1 |
        while read -r line; do
            b=${line##*bounds=\"}
            b=${b%\"}
            b=${b%]}
            left=${b%%]\[*}
            right=${b#*]\[}
            l=${left#\[}
            t=${right%%,*}
            r=${right#*,}
            bottom=${r##*,}
            echo $(( (l + t) / 2 )) $(( (r + bottom) / 2 ))
        done
}

snapshot_db() {
    pidof com.viber.voip >/dev/null 2>&1 || return 0
    cat "$VDIR/viber_messages" > "$OUT/snapshot.db" 2>/dev/null
    cat "$VDIR/viber_messages-wal" > "$OUT/snapshot.db-wal" 2>/dev/null
    chmod 666 "$OUT"/snapshot.db* 2>/dev/null
}

deliver_reply() {
    req="$1"
    num=$(sed -n 1p "$req" 2>/dev/null)
    b64=$(sed -n 2p "$req" 2>/dev/null)
    name=$(basename "$req" .req)

    if [ -z "$num" ] || [ -z "$b64" ]; then
        echo "bad request" > "$ACK/$name.ack"
        rm -f "$req"
        return
    fi

    text=$(echo "$b64" | base64 -d 2>/dev/null)
    if [ -z "$text" ]; then
        echo "bad base64" > "$ACK/$name.ack"
        rm -f "$req"
        return
    fi

    am force-stop com.viber.voip
    sleep 1
    am start -a android.intent.action.SEND -t text/plain \
        --es android.intent.extra.TEXT "$text" \
        -p com.viber.voip >/dev/null 2>&1
    sleep 4

    # Field must be focused before input text works. Then Viber requires
    # the "+" country prefix ("+375000000000"), not "375…".
    set -- $(find_node "com.viber.voip:id/add_recipients_search_field")
    [ -n "$1" ] && input tap "$1" "$2"
    sleep 1
    input text "$num"

    # Poll up to 8 s for the resolved recipient row.
    row=""
    for _ in $(seq 1 16); do
        set -- $(find_node "com.viber.voip:id/add_number_layout")
        [ -n "$1" ] && { row="$1 $2"; break; }
        sleep 0.5
    done

    if [ -z "$row" ]; then
        echo "no recipient row" > "$ACK/$name.ack"
        rm -f "$req"
        return
    fi
    set -- $row
    input tap "$1" "$2"
    sleep 2

    # Poll up to 8 s for the send button.
    btn=""
    for _ in $(seq 1 16); do
        set -- $(find_node "com.viber.voip:id/fab_send")
        [ -n "$1" ] && { btn="$1 $2"; break; }
        sleep 0.5
    done

    if [ -z "$btn" ]; then
        echo "no send button" > "$ACK/$name.ack"
        rm -f "$req"
        return
    fi
    set -- $btn
    input tap "$1" "$2"
    sleep 2

    echo "ok" > "$ACK/$name.ack"
    rm -f "$req"
}

tick=0
while true; do
    for req in "$INBOX"/*.req; do
        [ -f "$req" ] || continue
        deliver_reply "$req"
    done

    # Snapshot roughly once a minute; this loop turns every 5 s for replies.
    if [ $((tick % 12)) -eq 0 ]; then
        snapshot_db
    fi

    tick=$((tick + 1))
    sleep 5
done