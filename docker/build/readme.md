# Docker build

```sh
export JDK_VERSION=21
cp ../../otoroshi/target/scala-3.8.4/otoroshi.jar ./otoroshi.jar
docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "otoroshi-jdk$JDK_VERSION" .
```

`build.sh` builds and pushes the images of a release, for JDK 17, 21 and 25.

## What the entrypoints give the JVM

`entrypoint-jar.sh` (and `entrypoint-jar-root.sh` for the image that runs as root) size the JVM from the limits of the
container, before the options of `JAVA_OPTS`, which therefore always win:

- with a memory limit, `-XX:MaxRAMPercentage=60.0`, less under 1280 MB so that 512 MB stay out of the heap: Otoroshi
  uses 300 to 550 MB there. Without a memory limit, nothing: the JVM keeps its default, 25% of the memory of the machine;
- `-XX:+UseG1GC`, unless a collector is picked in `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or the arguments
  of the container. By itself the JVM picks the serial collector under 2 CPUs or 1792 MB;
- `-XX:+ExitOnOutOfMemoryError`: out of memory, the HTTP server of Otoroshi stops while the JVM stays up, so the JVM
  exits for the container to be restarted;
- `MALLOC_ARENA_MAX=2`, unless it is set.

They pass no option that would hide a mistyped one: the JVM refuses to start on an option it does not know.

The user documentation is in `manual/next/docs/install/run-otoroshi.md`.

## Testing the entrypoints

```sh
./test-entrypoint.sh                      # in eclipse-temurin:21
./test-entrypoint.sh maif/otoroshi:dev    # in any linux image
```

It runs both entrypoints in containers with different memory limits and settings, with a fake `java` that prints its
arguments, then gives the options to the real JVM of the image.
