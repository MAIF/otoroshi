# Error Templates

Error Templates allow you to customize the error pages returned by Otoroshi when something goes wrong (4xx errors, 5xx errors, maintenance mode, build mode, etc.). Each error template is associated with a route or service and provides HTML templates with placeholder variables that are replaced at runtime.

## UI page

You can find all error templates [here](http://otoroshi.oto.tools:8080/bo/dashboard/error-templates)

## Properties

* `serviceId`: the identifier of the route or service this error template is attached to (also used as the entity id)
* `name`: display name of the error template
* `description`: description of the error template
* `tags`: list of tags associated to the error template
* `metadata`: list of metadata associated to the error template
* `template40x`: the HTML template used for 4xx client error responses
* `template50x`: the HTML template used for 5xx server error responses
* `templateBuild`: the HTML template displayed when the service is under construction
* `templateMaintenance`: the HTML template displayed when the service is in maintenance mode
* `genericTemplates`: a map of cause-specific templates. The key is a cause identifier (e.g., `errors.service.not.found`) and supports regex patterns for matching. These templates take priority over the generic `template40x`/`template50x` templates
* `messages`: a map of custom messages. Keys can be `message-{status}` (e.g., `message-404`) for status-specific messages or cause identifiers (e.g., `errors.service.not.found`) for cause-specific messages

## Template variables

All HTML templates support the following placeholder variables that are replaced at rendering time:

* `${message}`: the error message (can be overridden by a `messages` entry matching the status code)
* `${cause}`: the cause of the error (can be overridden by a `messages` entry matching the cause id)
* `${otoroshiMessage}`: the raw error message from Otoroshi
* `${errorId}`: a unique identifier for this error occurrence (useful for support/debugging)
* `${status}`: the HTTP status code

## Template selection logic

When Otoroshi renders an error page, it selects the template in the following order:

1. A matching entry in `genericTemplates` (exact match on cause id, then regex match)
2. `templateMaintenance` if the cause is `errors.service.in.maintenance`
3. `templateBuild` if the cause is `errors.service.under.construction`
4. `template40x` for 4xx status codes
5. `template50x` for 5xx status codes (and as a fallback)

## JSON response

When the client requests a JSON response, Otoroshi returns a JSON object with the following fields instead of rendering an HTML template:

```json
{
  "otoroshi-error-id": "unique-error-id",
  "otoroshi-error": "the error message",
  "otoroshi-cause": "the error cause",
  "otoroshi-raw-error": "the raw otoroshi message"
}
```

## API

The admin API is available at:

```
http://otoroshi-api.oto.tools:8080/api/error-templates
```
