#!/usr/bin/env bash
# End-to-end: the app (its e2e build, an app of its own) on the device or
# emulator adb sees, against a throwaway host: a container (e2e/host; podman,
# else docker, or ZAGAL_E2E_ENGINE)
# with sshd and herdr-core, published on 127.0.0.1 and reached through
# `adb reverse`. No volumes: removing the container removes all of it, and
# nothing here touches your sessions, your state or ~/.ssh.
#
#   mise run e2e                     # every flow in e2e/flows, then every scenario
#   mise run e2e e2e/flows/x.yaml
#   mise run e2e e2e/flows/connect.yaml e2e/scenarios/x.sh
#   ZAGAL_E2E_HOLD=1 mise run e2e    # then keep the host up to poke at by hand
#   ZAGAL_E2E_APK=x.apk mise run e2e # that build instead of this tree's
#
# Scenarios (e2e/scenarios/*.sh) check what the app draws: they run a test
# pattern in the connected terminal (zt, from e2e/host) and look at
# screenshots (pixels.py). They need connect.yaml before them.
set -euo pipefail
cd "$(dirname "$0")/.."

port=${ZAGAL_E2E_PORT:-2222}
app=io.github.osc100.zagal.e2e
mark="zagal-e2e-$RANDOM$RANDOM"
host=zagal-e2e-$$
engine=${ZAGAL_E2E_ENGINE:-$(command -v podman || command -v docker || true)}
[[ -n $engine ]] || { echo "e2e: needs podman or docker" >&2; exit 1; }

# The core and git, as the host's user.
core() { "$engine" exec -u e2e "$host" hc "$@"; }
on_host() { "$engine" exec -u e2e -w /home/e2e "$host" "$@"; }
cleanup() {
  keyboard_back 2>/dev/null || true
  "$engine" rm -f "$host" >/dev/null 2>&1 || true
  adb reverse --remove "tcp:$port" 2>/dev/null || true
}
trap cleanup EXIT
# Stopped by a signal: exit, so the cleanup above runs.
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# Docker looks for a Dockerfile: name it.
"$engine" build -q -t zagal-e2e-host -f e2e/host/Containerfile e2e/host >/dev/null
"$engine" run -d --rm --name "$host" -p "127.0.0.1:$port:22" zagal-e2e-host >/dev/null

apk=${ZAGAL_E2E_APK:-}
if [[ -z $apk ]]; then
  ./gradlew -q :app:assembleE2e
  apk=app/build/outputs/apk/e2e/app-e2e.apk
fi
adb install -r "$apk" >/dev/null
adb shell pm clear "$app" >/dev/null
# Android 17's local network permission; older versions have none to grant.
adb shell pm grant "$app" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
adb reverse "tcp:$port" "tcp:$port" >/dev/null

out=${ZAGAL_E2E_OUT:-build/e2e}
flows() {
  maestro test --test-output-dir "$out" \
    -e HOST="e2e@127.0.0.1:$port" -e MARK="$mark" "$@"
}

# For scenarios: their terminal (scenarios() opens it), and what they do with it.
addresses() { core ls | grep -oE '"address":"[^"]+"' | cut -d'"' -f4 | sort; }
term() { echo "$scenario_term"; }
# Runs a command in it, as if typed.
send() { core send "$(term)" "$1" >/dev/null; }
# The screen, as out/<scenario>-<name>.png.
shot() { adb exec-out screencap -p > "$out/$scenario-$1.png"; }
# The app to the background (home) and back in front.
to_back() { adb shell input keyevent KEYCODE_HOME; }
# Has Android let go of what it can, as when memory runs low. (Once at that
# level, Android refuses it again: it's still there.)
trim_memory() { adb shell am send-trim-memory "$app" COMPLETE >/dev/null 2>&1 || true; }
# No soft keyboard until the scenario ends: its going and coming resizes the
# terminal, which draws every row anyway. (Disabled, as Android shows it again
# on its own when the app comes back.)
no_keyboard() {
  keyboard=$(adb shell settings get secure default_input_method | tr -d '\r')
  adb shell ime disable "$keyboard" >/dev/null
  sleep 1
}
keyboard_back() {
  [[ -n ${keyboard:-} ]] || return 0
  adb shell ime enable "$keyboard" >/dev/null
  adb shell ime set "$keyboard" >/dev/null
  keyboard=
}
to_front() { adb shell am start -n "$app/dev.herdr.mobile.MainActivity" >/dev/null 2>&1; }
pixels() { python3 -I e2e/scenarios/pixels.py "$@"; }

scenarios() {
  mkdir -p "$out"
  # A space of their own, on screen whatever the flows before left there.
  local before
  before=$(addresses)
  flows e2e/scenarios/space.yaml
  scenario_term=$(comm -13 <(echo "$before") <(addresses) | head -1)
  [[ -n $scenario_term ]] || { echo "e2e: no terminal for the scenarios" >&2; exit 1; }
  for script in "$@"; do
    scenario=$(basename "$script" .sh)
    echo "e2e: scenario $scenario"
    # Each starts at a shell prompt: the last one's pattern stopped. (Killed:
    # the shell's commands ignore Ctrl+C's SIGINT.)
    on_host pkill -x sleep >/dev/null 2>&1 || true
    send clear
    # shellcheck source=/dev/null
    source "$script"
    keyboard_back
  done
}

# Given flows and scenarios: just those, flows first. Else all of them, in
# three steps: connecting first, then the rest of the flows, once the host
# has what they need, then the scenarios.
if [[ $# -gt 0 ]]; then
  given_flows=() given_scenarios=()
  for arg in "$@"; do
    if [[ $arg == *.sh ]]; then given_scenarios+=("$arg"); else given_flows+=("$arg"); fi
  done
  [[ ${#given_flows[@]} -eq 0 ]] || flows "${given_flows[@]}"
  [[ ${#given_scenarios[@]} -eq 0 ]] || scenarios "${given_scenarios[@]}"
else
  flows e2e/flows/connect.yaml

  # What connecting typed, on the host's side.
  typed=
  for t in $(core ls | grep -oE '"address":"[^"]+"' | cut -d'"' -f4); do
    if core read "$t" | grep -q "$mark"; then typed=$t; break; fi
  done
  if [[ -z $typed ]]; then
    echo "e2e: no terminal on the throwaway core shows '$mark'" >&2
    exit 1
  fi
  echo "e2e: $typed shows what the app typed"

  # What the rest need on the host: a git checkout, and an agent: a shell that
  # reports itself as one (OSC 7501), so no real agent runs. Labeled into the
  # space connecting made, as the desktop labels its own, which agent lists need.
  on_host git init -q -b main repo
  on_host git -C repo -c user.email=e2e@zagal -c user.name=e2e commit -q --allow-empty -m init
  agent=$(core run --bg --name fake sh -c 'printf "\033]0;Fake agent\007\033]7501;state=working:app=claude-code\033\\"; exec sleep 100000' |
    grep -oE '"address":"[^"]+"' | cut -d'"' -f4)
  core label "$agent" $(core get "$typed" | grep -oE '"herdr\.(space|tab)":"[^"]+"' | tr -d '"' | tr : =) >/dev/null

  flows e2e/flows/tabs.yaml e2e/flows/spaces.yaml e2e/flows/pin.yaml e2e/flows/worktree.yaml \
    e2e/flows/ime.yaml

  # The worktree flow made a checkout and removed it: git agrees.
  if [[ $(on_host git -C repo worktree list | wc -l) -ne 1 ]]; then
    echo "e2e: the worktree is still there:" >&2
    on_host git -C repo worktree list >&2
    exit 1
  fi
  echo "e2e: the worktree came and went"

  # What the soft keyboard typed and then erased (flows/ime.yaml): "zagalime",
  # one key at a time, then two DELs. On the host's side: the six characters,
  # and not the eight.
  keyboard=
  for t in $(core ls | grep -oE '"address":"[^"]+"' | cut -d'"' -f4); do
    if core read "$t" | grep -q "zagalime"; then
      echo "e2e: the keyboard's DELs are missing on $t" >&2
      exit 1
    fi
    if core read "$t" | grep -q "zagali"; then keyboard=$t; fi
  done
  if [[ -z $keyboard ]]; then
    echo "e2e: no terminal on the throwaway core shows what the keyboard typed" >&2
    exit 1
  fi
  echo "e2e: $keyboard shows the keyboard's line, minus the two DELs"

  scenarios e2e/scenarios/*.sh
fi

# Keep the throwaway host up for poking at by hand, until Ctrl-C.
if [[ ${ZAGAL_E2E_HOLD:-} == 1 ]]; then
  echo "e2e: holding e2e@127.0.0.1:$port ($(basename "$engine") container $host); Ctrl-C ends it"
  # In the background, so a signal doesn't wait out the sleep.
  while :; do sleep 3600 & wait $!; done
fi
