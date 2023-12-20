# Otoroshi plugins system

Otoroshi includes several extension points that allows you to create your own plugins and support stuff not supported by default.

## Kind of available plugins

@@@ div { .plugin-kind }
###Request Sink

Used when no services are matched in Otoroshi. Can reply with any content.
@@@

@@@ div { .plugin-kind }
###Pre routing

Used to extract values (like custom apikeys) and provide them to other plugins or Otoroshi engine
@@@

@@@ div { .plugin-kind }
###Access Validator

Used to validate if a request can pass or not based on whatever you want
@@@

@@@ div { .plugin-kind }
###Request Transformer

Used to transform request, responses and their body. Can be used to return arbitrary content
@@@

@@@ div { .plugin-kind }
###Event listener

Any plugin type can listen to Otoroshi internal events and react to thems
@@@

@@@ div { .plugin-kind }
###Job

Tasks that can run automatically once, on be scheduled with a cron expression or every defined interval
@@@

@@@ div { .plugin-kind }
###Exporter

Used to export events and Otoroshi alerts to an external source
@@@

@@@ div { .plugin-kind }
###Request handler

Used to handle traffic without passing through Otoroshi routing and apply own rules
@@@

@@@ div { .plugin-kind }
###Nano app

Used to write an api directly in Otoroshi in Scala language
@@@