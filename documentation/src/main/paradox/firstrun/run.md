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

