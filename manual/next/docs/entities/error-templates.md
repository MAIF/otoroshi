---
title: Error Templates
sidebar_position: 8
---
# Error Templates

When Otoroshi sits in front of your APIs, it becomes the face your consumers see -- including when things go wrong. By default, Otoroshi returns its own generic error pages for situations like an unauthorized request, a missing route, a backend that is down, or a service placed in maintenance mode. These generic pages get the job done, but they look nothing like the rest of your application, which can be jarring for end users and inconsistent with your brand.

Error Templates solve this problem by letting you replace Otoroshi's built-in error pages with your own custom HTML. Each error template is associated with a specific route or service, so different APIs can present different error experiences. The templates cover every category of error that Otoroshi may return on behalf of your backend:

- **4xx client errors** -- unauthorized access (401), forbidden requests (403), routes not found (404), and other client-side issues.
- **5xx server errors** -- bad gateway (502), service unavailable (503), proxy errors, and other backend failures.
- **Maintenance mode** -- a dedicated page displayed when you explicitly put a service into maintenance.
- **Build mode** -- a dedicated page displayed when a service is flagged as under construction.

Templates are standard HTML that can include placeholder variables such as `${status}`, `${message}`, `${cause}`, and `${errorId}`. At runtime, Otoroshi replaces these placeholders with the actual error details before sending the response to the client. This means you can design a fully branded error page once, and Otoroshi will fill in the specifics for each error occurrence automatically.

For API consumers that expect JSON rather than HTML (based on the `Accept` header), Otoroshi returns a structured JSON error response instead of rendering the HTML template, ensuring that both browser-based users and programmatic clients receive appropriate error formats.

Beyond the four broad template categories, you can also define cause-specific templates (via `genericTemplates`) and custom messages (via `messages`) for fine-grained control -- for example, showing a different page for "service not found" versus "service not secured", or overriding the default error message for a specific HTTP status code.

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

## Admin API

```
GET    /api/error-templates           # List all error templates
POST   /api/error-templates           # Create an error template
GET    /api/error-templates/:id       # Get an error template
PUT    /api/error-templates/:id       # Update an error template
DELETE /api/error-templates/:id       # Delete an error template
PATCH  /api/error-templates/:id       # Partially update an error template
```
