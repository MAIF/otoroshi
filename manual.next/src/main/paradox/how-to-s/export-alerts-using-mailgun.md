# Send alerts using mailgun

All Otoroshi alerts can be send on different channels.
One of the ways is to send a group of specific alerts via emails.

To enable this behaviour, let's start by create an exporter of events.

In this tutorial, we will admit that you already have a mailgun account with an API key and a domain.

## Create an Mailgun exporter

Let's create an exporter. The exporter will export by default all events generate by Otoroshi.

1. Go ahead, and navigate to http://otoroshi.oto.tools:8080
2. Click on the cog icon on the top right
3. Then `Exporters` button
4. And add a new configuration when clicking on the `Add item` button
5. Select the `mailer` in the `type` selector field
6. Jump to `Exporter config` and select the `Mailgun` option
7. Set the following values:
* `EU` : false/true depending on your mailgun configuratin
* `Mailgun api key` : your-mailgun-api-key
* `Mailgun domain` : your-mailgun-domain
* `Email addresses` : list of the recipient adresses

With this configuration, all Otoroshi events will be send to your listed addresses (we don't recommended to do that).

To filter events on `Alerts` type, we need to add the following configuration inside the `Filtering and projection` section.

```json
{
    "include": [
        { "@type": "AlertEvent" }
    ],
    "exclude": []
}
``` 

Save at the bottom page and enable the exporter (on the top of the page or in list of exporters).

The second field can be useful in the case you want filter the fields contains in each alert sended.

The `Projection` field is a json when you can list the fields to keep for each alerts.

```json
{
 "@type": true,
 "@timestamp": true,
 "@id": true
}
```
With this example, only `@type`, `@timestamp` and `@id` will be send to your recipient adresses.