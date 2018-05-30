# Bench

```sh
cd otoroshi
sbt asssembly
cd ..
cp otoroshi/target/scala-2.12/otoroshi.jar docker/bench/otoroshi/
cd docker/bench
docker-compose build
docker-compose up
```