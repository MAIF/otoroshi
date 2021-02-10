# Choose your datastore

Right now, Otoroshi supports multiple datastore.

You can choose one datastore over another depending on your use case.

Available datastores are the following :

* in memory
* redis
* cassandra (experimental support, should be used in cluster mode for leaders)
* postgresql or any postgresql compatible databse like cockroachdb for instance (experimental support, should be used in cluster mode for leaders)
* filedb (not suitable for production usage)

The **filedb** datastore is pretty handy for testing purposes, but is not supposed to be used in production mode.

The **in-memory** datastore is kind of interesting... It can be used for testing purposes, but it is also a good candidate for production because of its fastness. You can check the clustering documentation to find more about it.

The **redis** datastore is quite nice when you want to easily deploy several Otoroshi instances.

If you need a datastore more scalable than redis, then you can use the **postgresql** or **cassandra** datastore.

@@@ div { .centered-img }
<img src="../img/datastores.png" />
@@@
