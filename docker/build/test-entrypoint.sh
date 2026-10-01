#!/bin/sh
# Checks the java command line both entrypoints build, in containers with different memory limits and settings. java
# is replaced by a script that prints its arguments, so any linux image with a shell will do, then the options are
# given to the real JVM of the image once.
#
# usage: ./test-entrypoint.sh [image]    (eclipse-temurin:21 by default, needs docker)
set -u
cd "$(dirname "$0")"
IMAGE=${1:-eclipse-temurin:21}
FAKE=$(mktemp -d)
trap 'rm -rf "$FAKE"' EXIT
printf '#!/bin/sh\necho "java $*"\necho "MALLOC_ARENA_MAX=$MALLOC_ARENA_MAX"\n' > "$FAKE/java"
chmod +x "$FAKE/java"
failures=0
ON_OOM=-XX:+ExitOnOutOfMemoryError

# run <entrypoint> <memory limit or none> [docker run options...] -- [container arguments...]
run() {
  entrypoint=$1; memory=$2; shift 2
  set -- "$@" __end__
  options=""
  while [ "$1" != "--" ]; do options="$options $1"; shift; done
  shift
  if [ "$memory" != none ]; then options="$options --memory=$memory"; fi
  arguments=""
  while [ "$1" != "__end__" ]; do arguments="$arguments '$1'"; shift; done
  # shellcheck disable=SC2086
  eval docker run --rm -w /usr/app -v "$PWD/$entrypoint:/usr/app/entrypoint.sh:ro" -v "$FAKE:/fake:ro" \
    -e PATH=/fake:/usr/bin:/bin $options --entrypoint /usr/app/entrypoint.sh "$IMAGE" $arguments 2>&1
}

# what the JVM is given, without the options every start has
java_line() {
  grep '^java ' | sed 's/ --add-opens java.base\/javax.net.ssl=ALL-UNNAMED .* -Dhttp.port=8080 -Dhttps.port=8443//'
}

check() {
  description=$1; expected=$2; actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "ok    $description"
  else
    echo "FAIL  $description"
    echo "        expected: $expected"
    echo "        actual:   $actual"
    failures=$((failures + 1))
  fi
}

for entrypoint in entrypoint-jar.sh entrypoint-jar-root.sh; do
  echo "$entrypoint, in $IMAGE"

  check "no memory limit: the heap is left to the JVM" \
    "java -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint none -- | java_line)"
  check "4 GiB: 60% for the heap" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint 4g -- | java_line)"
  check "1280 MB: still 60%, 512 MB are left out of the heap" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint 1280m -- | java_line)"
  check "1 GiB: 512 MB out of the heap, 50% for the heap" \
    "java -XX:MaxRAMPercentage=50.0 -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint 1g -- | java_line)"
  check "768 MB: 512 MB out of the heap, 33% for the heap" \
    "java -XX:MaxRAMPercentage=33.0 -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint 768m -- | java_line)"
  check "512 MB: never less than the 25% of the JVM" \
    "java -XX:MaxRAMPercentage=25.0 -XX:+UseG1GC $ON_OOM -jar otoroshi.jar" "$(run $entrypoint 512m -- | java_line)"

  check "JAVA_OPTS comes after the defaults, so -Xmx wins" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -Xmx1g -jar otoroshi.jar" \
    "$(run $entrypoint 2g -e JAVA_OPTS=-Xmx1g -- | java_line)"
  check "a collector in JAVA_OPTS: no G1" \
    "java -XX:MaxRAMPercentage=60.0 $ON_OOM -XX:+UseZGC -jar otoroshi.jar" \
    "$(run $entrypoint 2g -e JAVA_OPTS=-XX:+UseZGC -- | java_line)"
  check "a collector in the arguments of the container: no G1" \
    "java -XX:MaxRAMPercentage=60.0 $ON_OOM -XX:+UseParallelGC -Dfoo=bar -jar otoroshi.jar" \
    "$(run $entrypoint 2g -- -XX:+UseParallelGC -Dfoo=bar | java_line)"
  check "a collector in JAVA_TOOL_OPTIONS: no G1" \
    "java -XX:MaxRAMPercentage=60.0 $ON_OOM -jar otoroshi.jar" \
    "$(run $entrypoint 2g -e JAVA_TOOL_OPTIONS=-XX:+UseSerialGC -- | java_line)"
  check "a collector in JDK_JAVA_OPTIONS: no G1" \
    "java -XX:MaxRAMPercentage=60.0 $ON_OOM -jar otoroshi.jar" \
    "$(run $entrypoint 2g -e JDK_JAVA_OPTIONS=-XX:+UseShenandoahGC -- | java_line)"
  check "an option that only ends like a collector: G1" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -XX:+DisableExplicitGC -jar otoroshi.jar" \
    "$(run $entrypoint 2g -e JAVA_OPTS=-XX:+DisableExplicitGC -- | java_line)"

  check "the empty argument of CMD [\"\"] is dropped, the others are forwarded" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -Dfoo=bar -jar otoroshi.jar" \
    "$(run $entrypoint 2g -- '' -Dfoo=bar | java_line)"
  check "additional plugins go on the classpath" \
    "java -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC $ON_OOM -cp ./otoroshi.jar:/plugins/* play.core.server.ProdServerStart" \
    "$(run $entrypoint 2g -e OTOROSHI_PLUGINS_DIR_PATH=/plugins -- | java_line)"

  check "two malloc arenas" "MALLOC_ARENA_MAX=2" "$(run $entrypoint 2g -- | grep '^MALLOC_ARENA_MAX')"
  check "unless MALLOC_ARENA_MAX is set" "MALLOC_ARENA_MAX=8" \
    "$(run $entrypoint 2g -e MALLOC_ARENA_MAX=8 -- | grep '^MALLOC_ARENA_MAX')"
  check "no option the JVM dropped, nor the one that hides a mistyped option" "0" \
    "$(run $entrypoint 2g -- | grep -c -e 'illegal-access' -e 'IgnoreUnrecognizedVMOptions')"
done

# with the JVM of the image, when it has one: the options are accepted, and give the heap and the collector expected
if docker run --rm --entrypoint sh "$IMAGE" -c 'command -v java' > /dev/null 2>&1; then
  flags=$(docker run --rm -w /usr/app --memory=1g -v "$PWD/entrypoint-jar.sh:/usr/app/entrypoint.sh:ro" \
    --entrypoint /usr/app/entrypoint.sh "$IMAGE" -XX:+PrintFlagsFinal -version 2>&1)
  check "the JVM of the image, in 1 GiB: a 512 MB heap" "536870912" \
    "$(echo "$flags" | awk '$2 == "MaxHeapSize" { print $4 }')"
  check "the JVM of the image, in 1 GiB: G1" "true" "$(echo "$flags" | awk '$2 == "UseG1GC" { print $4 }')"
  check "the JVM of the image exits when out of memory" "true" \
    "$(echo "$flags" | awk '$2 == "ExitOnOutOfMemoryError" { print $4 }')"
  off=$(docker run --rm -w /usr/app --memory=1g -e JAVA_OPTS=-XX:-ExitOnOutOfMemoryError \
    -v "$PWD/entrypoint-jar.sh:/usr/app/entrypoint.sh:ro" --entrypoint /usr/app/entrypoint.sh "$IMAGE" \
    -XX:+PrintFlagsFinal -version 2>&1)
  check "unless JAVA_OPTS turns it off" "false" "$(echo "$off" | awk '$2 == "ExitOnOutOfMemoryError" { print $4 }')"
  mistyped=$(docker run --rm -w /usr/app -e JAVA_OPTS=-XX:MaxRamPercentage=70 \
    -v "$PWD/entrypoint-jar.sh:/usr/app/entrypoint.sh:ro" --entrypoint /usr/app/entrypoint.sh "$IMAGE" -version 2>&1)
  check "the JVM of the image refuses a mistyped option" "1" "$(echo "$mistyped" | grep -c 'Unrecognized VM option')"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
