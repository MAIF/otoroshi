---
title: Run Otoroshi
sidebar_label: "Run Otoroshi"
sidebar_position: 4
---
# Run Otoroshi

Now you are ready to run Otoroshi. You can run the following command with some tweaks depending on the way you want to configure Otoroshi. If you want to pass a custom configuration file, use the `-Dconfig.file=/path/to/file.conf` flag in the following commands.

## From .zip file

```sh
cd otoroshi-vx.x.x
./bin/otoroshi
```

## From .jar file

For Java 11

```sh
java -jar otoroshi.jar
```

if you want to run the jar file for on a JDK above JDK11, you'll have to add the following flags

```sh
java \
  --add-opens=java.base/javax.net.ssl=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED \
  --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
  --add-opens=java.base/sun.security.ssl=ALL-UNNAMED \
  -Dlog4j2.formatMsgNoLookups=true \
  -jar otoroshi.jar
```

## From docker

```sh
docker run -p "8080:8080" maif/otoroshi
```

You can also pass useful args like :

```sh
docker run -p "8080:8080" maif/otoroshi -Dconfig.file=/usr/app/otoroshi/conf/otoroshi.conf -Dlogger.file=/usr/app/otoroshi/conf/otoroshi.xml
```

If you want to provide your own config file, you can read [the documentation about config files](./setup-otoroshi.mdx).

You can also provide some ENV variable using the `--env` flag to customize your Otoroshi instance.

The list of possible env variables is available [here](./setup-otoroshi.mdx).

You can use a volume to provide configuration like :

```sh
docker run -p "8080:8080" -v "$(pwd):/usr/app/otoroshi/conf" maif/otoroshi
```

You can also use a volume if you choose to use `filedb` datastore like :

```sh
docker run -p "8080:8080" -v "$(pwd)/filedb:/usr/app/otoroshi/filedb" maif/otoroshi -Dotoroshi.storage=file
```

You can also use a volume if you choose to use exports files :

```sh
docker run -p "8080:8080" -v "$(pwd):/usr/app/otoroshi/imports" maif/otoroshi -Dotoroshi.importFrom=/usr/app/otoroshi/imports/export.json
```

## Run examples

```sh
$ java \
  -Xms2G \
  -Xmx8G \
  -Dhttp.port=8080 \
  -Dotoroshi.importFrom=/home/user/otoroshi.json \
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

## Initial admin account

The first time Otoroshi starts on an empty datastore (and only on a Leader or standalone instance, never on a cluster worker), it creates a default admin account. You can set its credentials explicitly, otherwise the login defaults to `admin@otoroshi.io` and a random 32 characters password is generated.

| Configuration | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `otoroshi.adminLogin` | `OTOROSHI_INITIAL_ADMIN_LOGIN` | `admin@otoroshi.io` | the initial admin login |
| `otoroshi.adminPassword` | `OTOROSHI_INITIAL_ADMIN_PASSWORD` | *randomly generated* | the initial admin password |
| `otoroshi.hideInitialAdminPassword` | `OTOROSHI_HIDE_INITIAL_ADMIN_PASSWORD` | `false` | do not print the initial admin password in the logs, even when it has been randomly generated |
| `otoroshi.writeInitialAdminPasswordToFile` | `OTOROSHI_WRITE_INITIAL_ADMIN_PASSWORD_TO_FILE` | `false` | write the randomly generated initial admin password to a file instead of printing it in the logs |
| `otoroshi.initialAdminPasswordFile` | `OTOROSHI_INITIAL_ADMIN_PASSWORD_FILE` | `/etc/otoroshi/initial_admin_password` | path of the file where the randomly generated initial admin password is written |

When the password is **provided** through `otoroshi.adminPassword` (or its environment variable), it is never printed in the logs nor written to a file: you already know it.

When the password is **randomly generated**, Otoroshi makes it available to you, depending on the flags above:

- if `otoroshi.writeInitialAdminPasswordToFile` is `true`, it is written to `otoroshi.initialAdminPasswordFile` (created with owner read/write only permissions, i.e. `0600`, à la GitLab `/etc/gitlab/initial_root_password`) and **never printed in the logs** — only the path is logged. This takes precedence over `hideInitialAdminPassword` ;
- otherwise, if `otoroshi.hideInitialAdminPassword` is `true`, the password is not printed in the logs at all (and you will need an explicit `adminPassword` to log in) ;
- otherwise (the default), it is printed once in the logs (`You can log into the Otoroshi admin console with the following credentials: ...`).

```sh
$ java \
  -Dotoroshi.writeInitialAdminPasswordToFile=true \
  -jar otoroshi.jar

[warn] otoroshi-env - The main datastore seems to be empty, registering some basic services
[info] otoroshi-env - The initial admin password has been written to '/etc/otoroshi/initial_admin_password'. Read it to log into the Otoroshi admin console.
```

:::warning Clean up
When `otoroshi.writeInitialAdminPasswordToFile` is enabled, Otoroshi **deletes the password file on the next reboot** (and logs the deletion). Make sure you read it before restarting the instance. Also make sure the process is allowed to write to the configured path: if the file cannot be written, the error is logged but the boot is not interrupted. On environments where `/etc/otoroshi` is not writable (Docker, ...), set `otoroshi.initialAdminPasswordFile` to a writable location.
:::

:::tip Security
Whichever way you retrieve the initial password, when you log into the admin dashboard for the first time, Otoroshi asks you to create another account to avoid security issues.
:::
