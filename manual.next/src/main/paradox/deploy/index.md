# Deploy to production

Now it's time to deploy Otoroshi in production, in this chapter we will see what kind of things you can do.

Otoroshi can run wherever you want, even on a raspberry pi (Cluster^^) ;)

@@@div { .plugin .platform }
## Clever Cloud

Otoroshi provides an integration to create easily services based on application deployed on your Clever Cloud account.

<img src="../imgs/clever-cloud.png" />
@ref:[Documentation](./clever-cloud.md)
@@@

@@@div { .plugin .platform } 
## Kubernetes
Starting at version 1.5.0, Otoroshi provides a native Kubernetes support.

<img src="../imgs/kubernetes.png" />
@ref:[Documentation](./kubernetes.md)
@@@

@@@div { .plugin .platform } 
## AWS Elastic Beanstalk

Run Otoroshi on AWS Elastic Beanstalk

<img src="../imgs/elastic-beanstalk.png" />

@ref:[Tutorial](./aws.md)
@@@

@@@div { .plugin .platform } 
## Amazon ECS

Deploy the Otoroshi Docker image using Amazon Elastic Container Service

<img src="../imgs/amazon-ecs.png" />

@link:[Tutorial](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html)
@ref:[Docker image](../install/get-otoroshi.md#from-docker)

@@@

@@@div { .plugin .platform }
## GCE

Deploy the Docker image using Google Compute Engine container integration

<img src="../imgs/google.jpeg" />

@link:[Documentation](https://cloud.google.com/compute/docs/containers/deploying-containers)
@ref:[Docker image](../install/get-otoroshi.md#from-docker)

@@@

@@@div { .plugin .platform } 
## Azure

Deploy the Docker image using Azure Container Service

<img src="../imgs/azure-container-service.png" />

@link:[Documentation](https://azure.microsoft.com/en-us/services/container-service/)
@ref:[Docker image](../install/get-otoroshi.md#from-docker) 
@@@

@@@div { .plugin .platform } 
## Heroku

Deploy the Docker image using Docker integration

<img src="../imgs/heroku.png" />

@link:[Documentation](https://devcenter.heroku.com/articles/container-registry-and-runtime)
@ref:[Docker image](../install/get-otoroshi.md#from-docker)
@@@

@@@div { .plugin .platform } 
## CloudFoundry

Deploy the Docker image using -Docker integration

<img src="../imgs/cloudfoundry.png" />

@link:[Documentation](https://docs.cloudfoundry.org/adminguide/docker.html)
@ref:[Docker image](../install/get-otoroshi.md#from-docker)
@@@

@@@div { .plugin .platform .platform-actions-column } 
## Your own infrastructure

As Otoroshi is a Play Framework application, you can read the doc about putting a `Play` app in production.

Download the latest Otoroshi distribution, unzip it, customize it and run it.

@link:[Play Framework](https://www.playframework.com)
@link:[Production Configuration](https://www.playframework.com/documentation/2.6.x/ProductionConfiguration)
@ref:[Otoroshi distribution](../install/get-otoroshi.md#from-zip)
@@@

@@@div { .break }
## Scaling and clustering in production
@@@


@@@div { .plugin .platform .dark-platform } 
## Clustering

Deploy Otoroshi as a cluster of leaders and workers.

<img src="../imgs/clustering.png" />
@ref:[Documentation](./clustering.md)
@@@

@@@div { .plugin .platform .dark-platform } 
## Scaling Otoroshi

Otoroshi is designed to be reasonably easy to scale and be highly available.

<img src="../imgs/scaling.png" />
@ref:[Documentation](./scaling.md) 
@@@

@@@ index

* [Clustering](./clustering.md)
* [Kubernetes](./kubernetes.md)
* [Clever Cloud](./clever-cloud.md)
* [AWS - Elastic Beanstalk](./aws.md)
* [Scaling](./scaling.md)  

@@@
