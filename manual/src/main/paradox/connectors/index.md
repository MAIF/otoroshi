# Connectors

Otoroshi provides some connectors to be used in some use cases that could be useful

* [Elastic connector](https://github.com/MAIF/otoroshi/tree/master/connectors/elasticsearch) : a connector to send Otoroshi events to an Elastic cluster. Provides the apis needed to make Otoroshi analytics works
* [rancher connector](https://github.com/MAIF/otoroshi/tree/master/connectors/rancher) : a connector to synchronize a rancher cluster with Otoroshi on the fly
* [kubernetes connector](https://github.com/MAIF/otoroshi/tree/master/connectors/kubernetes) : a connector to synchronize a kubernetes cluster with Otoroshi on the fly
* [Clever-Cloud connector](https://github.com/MAIF/otoroshi/tree/master/connectors/clevercloud) : a connector to synchronize a Clever-Cloud organization with Otoroshi on the fly

If you want to build your own connector, Otoroshi provides a common library for synchronization. You can find the code here 

https://github.com/MAIF/otoroshi/tree/master/connectors/common

@@@ index

* [clevercloud](./clevercloud.md)
* [kubernetes](./kubernetes.md)
* [rancher](./rancher.md)
* [Elastic](./elastic.md)

@@@