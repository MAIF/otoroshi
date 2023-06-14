# Anonymous reporting

The best way of supporting us in Otoroshi developement is to enable Anonymous reporting.

## Details

When this feature is active, Otoroshi perdiodically send anonymous information about its configuration.

This information helps us to know how Otoroshi is used, it's a precious hint to prioritise our roadmap.

Below is an example of what is send by Otoroshi. You can find more information about these fields either on @ref:[entities documentation](../entities/index.md) or [by reading the source code](https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/jobs/reporting.scala#L174-L458).

```json
{
  "@timestamp": 1679514537259,
  "timestamp_str": "2023-03-22T20:48:57.259+01:00",
  "@id": "4edb54171-8156-4947-b821-41d6c2bd1ba7",
  "otoroshi_cluster_id": "1148aee35-a487-47b0-b494-a2a44862c618",
  "otoroshi_version": "16.0.0-dev",
  "java_version": {
    "version": "11.0.16.1",
    "vendor": "Eclipse Adoptium"
  },
  "os": {
    "name": "Mac OS X",
    "version": "13.1",
    "arch": "x86_64"
  },
  "datastore": "file",
  "env": "dev",
  "features": {
    "snow_monkey": false,
    "clever_cloud": false,
    "kubernetes": false,
    "elastic_read": true,
    "lets_encrypt": false,
    "auto_certs": false,
    "wasm_manager": true,
    "backoffice_login": false
  },
  "stats": {
    "calls": 3823,
    "data_in": 480406,
    "data_out": 4698261,
    "rate": 0,
    "duration": 35.89899494949495,
    "overhead": 24.696984848484846,
    "data_in_rate": 0,
    "data_out_rate": 0,
    "concurrent_requests": 0
  },
  "engine": {
    "uses_new": true,
    "uses_new_full": false
  },
  "cluster": {
    "mode": "Leader",
    "all_nodes": 1,
    "alive_nodes": 1,
    "leaders_count": 1,
    "workers_count": 0,
    "nodes": [
      {
        "id": "node_15ac62ec3-3e0d-48c1-a8ea-15de97088e3c",
        "os": {
          "name": "Mac OS X",
          "version": "13.1",
          "arch": "x86_64"
        },
        "java_version": {
          "version": "11.0.16.1",
          "vendor": "Eclipse Adoptium"
        },
        "version": "16.0.0-dev",
        "type": "Leader",
        "cpu_usage": 10.992902320605205,
        "load_average": 44.38720703125,
        "heap_used": 527,
        "heap_size": 2048,
        "relay": true,
        "tunnels": 0
      }
    ]
  },
  "entities": {
    "scripts": {
      "count": 0,
      "by_kind": {}
    },
    "routes": {
      "count": 24,
      "plugins": {
        "min": 1,
        "max": 26,
        "avg": 4
      }
    },
    "router_routes": {
      "count": 27,
      "http_clients": {
        "ahc": 25,
        "akka": 2,
        "netty": 0,
        "akka_ws": 0
      },
      "plugins": {
        "min": 1,
        "max": 26,
        "avg": 4
      }
    },
    "route_compositions": {
      "count": 1,
      "plugins": {
        "min": 1,
        "max": 1,
        "avg": 1
      },
      "by_kind": {
        "global": 1
      }
    },
    "apikeys": {
      "count": 6,
      "by_kind": {
        "disabled": 0,
        "with_rotation": 0,
        "with_read_only": 0,
        "with_client_id_only": 0,
        "with_constrained_services": 0,
        "with_meta": 2,
        "with_tags": 1
      },
      "authorized_on": {
        "min": 1,
        "max": 4,
        "avg": 2
      }
    },
    "jwt_verifiers": {
      "count": 6,
      "by_strategy": {
        "pass_through": 6
      },
      "by_alg": {
        "HSAlgoSettings": 6
      }
    },
    "certificates": {
      "count": 9,
      "by_kind": {
        "auto_renew": 6,
        "exposed": 6,
        "client": 1,
        "keypair": 1
      }
    },
    "auth_modules": {
      "count": 8,
      "by_kind": {
        "basic": 7,
        "oauth2": 1
      }
    },
    "service_descriptors": {
      "count": 3,
      "plugins": {
        "old": 0,
        "new": 0
      },
      "by_kind": {
        "disabled": 1,
        "fault_injection": 0,
        "health_check": 1,
        "gzip": 0,
        "jwt": 0,
        "cors": 1,
        "auth": 0,
        "protocol": 0,
        "restrictions": 0
      }
    },
    "teams": {
      "count": 2
    },
    "tenants": {
      "count": 2
    },
    "service_groups": {
      "count": 2
    },
    "data_exporters": {
      "count": 10,
      "by_kind": {
        "elastic": 5,
        "file": 2,
        "metrics": 1,
        "console": 1,
        "s3": 1
      }
    },
    "otoroshi_admins": {
      "count": 5,
      "by_kind": {
        "simple": 2,
        "webauthn": 3
      }
    },
    "backoffice_sessions": {
      "count": 1,
      "by_kind": {
        "simple": 1
      }
    },
    "private_apps_sessions": {
      "count": 0,
      "by_kind": {}
    },
    "tcp_services": {
      "count": 0
    }
  },
  "plugins_usage": {
    "cp:otoroshi.next.plugins.AdditionalHeadersOut": 2,
    "cp:otoroshi.next.plugins.DisableHttp10": 2,
    "cp:otoroshi.next.plugins.OverrideHost": 27,
    "cp:otoroshi.next.plugins.TailscaleFetchCertificate": 1,
    "cp:otoroshi.next.plugins.OtoroshiInfos": 6,
    "cp:otoroshi.next.plugins.MissingHeadersOut": 2,
    "cp:otoroshi.next.plugins.Redirection": 2,
    "cp:otoroshi.next.plugins.OtoroshiChallenge": 5,
    "cp:otoroshi.next.plugins.BuildMode": 2,
    "cp:otoroshi.next.plugins.XForwardedHeaders": 2,
    "cp:otoroshi.next.plugins.NgLegacyAuthModuleCall": 2,
    "cp:otoroshi.next.plugins.Cors": 4,
    "cp:otoroshi.next.plugins.OtoroshiHeadersIn": 2,
    "cp:otoroshi.next.plugins.NgDefaultRequestBody": 1,
    "cp:otoroshi.next.plugins.NgHttpClientCache": 1,
    "cp:otoroshi.next.plugins.ReadOnlyCalls": 2,
    "cp:otoroshi.next.plugins.RemoveHeadersIn": 2,
    "cp:otoroshi.next.plugins.JwtVerificationOnly": 1,
    "cp:otoroshi.next.plugins.ApikeyCalls": 3,
    "cp:otoroshi.next.plugins.WasmAccessValidator": 3,
    "cp:otoroshi.next.plugins.WasmBackend": 3,
    "cp:otoroshi.next.plugins.IpAddressAllowedList": 2,
    "cp:otoroshi.next.plugins.AuthModule": 4,
    "cp:otoroshi.next.plugins.RemoveHeadersOut": 2,
    "cp:otoroshi.next.plugins.IpAddressBlockList": 2,
    "cp:otoroshi.next.proxy.ProxyEngine": 1,
    "cp:otoroshi.next.plugins.JwtVerification": 3,
    "cp:otoroshi.next.plugins.GzipResponseCompressor": 2,
    "cp:otoroshi.next.plugins.SendOtoroshiHeadersBack": 3,
    "cp:otoroshi.next.plugins.AdditionalHeadersIn": 4,
    "cp:otoroshi.next.plugins.SOAPAction": 1,
    "cp:otoroshi.next.plugins.NgLegacyApikeyCall": 6,
    "cp:otoroshi.next.plugins.ForceHttpsTraffic": 2,
    "cp:otoroshi.next.plugins.NgErrorRewriter": 1,
    "cp:otoroshi.next.plugins.MissingHeadersIn": 2,
    "cp:otoroshi.next.plugins.MaintenanceMode": 3,
    "cp:otoroshi.next.plugins.RoutingRestrictions": 2,
    "cp:otoroshi.next.plugins.HeadersValidation": 2
  }
}
```

## Toggling

Anonymous reporting can be toggled any time using :

- the UI (Features > Danger zone > Send anonymous reports)
- `otoroshi.anonymous-reporting.enabled` configuration
- `OTOROSHI_ANONYMOUS_REPORTING_ENABLED` env variable
