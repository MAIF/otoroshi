# Otoroshi dev environment for quick patches

this docker image provide a functionnal dev environement to patch Otoroshi quickly without installing a lot of stuff, you just need `Docker` and a web browser available on your machine.

## Build and Run it

```sh
docker build -t otoroshi-dev .
docker run -p "8080:8080" -p "9999:9999" -it otoroshi-dev zsh
```

## Run it

```sh 
docker run -p "8080:8080" -p "9999:9999" -it otoroshi-dev zsh
```
