# Admin REST API

Otoroshi provides a fully featured REST admin API to perform almost every operation possible in the Otoroshi dashboard. The Otoroshi dashbaord is just a regular consumer of the admin API.

Using the admin API, you can do whatever you want and enhance your Otoroshi instances with a lot of features that will feet your needs.

Otoroshi also provides some connectors that uses the Otoroshi admin API to automate Otorshi's instances when used with stuff like containers orchestrators. For more informations about that, just go to the @ref:[third party integrations chapter](./integrations/index.md)

## Swagger descriptor

The Otoroshi admin API is described using OpenAPI format and is available at :

https://maif.github.io/otoroshi/manual/code/swagger.json

Every Otoroshi instance provides its own embedded OpenAPI descriptor at :

http://otoroshi.foo.bar:8080/api/swagger.json

## Swagger documentation

You can read the OpenAPI descriptor in a more human friendly fashion using `Swagger UI`. The swagger UI documentation of the Otoroshi admin API is available at :

https://maif.github.io/otoroshi/swagger-ui/index.html

Every Otoroshi instance provides its own embedded OpenAPI descriptor at :

http://otoroshi.foo.bar:8080/api/swagger/ui

You can also read the swagger UI documentation of the Otoroshi admin API below :

@@@ div { .swagger-frame }


@@@
