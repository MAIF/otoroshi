# Working with Eureka

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/built-in-plugins.html#otoroshi.next.plugins.EurekaServerSink">Eureka instance</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/built-in-plugins.
html#otoroshi.next.plugins.EurekaTarget">Internal Eureka server</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/built-in-plugins.html#otoroshi.next.plugins.ExternalEurekaTarget">External Eureka server</a>
</div>

Eureka is a library of Spring Cloud Netflix, which provides two parts to register and discover services.
Generally, the services are applications written with Spring but Eureka also provides a way to communicate in REST. The main goals of Eureka are to allow clients to find and communicate with each other without hard-coding the hostname and port.
All services are registered in an Eureka Server.

To work with Eureka, Otoroshi has three differents plugins:

* to expose its own Eureka Server instance
* to discover an existing Eureka Server instance
* to use Eureka application as an Otoroshi target and took advantage of all Otoroshi clients features (load-balancing, rate limiting, etc...)

Let's cut this tutorial in three parts. 

- Create an simple Spring application that we'll use as an Eureka Client
- Deploy an implementation of the Otoroshi Eureka Server (using the `Eureka Instance` plugin), register eureka clients and expose them using the `Internal Eureka Server` plugin
- Deploy an Netflix Eureka Server and use it in Otoroshi to discover apps using the `External Eureka Server` plugin.


In this tutorial: 

- [Create an Otoroshi route with the Internal Eureka Server plugin](#create-an-otoroshi-route-with-the-internal-eureka-server-plugin)
- [Create a simple Eureka Client and register it](#create-a-simple-eureka-client-and-register-it)
- [Connect to an external Eureka server](#connect-to-an-external-eureka-server)

### Download Otoroshi

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create an Otoroshi route with the Internal Eureka Server plugin

@@@ note
We'll supposed that you have an Otoroshi exposed on the 8080 port with the new Otoroshi engine enabled
@@@

Let's jump to the routes Otoroshi [view](http://otoroshi.oto.tools:8080/bo/dashboard/routes) and create a new route using the wizard button.

Enter the following values in for each step:

1. An Eureka Server instance
2. Choose the first choice : **BLANK ROUTE** and click on continue
3. As exposed domain, set `eureka-server.oto.tools/eureka`
4. As Target URL, set `http://foo.bar` (this value has no importance and will be skip by the Otoroshi Instance plugin)
5. Validate the creation

Once created, you can hide with the arrow on the right top of the screen the tester view (which is displayed by default after each route creation).
In our case, we want to add a new plugin, called Internal Eureka Instance on our feed.

Inside the designer view:

1. Search the `Eureka Instance` in the list of plugins.
2. Add it to the feed by clicking on it
3. Set an eviction timeout at 300 seconds (this configuration is used by Otoroshi to automatically check if an Eureka is up. Otherwise Otoroshi will evict the eureka client from the registry)

Well done you have set up an Eureka Server. To check the content of an Eureka Server, you can navigate to this [link]('http://otoroshi.oto.tools:8080/bo/dashboard/eureka-servers'). In all case, none instances or applications are registered, so the registry is currently empty.

### Create a simple Eureka Client and register it

*This tutorial has no vocation to teach you how to write an Spring application and it may exists a newer version of this Spring code.*


For this tutorial, we'll use the following code which initiates an Eureka Client and defines an Spring REST Controller with only one endpoint. This endpoint will return its own exposed port (this value will  be useful to check that the Otoroshi load balancing is right working between the multiples Eureka instances registered).


Let's fast create a Spring project using [Spring Initializer](https://start.spring.io/). You can use the previous link or directly click on the following link to get the form already filled with the needed dependencies.

````bash
https://start.spring.io/#!type=maven-project&language=java&platformVersion=2.7.3&packaging=jar&jvmVersion=17&groupId=otoroshi.io&artifactId=eureka-client&name=eureka-client&description=A%20simple%20eureka%20client&packageName=otoroshi.io.eureka-client&dependencies=cloud-eureka,web
````

Feel free to change the project metadata for your use case.

Once downloaded and uncompressed, let's ahead and start to delete the application.properties and create an application.yml (if you are more comfortable with an application.properties, keep it)

````yaml
eureka:
   client:
      fetch-registry: false # disable the discovery services mechanism for the client
      serviceUrl:
         defaultZone: http://eureka-server.oto.tools:8080/eureka

spring:
   application:
      name: foo_app

````


Now, let's define the simple REST controller to expose the client port.

Create a new file, called PortController.java, in the sources folder of your project with the following content.

````java
package otoroshi.io.eurekaclient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortController {

    @Autowired
    Environment environment;

    @GetMapping("/port")
    public String index() {
        return environment.getProperty("local.server.port");
    }
}
````
This controller is very simple, we just exposed one endpoint `/port` which returns the port as string. Our client is ready to running. 

Let's launch it with the following command:

````sh
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085
````

@@@note
The port is not required but it will be useful when we will deploy more than one instances in the rest of the tutorial
@@@


Once the command ran, you can navigate to the eureka server view in the Otoroshi UI. The dashboard should displays one registered app and instance.
It should also displays a timer for each application which represents the elapsed time since the last received heartbeat.

Let's define a new route to exposed our registered eureka client.

* Create a new route, named `Eureka client`, exposed on `http://eureka-client.oto.tools:8080` and targeting `http://foo.bar`
* Search and add the `Internal Eureka server` plugin 
* Edit the plugin and choose your eureka server and your app (in our case, `Eureka Server` and `FOO_APP` respectively)
* Save your route

Now try to call the new route.

````sh
curl 'http://eureka-client.oto.tools:8080/port'
````

If everything is working, you should get the port 8085 as the response.The setup is working as expected, but we can improve him by scaling our eureka client.

Open a new tab in your terminal and run the following command.

````sh
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
````

Just wait a few seconds and retry to call your new route.

````sh
curl 'http://eureka-client.oto.tools:8080/port'
$ 8082
curl 'http://eureka-client.oto.tools:8080/port'
$ 8085
curl 'http://eureka-client.oto.tools:8080/port'
$ 8085
curl 'http://eureka-client.oto.tools:8080/port'
$ 8082
````

The configuration is ready and the setup is working, Otoroshi use all instances of your app to dispatch clients on it.

### Connect to an external Eureka server

Otoroshi has the possibility to discover services by connecting to an Eureka Server.

Let's create a route with an Eureka application as Otoroshi target:

* Create a new blank API route
* Search and add the `External Eureka Server` plugin
* Set your eureka URL
* Click on `Fetch Services` button to discover the applications of the Eureka instance
* In the appeared selector, choose the application to target
* Once the frontend configured, save your route and try to call it.

Well done, you have exposed your Eureka application through the Otoroshi discovery services.





























