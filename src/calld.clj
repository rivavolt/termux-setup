(ns calld
  "The tapped handset carries the call daemon: calld (rivavolt/call), the static
  aarch64-musl binary that places and answers calls, taps both audio legs and
  injects speech into the uplink. The binary is BUILT from the call flake at
  apply time, never vendored — same reasoning as the nixos-config renders: a
  committed binary is a cache with no invalidation. It lands over adb in
  /data/local/tmp, which is DE storage (reachable pre-unlock, so a boot
  supervisor can exec it in BFU) and shell-writable (no su hop, unlike the
  /data/adb payloads).

  Two things converge here, on separate tracks. The BINARY lands COLD in
  /data/local/tmp — this step never starts or restarts the daemon and never
  signals the process. Restarting calld out from under a live call drops the tap
  mid-conversation and can leave the ADSP mixer routing latched for the next
  call, so a converge must not touch a running instance. To make a pushed update
  (notably a security fix) actually take effect, the step touches
  /data/local/tmp/calld.restart after a drift push; the supervisor watches that
  flag and gracefully recycles onto the new binary, then clears it. This is a
  flag and not an mtime check because every nix-built calld carries the same
  epoch-0 store mtime and adb push preserves it, so mtime cannot tell builds
  apart. The touch is harmless when no supervisor is running — a stale flag the
  next recycle clears.

  The SUPERVISOR is an APatch/Magisk module in /data/adb/modules/calld (root-
  owned, so it needs the su hop, unlike the shell-writable binary). It runs as
  root at boot so calld can self-drop to uid 2000 keeping gid 1005(audio)+
  3003(inet) — a supervisor that set those itself is how the inet group got lost
  before, so it doesn't — and stops calld with SIGTERM + grace, never SIGKILL,
  so the mixer reverts. It waits for the tailnet address before start (calld
  binds interfaces once and fails closed to loopback-only) and recycles on
  address change. The scripts are vendored from payload/pixel3/calld because
  they are static; the binary is built because it is not.

  Installing the module is safe: the supervisor idles until the runtime flag
  /data/local/tmp/calld.enabled exists, and this converge never creates that
  flag — enabling calld is a deliberate on-device act, kept out of the desired
  state so `phone apply` cannot start the daemon while the sound card is in use."
  (:require [engine]
            [transport :refer [adb sh-out repo-file]]
            [clojure.string :as str]))

;; Pinned to the hardware-vetted rev, NOT main HEAD. `phone apply` pushes this
;; binary cold over whatever is running, with no CI gate and no easy rollback, so
;; it must be a reviewed build. Bump this only to a rev the call daemon owner has
;; vetted on live calls (coordinate with team-lead). CALL_FLAKE overrides for
;; local iteration. Current pin e6fc21d: wedge fix (poll-based capture read),
;; place-while-active refusal, telecom-bridge seam, capped tap re-arm, and the
;; mic cut decoupled to explicit --cut-mic — its predecessor d64c860 muted agent
;; calls to the far party by coupling the cut to --agent, so never pin that rev.
(def ^:private flake (or (System/getenv "CALL_FLAKE")
                         "github:rivavolt/call/e6fc21d4fc70ae77c05707f85f2824a3bc1381b0"))
(def ^:private dest "/data/local/tmp/calld")

(def ^:private binary
  (delay
    (let [out (str/trim (:out (sh-out "nix" "build" "--no-link" "--print-out-paths"
                                      (str flake "#calld"))))]
      (when (str/blank? out) (throw (ex-info "nix build produced no calld" {:flake flake})))
      (str out "/bin/calld"))))

(defn- host-md5 [path] (re-find #"^\S+" (:out (sh-out "md5sum" path))))
(defn- device-md5 [path] (some->> (adb "shell" (str "md5sum " path " 2>/dev/null")) :out (re-find #"^\S+")))

;; only the Pixel 3 is the tapped handset — the audio path is SDM845-specific
;; mixer routing, and being the fleet's call tap is a designation, not a cheap
;; probe, so the guard is the device identity
(defn- call-tap? [] (= (:ssh transport/*dev*) "pixel3"))

(engine/step! (engine/step
               :calld-binary
               "calld current in /data/local/tmp (cold; a supervisor launches it)"
               :adb
               (fn [] (= (host-md5 @binary) (device-md5 dest)))
               (fn []
                 (adb "push" @binary dest)
                 ;; signal the supervisor to recycle onto the new binary — mtime
                 ;; can't, every build shares the store's epoch-0 mtime
                 (adb "shell" (str "chmod 755 " dest "; touch /data/local/tmp/calld.restart")))
               call-tap?))

;; --- supervisor module (root-owned, so su-cp not scp) -----------------------

(def ^:private module-dir "/data/adb/modules/calld")
(def ^:private module-files
  [[(repo-file "payload/pixel3/calld/module.prop")    (str module-dir "/module.prop")    "644"]
   [(repo-file "payload/pixel3/calld/service.sh")      (str module-dir "/service.sh")      "755"]
   [(repo-file "payload/pixel3/calld/calld-supervise") (str module-dir "/calld-supervise") "755"]])

(defn- su [cmd] (some-> (adb "shell" (str "su -c '" cmd "'")) :out))
(defn- root-md5 [path] (some->> (su (str "md5sum " path " 2>/dev/null")) (re-find #"^\S+")))

(defn- push-root-file!
  "adb push into shell-writable staging, then su-cp into the root-owned module
  dir (the scp/ssh file plane cannot reach /data/adb)."
  [local dest mode]
  (when (adb "push" local "/data/local/tmp/calld-payload")
    (su (str "cp /data/local/tmp/calld-payload " dest
             "; chmod " mode " " dest "; chown root:root " dest
             "; rm -f /data/local/tmp/calld-payload"))))

(engine/step! (engine/step
               :calld-supervisor
               "calld supervisor module in /data/adb/modules/calld (idle until enabled)"
               :adb
               (fn [] (every? (fn [[local dest _]] (= (host-md5 local) (root-md5 dest))) module-files))
               (fn []
                 (su (str "mkdir -p " module-dir))
                 (doseq [[local dest mode] module-files] (push-root-file! local dest mode)))
               call-tap?))
