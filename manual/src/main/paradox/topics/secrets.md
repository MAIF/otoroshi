# Secrets management

@@include[experimental.md](../includes/experimental.md) { .experimental-feature }

Secrets are generally confidential values that should not appear in plain text in the application. There are several products that help you store, retrieve, and rotate these secrets securely. Otoroshi offers a mechanism to set up references to these secrets in its entities to benefits from the perks of your existing secrets management infrastructure. This feature only work with the @ref:[new proxy engine](./engine.md).

A secret can be anything you want like an apikey secret, a certificate private key or password, a jwt verifier signing key, a password to a proxy, a value for a header, etc.

## Enable secrets management in otoroshi

By default secrets management is disbaled. You can enable it by setting `otoroshi.vaults.enabled` or `${OTOROSHI_VAULTS_ENABLED}` to `true`.

## Global configuration

Secrets management can be only configured using otoroshi static configuration file (also using jvm args mechanism). 
The configuration is located at `otoroshi.vaults` where you can find the global configuration of the secrets management system and the configurations for each enabled secrets management backends. Basically it looks like

```conf
vaults {
  enabled = false
  enabled = ${?OTOROSHI_VAULTS_ENABLED}
  secrets-ttl = 300000 # 5 minutes
  secrets-ttl = ${?OTOROSHI_VAULTS_SECRETS_TTL}
  cached-secrets = 10000
  cached-secrets = ${?OTOROSHI_VAULTS_CACHED_SECRETS}
  read-timeout = 10000 # 10 seconds
  read-timeout = ${?OTOROSHI_VAULTS_READ_TIMEOUT}
  # if enabled, only leader nodes fetches the secrets.
  # entities with secret values filled are then sent to workers when they poll the cluster state.
  # only works if `otoroshi.cluster.autoUpdateState=true`
  leader-fetch-only = false
  leader-fetch-only = ${?OTOROSHI_VAULTS_LEADER_FETCH_ONLY}
  env {
    type = "env"
    prefix = ${?OTOROSHI_VAULTS_ENV_PREFIX}
  }
}
```

you can see here the global configuration and a default backend configured that can retrieve secrets from environment variables. 

The configuration keys can be used for 

- `secrets-ttl`: the amount of milliseconds before the secret value is read again from backend
- `cached-secrets`: the number of secrets that will be cached on an otoroshi instance
- `read-timeout`: the timeout (in milliseconds) to read a secret from a backend

## Entities with secrets management

the entities that support secrets management are the following 

- `routes`
- `services`
- `service_descriptors`
- `apikeys`
- `certificates`
- `jwt_verifiers`
- `authentication_modules`
- `targets`
- `backends`
- `tcp_services`
- `data_exporters`

## Define a reference to a secret

in the previously listed entities, you can define, almost everywhere, references to a secret using the following syntax:

`${vault://name_of_the_vault/secret/of/the/path}`

let say I define a new apikey with the following value as secret `${vault://my_env/apikey_secret}` with the following secrets management configuration

```conf
vaults {
  enabled = true
  secrets-ttl = 300000
  cached-secrets = 10000
  read-ttl = 10000
  my_env {
    type = "env"
  }
}
```

if the machine running otoroshi has an environment variable named `APIKEY_SECRET` with the value `verysecret`, then you will be able to can an api with the defined apikey `client_id` and a `client_secret` value of `verysecret`

```sh
curl 'http://my-awesome-api.oto.tools:8080/api/stuff' -u awesome_apikey:verysecret
```

## Possible backends

Otoroshi comes with the support of several secrets management backends.

### Environment variables

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "env"
    prefix = "the_prefix_added_to_the_name_of_the_env_variable"
  }
}
```

### Local

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "local"
    root = "the_root_path/in_otoroshi/environment"
  }
}
```

value of this vault can be configured in the danger zone > Global metadata > Otoroshi environment.

### Infisical

a backend for the awesome open source project [Infisical](https://infisical.com/). It support both E2EE and non E2EE secrets.

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "infisical"
    baseUrl = "https://app.infisical.com" # optional, fallback to https://app.infisical.com
    serviceToken = "st.xxxx.yyyy.zzzz" # the service token for your projet
    e2ee = true # are you secrets end to end encrypted
    defaultSecretType = "shared" # optional, fallback to shared
    defaultWorkspaceId = "xxxxxx" # optional, value can be passed in the secret address
    defaultEnvironment = "dev" # optional, value can be passed in the secret address
  }
}
```

you should define your references like `${vault://infisical_vault/my_secret_path?workspaceId=xxx&environment=dev&type=shared}`. `workspaceId`, `environment` and `type` are optional if filled in global config. You can also pass a `json_parse=true` and `json_pointer=/foo/bar` to handle the value like a json document a select a value inside it.

### Hashicorp Vault

a backend for [Hashicorp Vault](https://www.vaultproject.io/). Right now we only support KV engines.

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "hashicorp-vault"
    url = "http://127.0.0.1:8200"
    mount = "kv" # the name of the secret store in vault
    kv = "v2" # the version of the kv store (v1 or v2)
    token = "root" # the token that can access to your secrets
  }
}
```

you should define your references like `${vault://hashicorp_vault/secret/path/key_name}`.


### Azure Key Vault

a backend for [Azure Key Vault](https://azure.microsoft.com/en-en/services/key-vault/). Right now we only support secrets and not keys and certificates.

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "azure"
    url = "https://keyvaultname.vault.azure.net"
    api-version = "7.2" # the api version of the vault
    tenant = "xxxx-xxx-xxx" # your azure tenant id, optional
    client_id = "xxxxx" # your azure client_id
    client_secret = "xxxxx" # your azure client_secret
    # token = "xxx" possible if you have a long lived existing token. will take over tenant / client_id / client_secret
  }
}
```

you should define your references like `${vault://azure_vault/secret_name/secret_version}`. `secret_version` is mandatory

If you want to use certificates and keys objects from the azure key vault, you will have to specify an option in the reference named `azure_secret_kind` with possible value `certificate`, `privkey`, `pubkey` like the following :

```
${vault://azure_vault/myprivatekey/secret_version?azure_secret_kind=privkey}
```

### AWS Secrets Manager

a backend for [AWS Secrets Manager](https://aws.amazon.com/en/secrets-manager/)

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "aws"
    access-key = "key"
    access-key-secret = "secret"
    region = "eu-west-3" # the aws region of your secrets management
  }
}
```

you should define your references like `${vault://aws_vault/secret_name/secret_version}`. `secret_version` is optional

### Google Cloud Secrets Manager

a backend for [Google Cloud Secrets Manager](https://cloud.google.com/secret-manager)

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "gcloud"
    url = "https://secretmanager.googleapis.com"
    apikey = "secret"
  }
}
```

you should define your references like `${vault://gcloud_vault/projects/foo/secrets/bar/versions/the_version}`. `the_version` can be `latest`

### AlibabaCloud Cloud Secrets Manager

a backend for [AlibabaCloud Secrets Manager](https://www.alibabacloud.com/help/en/doc-detail/152001.html)

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "alibaba-cloud"
    url = "https://kms.eu-central-1.aliyuncs.com"
    access-key-id = "access-key"
    access-key-secret = "secret"
  }
}
```

you should define your references like `${vault://alibaba_vault/secret_name}`


### Kubernetes Secrets

a backend for [Kubernetes secrets](https://kubernetes.io/en/docs/concepts/configuration/secret/)

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "kubernetes"
    # see the configuration of the kubernetes plugin, 
    # by default if the pod if well configured, 
    # you don't have to setup anything
  }
}
```

you should define your references like `${vault://k8s_vault/namespace/secret_name/key_name}`. `key_name` is optional. if present, otoroshi will try to lookup `key_name` in the secrets `stringData`, if not defined the secrets `data` will be base64 decoded and used.


### Izanami config.

a backend for [Izanami config.](https://maif.github.io/izanami/manual/)


the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "izanami"
    url = "http://127.0.0.1:8200"
    client-id = "client"
    client-secret = "secret"
  }
}
```

you should define your references like `${vault://izanami_vault/the:secret:id/key_name}`. `key_name` is optional if the secret value is not a json object

### Spring Cloud Config

a backend for [Spring Cloud Config.](https://docs.spring.io/spring-cloud-config/docs/current/reference/html/)


the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "spring-cloud"
    url = "http://127.0.0.1:8000"
    root = "myapp/prod"
    headers {
      authorization = "Basic xxxx"
    }
  }
}
```

you should define your references like `${vault://spring_vault/the/path/of/the/value}` where `/the/path/of/the/value` is the path of the value.

### Http backend

a backend for that uses the result of an http endpoint

the configuration of this backend should be like

```conf
vaults {
  ...
  name_of_the_vault {
    type = "http"
    url = "http://127.0.0.1:8000/endpoint/for/config"
    headers {
      authorization = "Basic xxxx"
    }
  }
}
```

you should define your references like `${vault://http_vault/the/path/of/the/value}` where `/the/path/of/the/value` is the path of the value.
