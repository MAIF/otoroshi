# Otoroshi dev environment for quick patches

this docker image provide a functionnal dev environement to patch Otoroshi quickly without installing a lot of stuff, you just need `Docker` and a web browser available on your machine.

## Build

```sh
docker build -t otoroshi-dev .
```

## Run

```sh 
docker run -p "9997:9997" -p "9998:9998" -p "9999:9999" -p "3040:3040" -v "$(pwd)/oto/src:/root/otoroshi" -v "$(pwd)/oto/db:/root/otoroshi/otoroshi/filedb" -it otoroshi-dev 
http://localhost:9997?folder=/root/otoroshi
```
