---
title: Deploy to production
sidebar_label: "Overview"
sidebar_position: 1
slug: /deploy
---
# Deploy to production

Now it's time to deploy Otoroshi in production, in this chapter we will see what kind of things you can do.

Otoroshi can run wherever you want, even on a raspberry pi (Cluster^^) ;)

<div class="provider-grid">
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Cloud APIM</h4>
  </div>
  <p>Cloud APIM provides Otoroshi instances as a service. You can easily create production ready Otoroshi clusters in just a few clics.</p>
  <div class="provider-card-img"><img src="https://www.cloud-apim.com/assets/logo/512x512.png" alt="Cloud APIM" /></div>
  <div class="provider-card-action"><a href="https://www.cloud-apim.com/">Documentation</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Clever Cloud</h4>
  </div>
  <p>Otoroshi provides an integration to create easily services based on application deployed on your Clever Cloud account.</p>
  <div class="provider-card-img"><img src="/img/docs/clever-cloud.png" alt="Clever Cloud" /></div>
  <div class="provider-card-action"><a href="./clever-cloud">Documentation</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Kubernetes</h4>
  </div>
  <p>Starting at version 1.5.0, Otoroshi provides a native Kubernetes support.</p>
  <div class="provider-card-img"><img src="/img/docs/kubernetes.png" alt="Kubernetes" /></div>
  <div class="provider-card-action"><a href="./kubernetes">Documentation</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>AWS Elastic Beanstalk</h4>
  </div>
  <p>Run Otoroshi on AWS Elastic Beanstalk</p>
  <div class="provider-card-img"><img src="/img/docs/elastic-beanstalk.png" alt="AWS Elastic Beanstalk" /></div>
  <div class="provider-card-action"><a href="./aws">Tutorial</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Amazon ECS</h4>
  </div>
  <p>Deploy the Otoroshi Docker image using Amazon Elastic Container Service</p>
  <div class="provider-card-img"><img src="/img/docs/amazon-ecs.png" alt="Amazon ECS" /></div>
  <div class="provider-card-actions"><a href="https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html">Tutorial</a> <a href="../install/get-otoroshi#from-docker">Docker image</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>GCE</h4>
  </div>
  <p>Deploy the Docker image using Google Compute Engine container integration</p>
  <div class="provider-card-img"><img src="/img/docs/google.jpeg" alt="Google Cloud" /></div>
  <div class="provider-card-actions"><a href="https://cloud.google.com/compute/docs/containers/deploying-containers">Documentation</a> <a href="../install/get-otoroshi#from-docker">Docker image</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Azure</h4>
  </div>
  <p>Deploy the Docker image using Azure Container Service</p>
  <div class="provider-card-img"><img src="/img/docs/azure-container-service.png" alt="Azure" /></div>
  <div class="provider-card-actions"><a href="https://azure.microsoft.com/en-us/services/container-service/">Documentation</a> <a href="../install/get-otoroshi#from-docker">Docker image</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Heroku</h4>
  </div>
  <p>Deploy the Docker image using Docker integration</p>
  <div class="provider-card-img"><img src="/img/docs/heroku.png" alt="Heroku" /></div>
  <div class="provider-card-actions"><a href="https://devcenter.heroku.com/articles/container-registry-and-runtime">Documentation</a> <a href="../install/get-otoroshi#from-docker">Docker image</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>CloudFoundry</h4>
  </div>
  <p>Deploy the Docker image using Docker integration</p>
  <div class="provider-card-img"><img src="/img/docs/cloudfoundry.png" alt="CloudFoundry" /></div>
  <div class="provider-card-actions"><a href="https://docs.cloudfoundry.org/adminguide/docker.html">Documentation</a> <a href="../install/get-otoroshi#from-docker">Docker image</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Your own infrastructure</h4>
  </div>
  <p>As Otoroshi is a Play Framework application, download the latest distribution, unzip it, customize it and run it.</p>
  <div class="provider-card-actions"><a href="https://www.playframework.com/documentation/2.6.x/ProductionConfiguration">Production Config</a> <a href="../install/get-otoroshi#from-zip">Distribution</a></div>
</div>
</div>

## Scaling and clustering in production

<div class="provider-grid">
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Clustering</h4>
  </div>
  <p>Deploy Otoroshi as a cluster of leaders and workers.</p>
  <div class="provider-card-img"><img src="/img/docs/clustering.png" alt="Clustering" /></div>
  <div class="provider-card-action"><a href="./clustering">Documentation</a></div>
</div>
<div class="provider-card">
  <div class="provider-card-header">
    <h4>Scaling Otoroshi</h4>
  </div>
  <p>Otoroshi is designed to be reasonably easy to scale and be highly available.</p>
  <div class="provider-card-img"><img src="/img/docs/scaling.png" alt="Scaling" /></div>
  <div class="provider-card-action"><a href="./scaling">Documentation</a></div>
</div>
</div>
