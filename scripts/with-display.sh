#!/bin/sh
set -eu
if [ "${PRICES_HEADLESS:-true}" != "false" ] || [ -n "${DISPLAY:-}" ]; then
    exec "$@"
fi
display_pid=""
cleanup() {
    if [ -n "$display_pid" ]; then
        kill -TERM "$display_pid" 2>/dev/null || true
        wait "$display_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
export DISPLAY=:99
Xvfb "$DISPLAY" -screen 0 1280x720x24 -nolisten tcp &
display_pid=$!
attempt=0
until xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if ! kill -0 "$display_pid" 2>/dev/null || [ "$attempt" -ge 50 ]; then
        printf '%s\n' 'Virtual display failed to become ready.' >&2
        exit 1
    fi
    sleep 0.1
done
printf '%s\n' 'Virtual display ready; Chromium headless=false.'
"$@" &
command_pid=$!
terminate() {
    kill -TERM "$command_pid" 2>/dev/null || true
}
trap terminate TERM INT
set +e
wait "$command_pid"
command_status=$?
if kill -0 "$command_pid" 2>/dev/null; then
    wait "$command_pid"
    command_status=$?
fi
exit "$command_status"
