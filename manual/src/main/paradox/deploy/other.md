# Others

Otoroshi can run wherever you want, even on a raspberry pi (Cluster^^) ;)

This section is not finished yet. So, as Otoroshi is available as a @ref:[Docker image](../getotoroshi/fromdocker.md) that you can run on any Docker compatible cloud, just go ahead and use it on cloud provider until we have more detailed documentation.

## Running Otoroshi on AWS

Deploy the @ref:[Docker image](../firstrun/run.md#from-docker) using [Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html)

## Running Otoroshi on GCE

Deploy the @ref:[Docker image](../firstrun/run.md#from-docker) using [Google Compute Engine container integration](https://cloud.google.com/compute/docs/containers/deploying-containers)

## Running Otoroshi on Azure

Deploy the @ref:[Docker image](../firstrun/run.md#from-docker) using [Azure Container Service](https://azure.microsoft.com/en-us/services/container-service/)

## Running Otoroshi on Heroku

Deploy the @ref:[Docker image](../firstrun/run.md#from-docker) using [Docker integration](https://devcenter.heroku.com/articles/container-registry-and-runtime)

## Running Otoroshi on CloudFoundry

Deploy the @ref:[Docker image](../firstrun/run.md#from-docker) using [Docker integration](https://docs.cloudfoundry.org/adminguide/docker.html)

## Running Otoroshi on your own infrastructure

As Otoroshi is a [Play Framework](https://www.playframework.com) application, you can read the doc about putting a `Play` app in production.

https://www.playframework.com/documentation/2.6.x/ProductionConfiguration

Download the latest @ref:[Otoroshi distribution](../getotoroshi/frombinaries.md), unzip it, customize it and run it.
