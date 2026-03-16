---
title: Deploy to production
sidebar_label: "Overview"
sidebar_position: 1
slug: /deploy
---
# Deploy to production

Now it's time to deploy Otoroshi in production, in this chapter we will see what kind of things you can do.

Otoroshi can run wherever you want, even on a raspberry pi (Cluster^^) ;)


## Cloud APIM

Cloud APIM provides Otoroshi instances as a service. You can easily create production ready Otoroshi clusters in just a few clics.

<img src="https://www.cloud-apim.com/assets/logo/512x512.png" />
[Documentation](https://www.cloud-apim.com/)
:::
## Clever Cloud

Otoroshi provides an integration to create easily services based on application deployed on your Clever Cloud account.

<img src="./img/docs/clever-cloud.png" />
[Documentation](./clever-cloud.md)
:::
## Kubernetes
Starting at version 1.5.0, Otoroshi provides a native Kubernetes support.

<img src="./img/docs/kubernetes.png" />

[Documentation](./kubernetes.md)
:::
## AWS Elastic Beanstalk

Run Otoroshi on AWS Elastic Beanstalk

<img src="./img/docs/elastic-beanstalk.png" />

[Tutorial](./aws.md)
:::
## Amazon ECS

Deploy the Otoroshi Docker image using Amazon Elastic Container Service

<img src="./img/docs/amazon-ecs.png" />

@link:[Tutorial](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html)
[Docker image](../install/get-otoroshi.md#from-docker)

:::
## GCE

Deploy the Docker image using Google Compute Engine container integration

<img src="./img/docs/google.jpeg" />

@link:[Documentation](https://cloud.google.com/compute/docs/containers/deploying-containers)
[Docker image](../install/get-otoroshi.md#from-docker)

:::
## Azure

Deploy the Docker image using Azure Container Service

<img src="./img/docs/azure-container-service.png" />

@link:[Documentation](https://azure.microsoft.com/en-us/services/container-service/)
[Docker image](../install/get-otoroshi.md#from-docker) 
:::
## Heroku

Deploy the Docker image using Docker integration

<img src="./img/docs/heroku.png" />

@link:[Documentation](https://devcenter.heroku.com/articles/container-registry-and-runtime)
[Docker image](../install/get-otoroshi.md#from-docker)
:::
## CloudFoundry

Deploy the Docker image using -Docker integration

<img src="./img/docs/cloudfoundry.png" />

@link:[Documentation](https://docs.cloudfoundry.org/adminguide/docker.html)
[Docker image](../install/get-otoroshi.md#from-docker)
:::
## Your own infrastructure

As Otoroshi is a Play Framework application, you can read the doc about putting a `Play` app in production.

Download the latest Otoroshi distribution, unzip it, customize it and run it.

@link:[Play Framework](https://www.playframework.com)
@link:[Production Configuration](https://www.playframework.com/documentation/2.6.x/ProductionConfiguration)
[Otoroshi distribution](../install/get-otoroshi.md#from-zip)
:::

## Scaling and clustering in production
:::
## Clustering

Deploy Otoroshi as a cluster of leaders and workers.

<img src="./img/docs/clustering.png" />
[Documentation](./clustering.md)
:::
## Scaling Otoroshi

Otoroshi is designed to be reasonably easy to scale and be highly available.

<img src="./img/docs/scaling.png" />
[Documentation](./scaling.md) 
:::