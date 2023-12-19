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
  <link rel="stylesheet" type="text/css" href="index.css"/>
</head>
<body>
  <h1>Hello from Wasmo</h1>
</body>
</html>
```

The contents of your `index.css` file should be likes this:

```css
.body {
  background: #f9b000;
}
```

Once created, you can create the archive of both.

```bash
zip bundle.zip index.html index.css
```