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

## Create an Otoroshi instance on AWS Elastic Beanstalk
First, go to [AWS Elastic Beanstalk Console](https://eu-west-3.console.aws.amazon.com/elasticbeanstalk/home?region=eu-west-3#/welcome), don't forget to sign in and make sure that you are in the good region (eg : eu-west-3 for Paris).

Hit **Get started** 

@@@ div { .centered-img }
<img src="../img/deploy-elb-0.png" />
@@@

Specify the **Application name** of your application, Otoroshi for example.

@@@ div { .centered-img }
<img src="../img/deploy-elb-1.png" />
@@@
 
Choose the **Platform** of the application you want to create, in your case use Docker.

For **Application code** choose **Upload your code** then hit **Upload**.

@@@ div { .centered-img }
<img src="../img/deploy-elb-2.png" />
@@@

Browse the zip created in the [previous section](#prepare-your-deployment-target) from your machine. 

As you can see in the image above, you can also choose an S3 location, you can imagine that at the end of your build pipeline you upload your Zip to S3, and then get it from there (I wouldn't recommend that though).
  
When the upload is done, hit **Configure more options**.
   
@@@ div { .centered-img }
<img src="../img/deploy-elb-3.png" />
@@@ 
 
Right now an AWS Elastic Beanstalk application has been created, and by default an environment named Otoroshi-env is being created as well.

AWS Elastic Beanstalk can manage multiple environments of the same application, for instance environments can be (prod, preprod, expriments...).  

Otoroshi is a bit particular, it doesn't make much sense to have multiple environments, since Otoroshi will handle all the requests from/to downstream services regardless of the environment.        
 
As you see in the image above, we are now configuring the Otoroshi-env, the one and only environment of Otoroshi.
  
For **Configuration presets**, choose custom configuration, now you have a load balancer for your environment with the capacity of at least one instance and at most four.
I'd recommend at least 2 instances, to change that, on the **Capacity** card hit **Modify**.         

@@@ div { .centered-img }
<img src="../img/deploy-elb-4.png" />
@@@

Change the **Instances** to min 2, max 4 then hit **Save**. For the **Scaling triggers**, I'd keep the default values, but know that you can edit the capacity config any time you want, it only costs a redeploy, which will be done automatically by the way.
       
Instances size is by default t2.micro, which is a bit small for running Otoroshi, I'd recommend a t2.medium.     
On the **Instances** card hit **Modify**.

@@@ div { .centered-img }
<img src="../img/deploy-elb-5.png" />
@@@

For **Instance type** choose t2.medium, then hit **Save**, no need to change the volume size, unless you have a lot of http call faults, which means a lot more logs, in that case the default volume size may not be enough.

The default environment created for Otoroshi, for instance Otoroshi-env, is a web server environment which fits in your case, but the thing is that on AWS Elastic Beanstalk by default a web server environment for a docker-based application, runs behind an Nginx proxy.
We have to remove that proxy. So on the **Software** card hit **Modify**.
        
@@@ div { .centered-img }
<img src="../img/deploy-elb-6.png" />
@@@        
    
For **Proxy server** choose None then hit **Save**.

Also note that you can set Envs for Otoroshi in same page (see image below). 

@@@ div { .centered-img }
<img src="../img/deploy-elb-7.png" />
@@@  

To finalise the creation process, hit **Create app** on the bottom right.

The Otoroshi app is now created, and it's running which is cool, but we still don't have neither a **datastore** nor **https**.