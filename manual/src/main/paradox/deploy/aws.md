# AWS 

Now you want to use Otoroshi on AWS. There are multiple options to deploy Otoroshi on AWS, 
for instance :

* You can deploy the [Docker image](../getotoroshi/fromdocker.md) on [Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/docker-basics.html)
* You can create a basic [Amazon EC2](https://docs.aws.amazon.com/fr_fr/AWSEC2/latest/UserGuide/concepts.html), access it via SSH, then 
deploy the @ref:[otoroshi.jar](../firstrun/run.md#from-jar-file)     
* Or you can use [AWS Elastic Beanstalk](https://aws.amazon.com/fr/elasticbeanstalk)

In this section we are going to cover how to deploy Otoroshi on [AWS Elastic Beanstalk](https://aws.amazon.com/fr/elasticbeanstalk). 