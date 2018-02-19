# Run Otoroshi

Now you are ready to run Otoroshi. You can run the following command with some tweaks depending on the way you want to configure Otoroshi. If you want to pass a custom configuration file, use the `-Dconfig.file=/path/to/file.conf` flag in the following commands.

## From .zip file

```sh
unzip otoroshi-dist.zip
cd otoroshi-vx.x.x
./bin/otoroshi
```

## From .jar file

```sh
java -jar otoroshi.jar
```

## From docker

```sh
docker run -p "8080:8080" maif/otoroshi:1.0.2
```

You can also pass useful args like :

```
docker run -p "8080:8080" otoroshi -Dconfig.file=/usr/app/otoroshi/conf/otoroshi.conf -Dlogger.file=/usr/app/otoroshi/conf/otoroshi.xml
```

If you want to provide your own config file, you can read @ref:[the documentation about config files](../firstrun/configfile.md).

You can also provide some ENV variable using the `--env` flag to customize your Otoroshi instance.

The list of possible env variables is available @ref:[here](../firstrun/env.md).

You can use a volume to provide configuration like :

```sh
docker run -p "8080:8080" -v "$(pwd):/usr/app/otoroshi/conf" maif/otoroshi
```

You can also use a volume if you choose to use `leveldb` datastore like :

```sh
docker run -p "8080:8080" -v "$(pwd)/leveldb:/usr/app/otoroshi/leveldb" maif/otoroshi -Dapp.storage=leveldb
```

You can also use a volume if you choose to use exports files :

```sh
docker run -p "8080:8080" -v "$(pwd):/usr/app/otoroshi/imports" maif/otoroshi -Dapp.importFrom=/usr/app/otoroshi/imports/export.json
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
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
```

If you choose to start Otoroshi without importing existing data, Otoroshi will create a new admin user and print the login details in the log. When you will log into the admin dashboard, Otoroshi will ask you to create another account to avoid security issues.

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
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
```
