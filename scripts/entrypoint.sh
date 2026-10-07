#!/bin/sh
set -eu
proxy_pid=""
if [ "${IPV6_PROXY_ENABLED:-false}" = "true" ]; then
    python3 /app/scripts/ipv6_proxy.py &
    proxy_pid=$!
    export PRICES_PROXY=http://127.0.0.1:8891
fi
java -XX:MaxRAMPercentage=55 -Dfile.encoding=UTF-8 -jar /app/bg-prices.jar "$@" &
app_pid=$!
terminate() {
    kill -TERM "$app_pid" 2>/dev/null || true
    if [ -n "$proxy_pid" ]; then kill -TERM "$proxy_pid" 2>/dev/null || true; fi
}
trap terminate TERM INT
set +e
wait "$app_pid"
app_status=$?
wait "$app_pid" 2>/dev/null
if [ -n "$proxy_pid" ]; then kill -TERM "$proxy_pid" 2>/dev/null; wait "$proxy_pid" 2>/dev/null; fi
exit "$app_status"
