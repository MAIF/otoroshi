# Config. with ENVs

Now that you know @ref:[how to configure Otoroshi with the config. file](./configfile.md) every propety in the following block can be overriden by an environment variable (an env. variable is written like `${?ENV_VARIABLE}`).

```
app.storage = ${?APP_STORAGE}
app.importFrom = ${?APP_IMPORT_FROM}
app.domain = ${?APP_DOMAIN}
app.rootScheme = ${?APP_ROOT_SCHEME}
app.snowflake.seed = ${?INSTANCE_NUMBER}
app.events.maxSize = ${?MAX_EVENTS_SIZE}
app.backoffice.subdomain = ${?APP_BACKOFFICE_SUBDOMAIN}
app.backoffice.session.exp = ${?APP_BACKOFFICE_SESSION_EXP}
app.privateapps.subdomain = ${?APP_PRIVATEAPPS_SUBDOMAIN}
app.privateapps.session.exp = ${?APP_PRIVATEAPPS_SESSION_EXP}
app.adminapi.targetSubdomain = ${?ADMIN_API_TARGET_SUBDOMAIN}
app.adminapi.exposedDubdomain = ${?ADMIN_API_EXPOSED_SUBDOMAIN}
app.adminapi.defaultValues.backOfficeGroupId = ${?ADMIN_API_GROUP}
app.adminapi.defaultValues.backOfficeApiKeyClientId = ${?ADMIN_API_CLIENT_ID}
app.adminapi.defaultValues.backOfficeApiKeyClientSecret = ${?ADMIN_API_CLIENT_SECRET}
app.adminapi.defaultValues.backOfficeServiceId = ${?ADMIN_API_SERVICE_ID}
app.adminapi.proxy.https = ${?ADMIN_API_HTTPS}
app.adminapi.proxy.local = ${?ADMIN_API_LOCAL}
app.claim.sharedKey = ${?CLAIM_SHAREDKEY}
app.webhooks.size = ${?WEBHOOK_SIZE}
app.redis.host = ${?REDIS_HOST}
app.redis.port = ${?REDIS_PORT}
app.redis.password = ${?REDIS_PASSWORD}
app.redis.windowSize = ${?REDIS_WINDOW_SIZE}
app.redis.useScan =  ${?REDIS_USE_SCAN}
app.inmemory.windowSize = ${?INMEMORY_WINDOW_SIZE}
app.leveldb.windowSize = ${?LEVELDB_WINDOW_SIZE}
app.leveldb.path = ${?LEVELDB_PATH}
app.cassandra.windowSize = ${?CASSANDRA_WINDOW_SIZE}
app.cassandra.hosts = ${?CASSANDRA_HOSTS}
app.cassandra.host = ${?CASSANDRA_HOST}
app.cassandra.port = ${?CASSANDRA_PORT}
http.port = ${?PORT}
play.crypto.secret = ${?PLAY_CRYPTO_SECRET}
play.http.session.secure = ${?SESSION_SECURE_ONLY}
play.http.session.maxAge = ${?SESSION_MAX_AGE}
play.http.session.domain = ${?SESSION_DOMAIN}
play.http.session.cookieName = ${?SESSION_NAME}
play.server.netty.transport = ${?NETTY_TRANSPORT}
play.ws.play.ws.useragent=${?USER_AGENT}
```