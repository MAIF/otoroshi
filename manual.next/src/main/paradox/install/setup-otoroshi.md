# Setup Otoroshi

## Choose your datastore

Right now, Otoroshi supports multiple datastore. You can choose one datastore over another depending on your use case.

@@@div { .plugin .platform } 
## In memory

The **in-memory** datastore is kind of interesting. It can be used for testing purposes, but it is also a good candidate for production because of its fastness.

<img src="../imgs/inmemory.png" />

@ref:[Start with](../getting-started.md)
@@@

@@@div { .plugin .platform } 
## Redis

The **redis** datastore is quite nice when you want to easily deploy several Otoroshi instances.
If you need a datastore more scalable than redis, then you can use the **postgresql** or **cassandra** datastore.

<img src="../imgs/redis.png" />

@link:[Documentation](https://redis.io/topics/quickstart)
@@@

@@@div { .plugin .platform } 
## Cassandra

Experimental support, should be used in cluster mode for leaders

<img src="../imgs/cassandra.png" />

@link:[Documentation](https://cassandra.apache.org/doc/latest/cassandra/getting_started/installing.html)
@@@

@@@div { .plugin .platform } 
## Postgresql

Or any postgresql compatible databse like cockroachdb for instance (experimental support, should be used in cluster mode for leaders)

<img src="../imgs/postgres.png" />

@link:[Documentation](https://www.postgresql.org/docs/10/tutorial-install.html)
@@@

@@@div { .plugin .platform } 
## FileDB

The **filedb** datastore is pretty handy for testing purposes, but is not supposed to be used in production mode. 
Not suitable for production usage.

<img src="../imgs/filedb.png" />

@@@

@@@div { .break }
## Environnement variables
@@@

@@@ note
TODO: This section is being written, thanks for your patience :)
@@@