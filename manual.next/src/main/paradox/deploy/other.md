# Others

Otoroshi can run wherever you want, even on a raspberry pi (Cluster^^) ;)

This section is not finished yet. So, as Otoroshi is available as a @ref:[Docker image](../install/get-otoroshi.md#from-docker) that you can run on any Docker compatible cloud, just go ahead and use it on cloud provider until we have more detailed documentation.

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