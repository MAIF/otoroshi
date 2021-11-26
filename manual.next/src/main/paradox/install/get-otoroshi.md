# Get Otoroshi

All release can be bound on the releases page of the repository ([visit it](`https://github.com/MAIF/otoroshi/releases`)).

## From zip

```sh
# Download the latest beta version
wget https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi-1.5.0-beta.8.zip

# or the latest stable version
wget https://github.com/MAIF/otoroshi/releases/download/v1.4.22/otoroshi-1.4.22.zip
```

## From jar file

```sh
# Download the latest beta version
wget https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi.jar

# or the latest stable version
wget https://github.com/MAIF/otoroshi/releases/download/v1.4.22/otoroshi.jar
```

## From Docker

```sh
# Download the latest beta version
docker pull maif/otoroshi:1.5.0-beta.8-jdk11

#or the latest stable version
docker pull maif/otoroshi:1.4.22-jdk12
```

## From Sources

To build Otoroshi from sources, you need the following tools :

* git
* JDK 8 or 11
* SBT
* node
* yarn

Once you've installed all those tools, go to the [Otoroshi github page](https://github.com/MAIF/otoroshi) and clone the sources :

```sh
git clone https://github.com/MAIF/otoroshi.git --depth=1
```

then you need to run the `build.sh` script to build the documentation, the React UI and the server :

```sh
sh ./scripts/build.sh
```

and that's all, you can grab your Otoroshi package at `otoroshi/target/scala-2.12/otoroshi` or `otoroshi/target/universal/`.

For those who want to build only parts of Otoroshi, read the following.

## Build the documentation only

Go to the `documentation` folder and run :

```sh
sbt ';clean;paradox'
```

The documentation is located at `manual/target/paradox/site/main/`

## Build the React UI

Go to the `otoroshi/javascript` folder and run :

```sh
yarn install
yarn build
```

You will find the JS bundle at `otoroshi/public/javascripts/bundle/bundle.js`.

## Build the Otoroshi server

Go to the `otoroshi` folder and run :

```sh
export SBT_OPTS="-Xmx2G -Xss6M"
sbt ';clean;compile;dist;assembly'
```

You will find your Otoroshi package at `otoroshi/target/scala-2.12/otoroshi` or `otoroshi/target/universal/`.
