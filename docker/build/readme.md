# Docker build

```sh
export JDK_VERSION=11
docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile-gen -t "otoroshi-jdk$JDK_VERSION" .
```