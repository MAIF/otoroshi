# From docker

If your a Docker aficionado, Otoroshi is provided as a Docker image that your can pull directly from Official repos.

first, fetch the last Docker image of Otoroshi

```sh
docker pull otoroshi
```

then just run it

```sh
docker run -p "8080:8080" otoroshi
```

you can also pass useful args like 

```
docker run -p "8080:8080" otoroshi -Dconfig.file=/home/user/otoroshi.conf -Dlogger.file=./home/user/otoroshi.xml
```

If you want to provide your own config file, you can read @ref:[the documentation about config files](../firstrun/configfile.md)

You can also provide some ENV variable using the `--env` flag to customize your Otoroshi instance

The list of possible env variables is available @ref:[here](../firstrun/env.md)

