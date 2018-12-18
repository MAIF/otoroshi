# Otoroshi clustering

Otoroshi can work as a cluster by default as you can spin many Otoroshi servers using the same datastore or datastore cluster. In that case any instance is capable of serving services, Otoroshi admin UI, Otoroshi admin API, etc.

But sometimes, this is not enough. So Otoroshi provides an additional clustering model named `Leader / Workers` where there is a leader cluster ([control plane](https://en.wikipedia.org/wiki/Control_plane)), composed of Otoroshi instances backed by a datastore like Redis, Cassandra or Mongo, that is in charge of all `writes` to the datastore through Otoroshi admin UI and API, and a worker cluster ([data plane](https://en.wikipedia.org/wiki/Forwarding_plane)) composed of horizontally scalable Otoroshi instances, backed by a super fast in memory datastore, with the sole purpose of routing traffic to your services based on data synced from the leader cluster. With this distributed Otoroshi version, you can reach your goals of high availability, scalability and security.

Otoroshi clustering only uses http internally (right now) to make communications between leaders and workers instances so it is fully compatible with PaaS providers like [Clever-Cloud](https://www.clever-cloud.com/en/) that only provide one external port for http traffic.

@@@ div { .centered-img }
<img src="../img/cluster-6.png" />

*Fig. 1: Simplified view*
@@@

@@@ div { .centered-img }
<img src="../img/cluster-5.jpg" />

*Fig. 2: Deployment view*
@@@

## Cluster configuration

```hocon
otoroshi {
  cluster {
    mode = "leader" # can be "off", "leader", "worker"
    compression = 4 # compression of the data sent between leader cluster and worker cluster. From -1 (disabled) to 9
    leader {
      name = ${?CLUSTER_LEADER_NAME}   # name of the instance, if none, it will be generated
      urls = ["http://127.0.0.1:8080"] # urls to contact the leader cluster
      host = "otoroshi-api.foo.bar"    # host of the otoroshi api in the leader cluster
      clientId = "apikey-id"           # otoroshi api client id
      clientSecret = "secret"          # otoroshi api client secret
      cacheStateFor = 4000             # state is cached during (ms)
    }
    worker {
      name = ${?CLUSTER_WORKER_NAME}   # name of the instance, if none, it will be generated
      retries = 3                      # number of retries when calling leader cluster
      timeout = 2000                   # timeout when calling leader cluster
      state {
        retries = ${otoroshi.cluster.worker.retries} # number of retries when calling leader cluster on state sync
        pollEvery = 10000                            # interval of time (ms) between 2 state sync
        timeout = ${otoroshi.cluster.worker.timeout} # timeout when calling leader cluster on state sync
      }
      quotas {
        retries = ${otoroshi.cluster.worker.retries} # number of retries when calling leader cluster on quotas sync
        pushEvery = 2000                             # interval of time (ms) between 2 quotas sync
        timeout = ${otoroshi.cluster.worker.timeout} # timeout when calling leader cluster on quotas sync
      }
    }
  }
}
```

you can also use many env. variables to configure Otoroshi cluster

```hocon
otoroshi {
  cluster {
    mode = ${?CLUSTER_MODE}
    compression = ${?CLUSTER_COMPRESSION}
    leader {
      name = ${?CLUSTER_LEADER_NAME}
      host = ${?CLUSTER_LEADER_HOST}
      clientId = ${?CLUSTER_LEADER_CLIENT_ID}
      clientSecret = ${?CLUSTER_LEADER_CLIENT_SECRET}
      groupingBy = ${?CLUSTER_LEADER_GROUP_BY}
      cacheStateFor = ${?CLUSTER_LEADER_CACHE_STATE_FOR}
      stateDumpPath = ${?CLUSTER_LEADER_DUMP_PATH}
    }
    worker {
      name = ${?CLUSTER_WORKER_NAME}
      retries = ${?CLUSTER_WORKER_RETRIES}
      timeout = ${?CLUSTER_WORKER_TIMEOUT}
      state {
        retries = ${?CLUSTER_WORKER_STATE_RETRIES}
        pollEvery = ${?CLUSTER_WORKER_POLL_EVERY}
        timeout = ${?CLUSTER_WORKER_POLL_TIMEOUT}
      }
      quotas {
        retries = ${?CLUSTER_WORKER_QUOTAS_RETRIES}
        pushEvery = ${?CLUSTER_WORKER_PUSH_EVERY}
        timeout = ${?CLUSTER_WORKER_PUSH_TIMEOUT}
      }
    }
  }
}
```

@@@ warning
You **should** use HTTPS exposition for the Otoroshi API that will be used for data sync as sensitive informations are exchanged between control plane and data plane.
@@@

@@@ warning
You **must** have the same cluster configuration on every Otoroshi instance (worker/leader) with only names and mode changed for each instance. Some things in leader/worker are computed using configuration of their counterpart worker/leader.
@@@

## Cluster UI

Once an Otoroshi instance is launcher as cluster Leader, a new row of live metrics tile will be available on the home page of Otoroshi admin UI.

@@@ div { .centered-img }
<img src="../img/cluster-3.png" />
@@@

you can also access a more detailed view of the cluster at `Settings (cog icon) / Cluster View`

@@@ div { .centered-img }
<img src="../img/cluster-4.png" />
@@@

## Run examples

for leader 

```sh
java -Dhttp.port=8091 -Dhttps.port=9091 -Dotoroshi.cluster.mode=leader -jar otoroshi.jar
```

for worker

```sh
java -Dhttp.port=8092 -Dhttps.port=9092 -Dotoroshi.cluster.mode=worker \
  -Dotoroshi.cluster.leader.urls.0=http://127.0.0.1:8091 -jar otoroshi.jar
```
