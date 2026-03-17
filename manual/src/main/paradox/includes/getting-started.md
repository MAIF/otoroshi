<!--- #getting-started --->

If you already have a running Otoroshi instance, you can skip the following instructions.

@@@div { .instructions }

<div id="instructions-toggle">
<span class="instructions-title">Set up Otoroshi</span>
<button id="instructions-toggle-button">close</button>
</div>

Download the latest Otoroshi release:

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v17.12.0-dev/otoroshi.jar'
```

Start Otoroshi with a custom admin password:

```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar
```

Once started, access the Otoroshi admin UI at http://otoroshi.oto.tools:8080 using the credentials `admin@otoroshi.io/password`.

<button id="instructions-toggle-confirm">Confirm the installation</button>
@@@
<!--- #getting-started --->