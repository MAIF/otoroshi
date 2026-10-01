#!/bin/sh
set -eu

# The JVM options Otoroshi starts with when nothing says otherwise. They come first on the command line, so an option
# of JAVA_OPTS always wins: the JVM keeps the last value of a flag, and -Xmx beats a percentage.
#
# By itself, the JVM gives the heap 25% of the memory of the container, and picks the serial collector, which stops
# every thread for each collection, under 2 CPUs or 1792 MB.
#
# This block is the same in entrypoint-jar-root.sh, test-entrypoint.sh checks both.
DEFAULT_JAVA_OPTS=""

# The memory limit of the container in MB, cgroup v2 then v1. Nothing without a limit: "max", or a limit above the
# memory of the machine, is not one.
memory_limit_mb() {
  limit=""
  if [ -r /sys/fs/cgroup/memory.max ]; then
    read -r limit < /sys/fs/cgroup/memory.max || true
  elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    read -r limit < /sys/fs/cgroup/memory/memory.limit_in_bytes || true
  fi
  case "$limit" in
    '' | *[!0-9]*) return 0 ;;
  esac
  limit_mb=$((limit / 1048576))
  total_kb=""
  if [ -r /proc/meminfo ]; then
    while read -r key value unit; do
      if [ "$key" = "MemTotal:" ]; then
        total_kb=$value
        break
      fi
    done < /proc/meminfo
  fi
  if [ "$limit_mb" -lt 1 ]; then
    return 0
  fi
  if [ -n "$total_kb" ] && [ "$limit_mb" -ge $((total_kb / 1024)) ]; then
    return 0
  fi
  echo "$limit_mb"
}

# With a memory limit, the container is there for Otoroshi and the heap gets 60% of it. Otoroshi uses 300 to 550 MB out
# of the heap (classes, compiled code, threads, collector, buffers, native allocations): under 1280 MB, the heap gets
# what is left once 512 MB are set aside, and never less than the 25% the JVM would give. Without a limit, the JVM
# keeps its own default, 25% of the memory of a machine Otoroshi may share.
MEMORY_LIMIT_MB=$(memory_limit_mb)
if [ -n "$MEMORY_LIMIT_MB" ]; then
  HEAP_PERCENT=60
  if [ $((MEMORY_LIMIT_MB * 40 / 100)) -lt 512 ]; then
    HEAP_PERCENT=$(((MEMORY_LIMIT_MB - 512) * 100 / MEMORY_LIMIT_MB))
    if [ "$HEAP_PERCENT" -lt 25 ]; then
      HEAP_PERCENT=25
    fi
  fi
  DEFAULT_JAVA_OPTS="$DEFAULT_JAVA_OPTS -XX:MaxRAMPercentage=$HEAP_PERCENT.0"
fi

# G1 whatever the size of the container, unless a collector is already picked, in JAVA_OPTS, in the arguments of the
# container or in the variables the JVM reads by itself: two collectors stop the JVM from starting.
GC_SELECTED=false
set -f
for opt in ${JAVA_OPTS:-} ${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-} "$@"; do
  case "$opt" in
    -XX:+Use*GC) GC_SELECTED=true ;;
  esac
done
set +f
if [ "$GC_SELECTED" = false ]; then
  DEFAULT_JAVA_OPTS="$DEFAULT_JAVA_OPTS -XX:+UseG1GC"
fi

# Out of memory, Otoroshi does not recover: the actor system hit by the error shuts down and its HTTP server with it,
# while the JVM stays up, a container that looks alive and serves nothing. Exit instead, so that whatever runs the
# container starts a new one. -XX:-ExitOnOutOfMemoryError in JAVA_OPTS turns it off.
DEFAULT_JAVA_OPTS="$DEFAULT_JAVA_OPTS -XX:+ExitOnOutOfMemoryError"

# glibc gives each thread its own malloc arena, up to 8 per CPU, and the memory freed in one stays there: with the
# hundred threads of the JVM, that is 100 to 150 MB of the container used for nothing.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"

export JAVA_OPTS="${DEFAULT_JAVA_OPTS# } ${JAVA_OPTS:-}"
echo "JAVA_OPTS: ${JAVA_OPTS}"

BASE_JAVA_OPTS="$JAVA_OPTS \
 --add-opens java.base/javax.net.ssl=ALL-UNNAMED \
 --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
 --add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED \
 --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
 --add-opens=java.base/sun.security.ssl=ALL-UNNAMED \
 -Dlog4j2.formatMsgNoLookups=true \
 -Dhttp.port=8080 -Dhttps.port=8443"

# Arguments given to the container (docker run maif/otoroshi -Dconfig.file=...) are forwarded
# to the JVM below. Drop the empty ones first: the images declare CMD [""] to neutralize the
# CMD inherited from the base image, so a plain `docker run maif/otoroshi` would otherwise hand
# an empty argument to java and fail to boot.
argc=$#
while [ "$argc" -gt 0 ]; do
  arg="$1"
  shift
  if [ -n "$arg" ]; then
    set -- "$@" "$arg"
  fi
  argc=$((argc - 1))
done

if [ -z "${OTOROSHI_PLUGINS_DIR_PATH:-}" ]; then
  echo "Bootstrapping otoroshi without additional plugins"
  exec java ${BASE_JAVA_OPTS} "$@" -jar otoroshi.jar
else
  echo "Bootstrapping otoroshi with additional plugins from ${OTOROSHI_PLUGINS_DIR_PATH}"
  exec java ${BASE_JAVA_OPTS} "$@" -cp "./otoroshi.jar:${OTOROSHI_PLUGINS_DIR_PATH}/*" play.core.server.ProdServerStart
fi
