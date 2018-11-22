# Rust CLI

Otoroshi provides a command line tool to command Otoroshi instances.

## Download

First, download the Otoroshi CLI depending on your operating system :

```sh
curl -L -o otoroshicli https://dl.bintray.com/maif/binaries/linux-otoroshicli/1.4.0/otoroshicli
# or if you use linux
curl -L -o otoroshicli https://dl.bintray.com/maif/binaries/mac-otoroshicli/1.3.2-dev/otoroshicli
# or if you use windows
curl -L -o otoroshicli.exe https://dl.bintray.com/maif/binaries/win-otoroshicli/1.3.2-dev/otoroshicli.exe
```

## Usage

The documentation is not written yet, but you can explore the cli usage using the help flag `-h`

You can read more about it [here](https://github.com/MAIF/otoroshi/tree/master/clients/cli).

```
$ otoroshicli -h

otoroshicli 1.3.2-dev
   ____  __________  ____  ____  _____ __  ______     ________    ____
  / __ \/_  __/ __ \/ __ \/ __ \/ ___// / / /  _/    / ____/ /   /  _/
 / / / / / / / / / / /_/ / / / /\__ \/ /_/ // /_____/ /   / /    / /
/ /_/ / / / / /_/ / _, _/ /_/ /___/ / __  // /_____/ /___/ /____/ /
\____/ /_/  \____/_/ |_|\____//____/_/ /_/___/     \____/_____/___/

A simple CLI to control the Otoroshi reverse proxy (https://maif.github.io/otoroshi).
You have to provide a $HOME/.otoroshicli.toml or a $PWD/otoroshicli.toml config file with
'host', 'client_id', 'client_secret' values to access the Otoroshi instance you need.

USAGE:
    otoroshicli [FLAGS] [OPTIONS] [SUBCOMMAND]

FLAGS:
        --debug        Debug output
    -h, --help         Prints help information
    -n, --no-colors    Does not color output
    -V, --version      Prints version information

OPTIONS:
        --oto-client-id <CLIENT_ID>            Sets the client ID for the Otoroshi instance
        --oto-client-secret <CLIENT_SECRET>    Sets the client secret for the Otoroshi instance
        --oto-host <HOST>                      Sets the host where Otoroshi is available
        --oto-url <URL>                        Sets the URL where Otoroshi is available
        --select <JSON_POINTER>                Select subset of the response

SUBCOMMANDS:
    apikeys     Control Otoroshi Api Keys
    config      Control global config of Otoroshi
    export      Export the full Otoroshi state
    groups      Control Otoroshi service groups
    help        Prints this message or the help of the given subcommand(s)
    import      Import the full Otoroshi state, erase all previous state
    lines       Control Otoroshi lines
    services    Control Otoroshi services
    stats       Fetch Otoroshi's global stats
    tryout      Features for Otoroshi tryout
```

then :

```
$ otoroshicli apikeys -h

otoroshicli-apikeys
Control Otoroshi Api Keys

USAGE:
    otoroshicli apikeys [FLAGS] [OPTIONS] [SUBCOMMAND]

FLAGS:
        --debug        Debug output
    -h, --help         Prints help information
    -n, --no-colors    Does not color output
    -V, --version      Prints version information

OPTIONS:
        --oto-client-id <CLIENT_ID>            Sets the client ID for the Otoroshi instance
        --oto-client-secret <CLIENT_SECRET>    Sets the client secret for the Otoroshi instance
        --oto-host <HOST>                      Sets the host where Otoroshi is available
        --oto-url <URL>                        Sets the URL where Otoroshi is available
        --select <JSON_POINTER>                Select subset of the response

SUBCOMMANDS:
    all       List Otoroshi Api Keys
    count     Count Otoroshi API Keys for a service group
    create    Create an Otoroshi Api Key
    delete    Delete an Otoroshi Api Key
    from      List Otoroshi Api Keys for a service group
    help      Prints this message or the help of the given subcommand(s)
    update    Update an Otoroshi Api Key
```
