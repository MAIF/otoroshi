# Clever Cloud

Now you want to use Otoroshi on Clever Cloud. Otoroshi has been designed and created to run on Clever Cloud and a lot of choices were made because of how Clever Cloud works.

## Create an Otoroshi instance on CleverCloud

If you want to customize the configuration @ref:[use env. variables](../firstrun/env.md), you can use [the example provided below](#example-of-clevercloud-env-variables)

Create a new CleverCloud app based on a clevercloud git repo (not empty) or a github project of your own (not empty).

@@@ div { .centered-img }
<img src="../img/deploy-cc-jar-0.png" />
@@@

Then choose what kind of app your want to create, for Otoroshi, choose `Java + Jar`

@@@ div { .centered-img }
<img src="../img/deploy-cc-jar-1.png" />
@@@

Next, set up choose instance size and auto-scalling. Otoroshi can run on small instances, especially if you just want to test it.

@@@ div { .centered-img }
<img src="../img/deploy-cc-2.png" />
@@@

Finally, choose a name for your app

@@@ div { .centered-img }
<img src="../img/deploy-cc-3.png" />
@@@

Now you just need to customize environnment variables

at this point, you can also add other env. variables to configure Otoroshi like in [the example provided below](#example-of-clevercloud-env-variables)

@@@ div { .centered-img }
<img src="../img/deploy-cc-4-bis.png" />
@@@

You can also use expert mode :

@@@ div { .centered-img }
<img src="../img/deploy-cc-4.png" />
@@@

Now, your app is ready, don't forget to add a custom domains name on the CleverCloud app matching the Otoroshi app domain. 

## Example of CleverCloud env. variables

You can add more env variables to customize your Otoroshi instance like the following. Use the expert mode to copy/paste all the values in one shot. Don't forget to change all `xxxx` values by random values. Don't forget to redirect your domain name to clevercloud. Also, change the `latest_otoroshi_version` to the latest otoroshi version published on github. If you want an real datastore, create a redis addon on clevercloud, link it to your otoroshi app and change the `APP_STORAGE` variable to `redis`

```
ADMIN_API_CLIENT_ID=xxxx
ADMIN_API_CLIENT_SECRET=xxxxx
ADMIN_API_GROUP=xxxxxx
ADMIN_API_SERVICE_ID=xxxxxxx
CLAIM_SHAREDKEY=xxxxxxx
OTOROSHI_INITIAL_ADMIN_LOGIN=youremailaddress
OTOROSHI_INITIAL_ADMIN_PASSWORD=yourpassword
PLAY_CRYPTO_SECRET=xxxxxx
SESSION_NAME=oto-session
APP_DOMAIN=yourdomain.tech
APP_ENV=prod
APP_STORAGE=inmemory
APP_ROOT_SCHEME=https
CC_PRE_BUILD_HOOK=curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/${latest_otoroshi_version}/otoroshi.jar'
CC_JAR_PATH=./otoroshi.jar
CC_JAVA_VERSION=11
PORT=8080
SESSION_DOMAIN=.yourdomain.tech
SESSION_MAX_AGE=604800000
SESSION_SECURE_ONLY=true
USER_AGENT=otoroshi
MAX_EVENTS_SIZE=1
WEBHOOK_SIZE=100
APP_BACKOFFICE_SESSION_EXP=86400000
APP_PRIVATEAPPS_SESSION_EXP=86400000
ENABLE_METRICS=true
OTOROSHI_ANALYTICS_PRESSURE_ENABLED=true
USE_CACHE=true
```