# From sources

to build Otoroshi from sources, you need the following tools :

* git
* JDK 8
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

and thats all, you can grab your Otoroshi package at `otoroshi/target/scala-2.11/otoroshi` or `otoroshi/target/universal/`.

For those who want to build only parts of Otoroshi, read the following.

## Build the documentation only

Go to the `documentation` folder and run :

```sh
sbt ';clean;paradox'
```

The documentation is located at `documentation/target/paradox/site/main/`

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
sbt ';clean;compile;dist;assembly'
```

You will find your Otoroshi package at `otoroshi/target/scala-2.11/otoroshi` or `otoroshi/target/universal/`.
