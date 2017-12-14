# Run Otoroshi

now your ready to run Otoroshi. You can run the following command with some tweaks dependencing on how you want to configure Otoroshi. If you want to pass a custom configuration file, use the `-Dconfig.file=/path/to/file.conf` flag in the following commands.

## From .zip file

```sh
unzip otoroshi-vx.x.x.zip
cd otoroshi-vx.x.x
./bin/otoroshi
```

## From .jar file

```sh
java -jar otoroshi-vx.x.x.jar
```

## From docker

```sh
docker run -p "8080:8080" otoroshi
```

## Run examples

```sh
$ java \
  -Xms2G \
  -Xmx8G \
  -Dhttp.port=8080 \
  -Dapp.importFrom=/home/user/otoroshi.json \
  -Dconfig.file=/home/user/otoroshi.conf \
  -jar ./otoroshi.jar

[warn] otoroshi-in-memory-datastores - Now using InMemory DataStores
[warn] otoroshi-env - The main datastore seems to be empty, registering some basic services
[warn] otoroshi-env - Importing from: /home/user/otoroshi.json
[info] play.api.Play - Application started (Prod)
[info] p.c.s.NettyServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
```

if you choose to start Otoroshi without importing existing data, Otoroshi will create a new admin user and print the login details in the log. When you will log into the admin dashboard, Otoroshi will ask you to create another account to avoid security issues.

```sh
$ java \
  -Xms2G \
  -Xmx8G \
  -Dhttp.port=8080 \
  -jar otoroshi.jar

[warn] otoroshi-in-memory-datastores - Now using InMemory DataStores
[warn] otoroshi-env - The main datastore seems to be empty, registering some basic services
[warn] otoroshi-env - You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / HHUsiF2UC3OPdmg0lGngEv3RrbIwWV5W
[info] play.api.Play - Application started (Prod)
[info] p.c.s.NettyServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
```