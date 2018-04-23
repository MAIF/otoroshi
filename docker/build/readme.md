# Docker build

```sh
docker build --no-cache -t otoroshi .
docker build --no-cache -f ./Dockerfile-jdk9 -t otoroshi-jdk9 .
docker build --no-cache -f ./Dockerfile-jdk10 -t otoroshi-jdk10 .
```