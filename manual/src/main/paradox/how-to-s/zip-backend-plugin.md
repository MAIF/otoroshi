# Quickly expose a website and static files 

@@include[badge.md](../includes/badge.md) { #badge }

## Tutorial

1. [Before your start](#before-your-start)
2. [Create the route](#create-the-route)

After completing these steps, your files will be statically exposed.

## Before your start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

## Create the route

Let's start by creating an archive composed of html and css files.

The contents of your `index.html` file should be likes this:

```html
<!DOCTYPE html>
<html>
<head>
  <title>Wasmo plugin</title>
  <link rel="stylesheet" type="text/css" href="/index.css"/>
</head>
<body>
  <h1>Hello from Wasmo</h1>
</body>
</html>
```

The contents of your `index.css` file should be likes this:

```css
body {
  background: #f9b000;
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100dvh;
}

h1 {
  font-size: 3rem;
  color: #fff;
}
```

Once created, you can create the archive of both.

```sh
zip bundle.zip index.html index.css
```

Let's create the route using the admin API.

``` sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "name": "demootoroshi",
  "frontend": {
    "domains": ["demo-otoroshi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
      "enabled": true,
      "debug": false,
      "plugin": "cp:otoroshi.next.plugins.ZipFileBackend",
      "include": [],
      "exclude": [],
      "config": {
        "url": "file:///<path-to-the-zip-file>",
        "headers": {},
        "dir": "./zips",
        "prefix": null,
        "ttl": 3600000
      }
    }
  ]
}
EOF
```