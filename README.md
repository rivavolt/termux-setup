# phone-config

Desired state for the fleet's Android phones, converged from a workstation.
The phone is a target, not a runtime: `phone` (babashka) drives everything
over ssh (Termux plane, port 8022) and adb (Android plane), checks state
before mutating, and re-running is always safe. The only things living
on-device are the pushed payload files — notably the Termux:Boot hooks,
which must work at boot with no host around.

## Usage

```
./phone apply  [device]    converge (default: pixel8)
./phone verify [device]    check only, exit non-zero on drift
./phone onboard <serial>   first contact over USB adb, then: phone apply
```

## Layout

```
phone                       entry: device roster + CLI dispatch (ssh details come from
                            the workstation's rendered ~/.ssh/config, not from here)
src/engine.clj              step registry + converge loop (check → apply → re-check)
src/transport.clj           ssh / adb / content-addressed file push
src/android16_exec.clj      workaround with a sunset — delete the file when upstream fixes it
src/always_on_vpn.clj       policy: the tailnet survives reboots (lockdown OFF, with why)
src/airplane_radios.clj     policy: airplane mode spares wifi + bluetooth
src/fast_animations.clj     policy: animation scales at half duration
src/wifi_scan_throttle.clj  policy: wi-fi scan throttling off
src/storage.clj             policy: Termux reads shared storage (appop + ~/storage farm)
src/wireless_adb.clj        policy: adb reachable across reboots (toggle + bootstrap + hook)
src/termux_boot.clj         policy: boot hooks actually fire (Boot APK + doze exemptions)
src/process_survival.clj    policy: the plane survives reboot AND mid-uptime app kills
                            (phantom-killer off, adbd port persist, termux-plane-up + hook)
src/sshd.clj                policy: reachable over ssh (keys, config, supervision)
src/socks_proxy.clj         policy: the fleet can egress through the phone (converges to
                            down; `<device>-proxy on` runs it)
src/userland.clj            policy: the dev environment (packages, zsh, ui config, doctl/gcloud)
src/nixos_config.clj        the nixos-config seam: authorized_keys + ssh_config rendered
                            fresh at apply time — never vendored
src/calld.clj               policy: the call daemon on the phone — deploys the binary
                            (adb push) + a root supervisor module (see calld supervision)
src/onboard.clj             USB first-contact flow (runs before ssh exists)
sshd_config.d/listen.conf   drop-in for $PREFIX/etc/ssh/sshd_config.d/
termux-adb-bootstrap        re-pins adbd to TCP 5555 (runs on-device at boot)
termux-plane-up             brings the plane up (wake-lock + runsvdir + sv up); called
                            by the boot hook and by ssh (adb recovery launches the
                            Termux activity instead — see restart-ssh)
.termux/boot/20-plane-up    Termux:Boot hook: runs termux-plane-up
```

Files group by the policy that carries a rationale, never by mechanism —
`always_on_vpn.clj` answers "why is lockdown off" by existing. Registration
order is execution order, declared once in `phone`. Workarounds get their own
file named for the specific problem (`android16_exec.clj`); the filename
carries the sunset, and deleting the file is the whole change when it arrives.
Generated files are rendered from nixos-config at apply time rather than
vendored: a committed render is a cache with no invalidation (the old
sync-keys shipped a stale key set for months before this rewrite caught it).

## Onboarding a new phone

Plug it in with adb authorized, sign Tailscale in against https://hs.avolt.net
(the one UI step), register the node server-side, then:

```
./phone onboard <adb-serial>   # Termux APKs, fleet adb key, wireless debugging
./phone apply <device>         # after adding the device to the table in ./phone
```

`onboard` drives Termux through `run-as com.termux` (debug builds are
debuggable). Both APKs are debug builds, which Play Protect refuses over adb on
a stock ROM, so every install runs with the adb-install verifier off and
restores it afterwards. The fleet adb client key it seeds is already authorized
(it authorized over USB), so the wireless-debug connect needs no pairing
dialog, ever.

Ordering trap, learned the hard way: `adb tcpip 5555` restarts adbd, which
kills anything started via `run-as` over that same transport — a just-started
sshd included. It is deliberately the last thing onboard does. On some devices
(Pixel 8) the 5555 bind also dies on USB unplug, not just reboot; recovery
without a cable is a reboot (Termux:Boot re-runs the bootstrap) or
`ssh -p 8022 <device> termux-adb-bootstrap`.

Two management planes, each recovering the other while the phone stays up. adb
(adbd pinned to TCP 5555 by `persist.adb.tcp.port`) is the more durable one: it
survives the Termux app being killed, so it heals ssh. ssh rides the Termux app,
so it dies whenever the app does. When adb is down (5555 lost) but ssh is up,
recover adb via `ssh -p 8022 <device> termux-adb-bootstrap`. When ssh is down but
adb is up (the common case — Android killed the app mid-uptime), recover ssh via
`<device>-adb restart-ssh`, which launches the Termux activity over adb — its
login shell brings up runsvdir (termux-services) and sshd restarts with it.
(Not RunCommandService: its RUN_COMMAND permission is not reliably held by the
adb caller after a reboot — refused even as root — whereas launching the
activity needs no permission.)

Which plane is the durable one can invert on an unrooted phone.
`persist.adb.tcp.port` is root-only, so a stock phone keeps adb across a reboot
solely by the boot hook re-finding the wireless-debug port — and that presumes
the Wireless Debugging toggle itself survived. Measured on the Pixel 2 XL (stock
Android 11): after a reboot Tailscale returned on its own and sshd came back 49s
in via Termux:Boot, but `init.svc.adbd` was `stopped` and stayed that way, so adb
needed a manual toggle in Developer options while ssh never went away. Treat adb
as the durable plane only where root pins the port.

How far that generalizes is untested: pixel2 is the only phone here observed
across a reboot. nothing1 is not a counterexample — it shows `adbd=running` with
no persist prop, but on 5+ days of uptime, so its toggle was armed by hand and
never tested against a boot. Settling whether the cause is Android 11, the
Pixel 2, or unrooted stock in general means deliberately rebooting a stock phone
and watching `init.svc.adbd`.

Re-arming the toggle from the device is not possible, though not for the reason
it first appears: Termux DOES declare WRITE_SECURE_SETTINGS and `pm grant` grants
it. The blocker is the `settings`/`cmd settings` CLI, which refuses any app uid —
it calls getCurrentUser() (wanting INTERACT_ACROSS_USERS) and `put --user 0`
wants MANAGE_USERS. Reaching `Settings.Global.putInt` through the API instead of
the CLI (a dex under `/system/bin/dalvikvm`, running as Termux with the grant)
is the one route not tried.

A REBOOT of a PIN-locked phone needs care, but on a ROOTED phone it is NOT a
one-way trip: given adb access you can unlock it remotely, without ever touching
the keyguard or the SIM prompt —

    adb ... shell su -c 'locksettings verify --old <DEVICE_PIN>'
      → "Lock credential verified successfully"

goes through LockSettingsService and unlocks CE storage (BFU → AFU), spending no
SIM attempt and needing no `input`/`wm dismiss-keyguard`. Use `verify`
(non-destructive); NEVER `clear`, which destroys the credential. A wrong device
PIN costs only a timed backoff — unlike the SIM PIN, which PUK-locks after ~3
tries, so never inject a device PIN into the SIM prompt (`getprop gsm.sim.state`
= PIN_REQUIRED/PUK_REQUIRED/LOADED distinguishes the two; `mDreamingLockscreen`
cannot). Measured on the Pixel 3 after a real reboot: `locksettings verify`
unlocked CE, then tailnet returned at t+10s and sshd (Termux:Boot → 20-plane-up)
at t+50s — the whole plane recovered on its own.

The real constraint is narrower: you must REACH adbd during BFU. With the phone
on a USB host there is no lottery at all: plain `adb devices` as the NORMAL
user — systemd's uaccess tag grants the device ACL to the seated user. Do NOT
`sudo adb`: a root-owned adb server presents root's (unauthorized) key and the
handset answers `unauthorized`, which looks exactly like a permissions problem
and isn't — kill root's server and rerun plain. Over the network it is flakier:
adbd comes back on 5555 (the persist prop survives) and answers
on the LAN, but the Wi-Fi association drops and returns while the phone is still
BFU (CE-encrypted supplicant config), so the recovery window is flaky, not
absent — adbd was reachable again at 2141s uptime, far past the first ~50s
window, i.e. availability recurs rather than closing permanently. Retry the LAN
address until it answers, then `locksettings verify`. Cellular does NOT help
while the SIM is at its PIN. The durable fix, if wanted, is a DE-stored Wi-Fi
network the phone can join pre-unlock (unverified on this build). Bottom line:
still design supervision that never needs a reboot, but a reboot is recoverable
remotely on this rooted handset — it is not the dead end it first appeared.

The SIM PIN can also be entered remotely, which brings cellular back without
touching the handset — but only as per-digit keyevents:
`input keyevent $((7+d))` for each digit d, then `input keyevent 66` to
confirm. `input text` on the SIM prompt silently goes nowhere (rc=0, nothing
typed), so a text-injection attempt looks like a wrong PIN was swallowed when
in fact no digits arrived. Read the outcome from `getprop gsm.sim.state`
(PIN_REQUIRED → LOADED), never from the screen: the SIM prompt is FLAG_SECURE
so `screencap` returns black, and `mDreamingLockscreen` reads identically for
the SIM prompt and the device keyguard. Mind the retry budget — ~3 wrong SIM
PINs and it PUK-locks — so verify WHICH prompt is up (`gsm.sim.state`) before
sending anything.

Why recovery goes through the Termux app (launching its activity) and not
`adb shell su -c sshd`: sshd has to run AS the Termux user (uid 10286) — its
authorized_keys live in that user's CE storage, and Termux's sshd has no
root-to-user mapping, so a root-launched listener rejects the key. `su 10286`
doesn't get you there either: `su <uid>` drops the supplementary groups down to
just that uid, and Android's paranoid-network gate denies AF_INET to a non-root
process without gid 3003 (inet) — the dropped sshd fails with "Cannot bind any
address". Launching the Termux activity runs everything in the real app: uid
10286 with its native inet group and CE storage unlocked, which is the one
context sshd can both bind and authenticate in. (RunCommandService would give
the same context, but its RUN_COMMAND permission isn't reliably held by the adb
caller after a reboot — refused even as root; the activity launch needs none.
The bind blocker is the missing inet group, not any SELinux domain — `adb shell`
here is root in the `su` domain and binds fine, so a standalone daemon like
calld can just launch as root and self-drop keeping inet.)

## calld supervision

The call daemon (`calld`, in `/data/local/tmp`) is a standalone binary, not a
Termux process: it execs as root and self-drops to uid 2000 keeping gid
1005(audio)+3003(inet), so it needs neither the Termux app nor RunCommandService.
Its supervisor is an APatch/Magisk module (`payload/pixel3/calld/`, installed to
`/data/adb/modules/calld/`) whose `service.sh` runs a restart-with-backoff loop
at boot; it idles until `/data/local/tmp/calld.enabled` exists, waits for tun0 to
carry a 100.64/10 address before starting calld (which fails closed to
loopback-only otherwise), recycles calld if that address later appears or
changes, stops it with SIGTERM+grace so it reverts the ADSP mixer, and logs to
logcat (`logcat -s calld-supervise`).

The two tracks — binary (`:calld-binary`) and supervisor module
(`:calld-supervisor`) — update independently, joined by ONE non-obvious contract:
after pushing a new binary the deploy step must `touch /data/local/tmp/calld.restart`,
which the supervisor watches and recycles on. mtime cannot substitute for this:
every nix build carries the same epoch-0 store mtime and `adb push` preserves it,
so a supervisor that watched mtime would silently keep running the OLD binary
after a deploy — a deploy that looks successful while the running system is
unchanged. The restart flag makes the update actually take effect.

## Adding a device pubkey

Add a `userKey` to the machine's entry in nixos-config's `flake/machines.nix`,
then `./phone apply <device>` per phone — the render picks it up directly.
Phones' own keys are excluded via their `androidDevice` flag.

## Termux vs NixOS — what you give up

- No atomic upgrades. `pkg update` mutates in place; no generation to roll
  back to.
- No version pinning. Policies declare package names only (`require-pkgs!`) —
  you always get whatever's current in the Termux repo.
- No reproducible userland — `$HOME` mutations from interactive use are not
  tracked.

Within those limits: desired state lives in a repo, converging is one
re-runnable command, and drift is visible (`phone verify`) rather than
discovered.
