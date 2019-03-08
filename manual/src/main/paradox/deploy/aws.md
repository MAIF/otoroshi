# AWS 

Now you want to use Otoroshi on AWS. There are multiple options to deploy Otoroshi on AWS, 
for instance :

* You can deploy the [Docker image](../getotoroshi/fromdocker.md) on [Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html)
* You can create a basic [Amazon EC2](https://docs.aws.amazon.com/fr_fr/AWSEC2/latest/UserGuide/concepts.html), access it via SSH, then 
deploy the @ref:[otoroshi.jar](../firstrun/run.md#from-jar-file)     
* Or you can use [AWS Elastic Beanstalk](https://aws.amazon.com/fr/elasticbeanstalk)

In this section we are going to cover how to deploy Otoroshi on [AWS Elastic Beanstalk](https://aws.amazon.com/fr/elasticbeanstalk). 

## AWS Elastic Beanstalk Overview
Unlike Clever Cloud, to deploy an application on AWS Elastic Beanstalk, you don't link your app to your VCS repository, push your code and expect it to be built and run.

AWS Elastic Beanstalk does only the run part. So you have to handle your own build pipeline, upload a Zip file containing your runnable, then AWS Elastic Beanstalk will take it from there.  
  
Eg: for apps running on the JVM (Scala/Java/Kotlin) a Zip with the jar inside would suffice, for apps running in a Docker container, a Zip with the DockerFile would be enough.   