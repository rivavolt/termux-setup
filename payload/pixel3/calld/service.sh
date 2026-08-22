#!/system/bin/sh
# APatch/Magisk late_start service: bring up the calld supervisor at boot. Runs
# as root (magiskd's service context), which is what lets it exec calld and have
# calld self-drop to uid 2000 keeping gid 1005(audio)+3003(inet) — a supervisor
# that set those itself is how the inet group got lost before, so it doesn't.
#
# late_start runs once DE /data is mounted, BEFORE the user unlocks (measured:
# the supervisor was up 7s into a boot, phone still at the keyguard). Fine for
# calld: /data/local/tmp is DE, the sound card needs no unlock, and the tailnet
# gate just waits — the app's tun0 appears only after CE unlock, and the
# supervisor recycles calld onto it then. The supervisor idles until
# /data/local/tmp/calld.enabled exists, so this module is safe to install and
# leave in place — it won't contend for the sound card until explicitly enabled.
MODDIR=${0%/*}
export CALLD=/data/local/tmp/calld
# Detach so a supervisor crash-loop can never wedge the boot service thread.
setsid "$MODDIR/calld-supervise" >/dev/null 2>&1 &
