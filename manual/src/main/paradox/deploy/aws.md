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


## Prepare your deployment target
Actually, there are 2 options to build your target. 

Either you create a DockerFile from this [Docker image](../getotoroshi/fromdocker.md), build a zip, and do all the Otoroshi custom configuration using ENVs.

Or you download the @ref:[otoroshi.jar](../getotoroshi/frombinaries.md), do all the Otoroshi custom configuration using your own otoroshi.conf, and create a DockerFile that runs the jar using your otoroshi.conf. 

For the second option your DockerFile would look like this :

``` 
    FROM openjdk:8
    VOLUME /tmp
    EXPOSE 8080
    ADD otoroshi.jar app.jar
    ADD otoroshi.conf otoroshi.conf
    RUN sh -c 'touch /app.jar'
    ENV JAVA_OPTS=""
    ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Dconfig.file=/otoroshi.conf -jar /app.jar" ]
``` 
 
I'd recommend the second option.
       
Now Zip your target (Jar + Conf + DockerFile) and get ready for deployment. 