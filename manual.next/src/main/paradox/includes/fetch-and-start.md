<!--- #init --->
If you already have an up and running otoroshi instance, you can skip the following instructions

Let's start by downloading the latest Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi.jar'
```

then you can run start Otoroshi :

```sh
java -Dapp.adminPassword=password -jar otoroshi.jar 
```

Now you can log into Otoroshi at http://otoroshi.oto.tools:8080 with `admin@otoroshi.io/password`
<!--- #init --->