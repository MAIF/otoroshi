# One header to rule them all

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.AdditionalHeadersIn">Additional Headers In</a>
</div>

Have you ever managed user permissions across 15 teams... with 15 different formats? This is a common problem in large organizations: you have multiple APIs handling authorization, and 15 client services calling them. The catch? Each team has implemented it their own way:

- The mobile team sends `X-Roles`
- The web portal team sends `User-Roles`
- The partners team sends `ROLES`
- Some don't send anything at all

**The result:** 
Your backend has to deal with all of this. You either:

- Code 4 different ways to read the roles
- Ask 15 teams to change their code (spoiler: nobody will do it)


## Before you start

@@include[getting-started.md](../includes/getting-started.md) { #getting-started }

## The Solution: Normalize Headers at the Gateway

With Otoroshi and its Expression Language, you can handle this at the gateway level by creating a single, unified header for each request that consolidates all possibilities.

### Step 1: Create a route with header normalization

Create a new route using Otoroshi's Admin API. This route will normalize incoming role headers into a single `roles` header:

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/routes \
  -H "Content-type: application/json" \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d @- <<'EOF'
{
  "id": "roles-api",
  "name": "roles-api",
  "frontend": {
    "domains": ["roles-api.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "request.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [,
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.AdditionalHeadersIn",
      "enabled": true,
      "config": {
        "headers": {
            "roles": "${req.headers.x-roles || req.headers.user-roles || req.headers.roles :: anonymous}"
        }
      }
    }
  ]
}
EOF
```

The backend `request.otoroshi.io` is a mirror service that echoes back all headers and body it receives, perfect for testing.

### Step 2: Test the normalization

Test with a custom header:

```sh
curl http://roles-api.oto.tools:8080 -H 'x-roles: admin,editor'
```

@@@ div { .centered-img }
<img src="../imgs/quick-tutorials/step-2.png" style="width: 700px" />
You'll see the response includes a `roles` header with the value `admin,editor`.
@@@


Test without any role header:

```sh
curl http://roles-api.oto.tools:8080
```

The Expression Language defaults to `anonymous` when no role headers are present

@@@ note
You may have noticed there’s one more plugin in the list. Otoroshi doesn’t automatically rewrite the Host header when proxying requests. For backends like request.otoroshi.io (or backends hosted on a special host), the Host header must match the backend’s hostname. This plugin ensures the correct Host is set. On localhost, it isn’t needed because local servers accept any Host.
@@@

### Step 3: Clean up original headers

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.AdditionalHeadersIn">Additional Headers In</a>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.RemoveHeadersIn">Remove Headers In</a>
</div>

By default, Otoroshi forwards all incoming headers to the backend. To ensure your backend only receives the normalized `roles` header, remove the original headers:

```sh
curl -X PUT http://otoroshi-api.oto.tools:8080/api/routes/roles-api \
  -H "Content-type: application/json" \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d @- <<'EOF'
{
  "id": "roles-api",
  "name": "roles-api",
  "frontend": {
    "domains": ["roles-api.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "request.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.RemoveHeadersIn",
      "enabled": true,
      "config": {
        "names": ["x-roles", "user-roles"]
      }
    },
    {
      "plugin": "cp:otoroshi.next.plugins.AdditionalHeadersIn",
      "enabled": true,
      "config": {
        "headers": {
            "roles": "${req.headers.x-roles || req.headers.user-roles || req.headers.roles :: anonymous}"
        }
      }
    }
  ]
}
EOF
```

Test again to verify the cleanup:

```sh
curl http://roles-api.oto.tools:8080 -H 'x-roles: admin'
```

@@@ div { .centered-img }
<img src="../imgs/quick-tutorials/step-4.png" style="width: 700px" />

Your backend now receives only the normalized `roles` header. The original headers (`x-roles`, `user-roles`, `roles`) are removed, ensuring consistent header handling across all teams without modifying any client code.
@@@

## Summary

**What we accomplished:**

- Created a route that normalizes multiple role header formats into a single `roles` header
- Used Otoroshi's Expression Language to provide a default value (`anonymous`) when no role headers are present
- Cleaned up incoming headers to ensure backends only receive the normalized header
- Eliminated the need for backend code changes or client-side coordination across teams

**Plugins used:**

<div style="display: flex; flex-direction: column; gap: .5rem; margin-top: 1rem;">
<div style="display: flex; align-items: center; gap: .5rem;">
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.AdditionalHeadersIn">Additional Headers In</a>
<span>Creates the normalized <code>roles</code> header using Expression Language</span>
</div>
<div style="display: flex; align-items: center; gap: .5rem;">
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.RemoveHeadersIn">Remove Headers In</a>
<span>Removes original header variations to prevent confusion</span>
</div>
<div style="display: flex; align-items: center; gap: .5rem;">
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.OverrideHostHeader">Override Host Header</a>
<span>Ensures proper Host header for backend routing</span>
</div>
</div>
