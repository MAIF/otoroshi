---
title: Getting Started Setup
---

:::tip Prerequisites
If you already have a running Otoroshi instance, you can skip the following instructions.
:::

Download the latest Otoroshi release:

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v17.14.0-dev/otoroshi.jar'
```

Start Otoroshi with a custom admin password:

```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar
```

Once started, access the Otoroshi admin UI at [http://otoroshi.oto.tools:8080](http://otoroshi.oto.tools:8080) using the credentials `admin@otoroshi.io/password`.
