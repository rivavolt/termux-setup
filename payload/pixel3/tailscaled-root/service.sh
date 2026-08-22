#!/system/bin/sh
# APatch/Magisk late_start service: the root tailscaled in netstack mode with
# DE state (/data/adb/tailscale), so the tailnet survives BFU — the app's
# tailscaled is CE-locked until the user unlocks; this one is not. late_start
# runs BFU on DE /data (measured on this phone: calld's supervisor was up 7s
# into a boot). Netstack (--tun=userspace-networking) installs zero routes and
# rules, so it cannot fight the app's tun0; inbound tailnet TCP forwards to
# localhost, which reaches adb 5555, calld 8790, and sshd 8022 via 100.64.0.43.
TS=/data/adb/tailscale
LOGFILE=$TS/boot.log
log() {
  line="$(date '+%F %T') tailscaled-root[$$]: $*"
  echo "$line" >> "$LOGFILE" 2>/dev/null
  if [ "$(wc -c < "$LOGFILE" 2>/dev/null || echo 0)" -gt 65536 ]; then
    tail -n 200 "$LOGFILE" > "$LOGFILE.t" 2>/dev/null && mv "$LOGFILE.t" "$LOGFILE"
  fi
  /system/bin/log -t tailscaled-root "$*" 2>/dev/null || true
}

[ -e "$TS/disabled" ] && { log "disabled flag set, not starting"; exit 0; }
[ -x "$TS/tailscaled" ] || { log "no executable tailscaled at $TS, not starting"; exit 0; }

# Android has no /etc/resolv.conf, so tailscaled's Go resolver is dead and the
# control-server lookup leans on the DERP DoH bootstrap — slow and flaky on
# weak links, which BFU Wi-Fi is. Pin hs/derp.avolt.net by bind-mounting our
# hosts file over the system one, in init's mount namespace so every process
# sees it. Idempotent: skip when the pin is already visible.
if ! nsenter -t 1 -m -- grep -q hs.avolt.net /system/etc/hosts 2>/dev/null; then
  nsenter -t 1 -m -- mount --bind "$TS/hosts" /system/etc/hosts 2>/dev/null \
    && log "hosts pin mounted" \
    || log "hosts pin FAILED, continuing on DoH bootstrap"
fi

# Keep-alive loop, calld-supervise's shape but simpler: netstack adds no
# routing state, so a restart cannot wedge anything — plain restart with capped
# backoff is enough. A tailscaled that is already up (hand-started via
# tuntest.sh) is left alone; the loop adopts the slot when it dies. Detached so
# a crash-loop can never wedge the boot service thread.
(
  backoff=5
  while :; do
    [ -e "$TS/disabled" ] && { log "disabled flag set, exiting"; exit 0; }
    if [ -n "$(pidof tailscaled)" ]; then sleep 30; continue; fi
    if [ "$(wc -c < "$TS/tailscaled.log" 2>/dev/null || echo 0)" -gt 1048576 ]; then
      tail -c 262144 "$TS/tailscaled.log" > "$TS/tailscaled.log.t" 2>/dev/null && mv "$TS/tailscaled.log.t" "$TS/tailscaled.log"
    fi
    log "starting tailscaled (netstack)"
    cd "$TS" && ./tailscaled --state=state/tailscaled.state --statedir=state \
      --socket=tailscaled.sock --port=41642 --tun=userspace-networking \
      --no-logs-no-support >> "$TS/tailscaled.log" 2>&1
    log "tailscaled exited rc=$?, restarting in ${backoff}s"
    sleep "$backoff"
    [ "$backoff" -lt 60 ] && backoff=$((backoff * 2)) || backoff=60
  done
) </dev/null >/dev/null 2>&1 &
log "keep-alive armed"
