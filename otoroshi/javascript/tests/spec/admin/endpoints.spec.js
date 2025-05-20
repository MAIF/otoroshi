const { test, expect } = require('@playwright/test')

let context

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/playwright/.auth/admin.json' })
})

test.afterAll(async () => {
    await context.close()
})

const route = {
    "_loc": {
        "tenant": "default",
        "teams": [
            "default"
        ]
    },
    "id": "route_c1a2868ca-0b26-4028-acae-420c1da8cb02",
    "name": "Verifier route",
    "description": "A new route",
    "tags": [],
    "metadata": {
        "created_at": "2025-05-20T14:44:49.748+02:00",
        "updated_at": "2025-05-20T14:44:50.501+02:00"
    },
    "enabled": true,
    "debug_flow": false,
    "export_reporting": false,
    "capture": false,
    "groups": [
        "default"
    ],
    "bound_listeners": [],
    "frontend": {
        "domains": [
            "verifier.oto.tools"
        ],
        "strip_path": true,
        "exact": false,
        "headers": {},
        "cookies": {},
        "query": {},
        "methods": []
    },
    "backend": {
        "targets": [
            {
                "id": "target_1",
                "hostname": "request.otoroshi.io",
                "port": 443,
                "tls": true,
                "weight": 1,
                "backup": false,
                "predicate": {
                    "type": "AlwaysMatch"
                },
                "protocol": "HTTP/1.1",
                "ip_address": null,
                "tls_config": {
                    "certs": [],
                    "trusted_certs": [],
                    "enabled": false,
                    "loose": false,
                    "trust_all": false
                }
            }
        ],
        "root": "/",
        "rewrite": false,
        "load_balancing": {
            "type": "RoundRobin"
        },
        "client": {
            "retries": 1,
            "max_errors": 20,
            "retry_initial_delay": 50,
            "backoff_factor": 2,
            "call_timeout": 30000,
            "call_and_stream_timeout": 120000,
            "connection_timeout": 10000,
            "idle_timeout": 60000,
            "global_timeout": 30000,
            "sample_interval": 2000,
            "proxy": {},
            "custom_timeouts": [],
            "cache_connection_settings": {
                "enabled": false,
                "queue_size": 2048
            }
        },
        "health_check": {
            "enabled": false,
            "url": "",
            "timeout": 5000,
            "healthyStatuses": [],
            "unhealthyStatuses": []
        }
    },
    "backend_ref": null,
    "plugins": [
        {
            "enabled": true,
            "debug": false,
            "plugin": "cp:otoroshi.next.plugins.OverrideHost",
            "include": [],
            "exclude": [],
            "config": {},
            "bound_listeners": [],
            "plugin_index": {
                "transform_request": 0
            },
            "nodeId": "cp:otoroshi.next.plugins.OverrideHost"
        },
        {
            "enabled": true,
            "debug": false,
            "plugin": "cp:otoroshi.next.plugins.JwtVerification",
            "include": [],
            "exclude": [],
            "config": {
                "verifiers": [
                    "jwt_verifier_dev_d8408081-57cc-4fca-a4b3-5c9be2d7fcfb"
                ]
            },
            "bound_listeners": [],
            "plugin_index": {
                "validate_access": 0,
                "transform_request": 1
            },
            "nodeId": "cp:otoroshi.next.plugins.JwtVerification"
        }
    ],
    "kind": "proxy.otoroshi.io/Route"
}

const verifier = {
    "_loc": {
        "tenant": "default",
        "teams": [
            "default"
        ]
    },
    "type": "global",
    "id": "jwt_verifier_dev_d8408081-57cc-4fca-a4b3-5c9be2d7fcfb",
    "name": "VERIFIER-ID",
    "desc": "New jwt verifier",
    "strict": true,
    "source": {
        "type": "InHeader",
        "name": "Authorization",
        "remove": "Bearer "
    },
    "algoSettings": {
        "type": "HSAlgoSettings",
        "size": 512,
        "secret": "secret",
        "base64": false
    },
    "strategy": {
        "type": "Transform",
        "verificationSettings": {
            "fields": {},
            "arrayFields": {}
        },
        "transformSettings": {
            "location": {
                "type": "InHeader",
                "name": "Authorization",
                "remove": ""
            },
            "mappingSettings": {
                "map": {
                    "foo": "bar"
                },
                "values": {
                    "newValue": "foobar",
                    "get-value-from-token": "${req.headers.unknown || token.first}",
                    "get-value-from-header": "${token.first || req.headers.foo}",
                    "get-default-value": "${req.headers.unknown || token.unknown :: not-found}"
                },
                "remove": []
            }
        },
        "algoSettings": {
            "type": "HSAlgoSettings",
            "size": 512,
            "secret": "secret",
            "base64": false
        }
    },
    "metadata": {},
    "tags": [],
    "kind": "security.otoroshi.io/JwtVerifier"
}

test('Should be able to get public keys in keys field', async () => {
    const cookies = await context.cookies();

    fetch('http://otoroshi.oto.tools:9999/.well-known/jwks.json', {
        cookies
    })
        .then(r => r.json())
        .then(rawKeys => expect(rawKeys.keys).toBeDefined())
})

test('Should be able to create a JWT verifier with || and ::', async () => {
    await fetch('http://otoroshi-api.oto.tools:9999/apis/security.otoroshi.io/v1/jwt-verifiers', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Otoroshi-Client-Id': 'admin-api-apikey-id',
            'Otoroshi-Client-Secret': 'admin-api-apikey-secret'
        },
        body: JSON.stringify(verifier)
    })

    await fetch('http://otoroshi-api.oto.tools:9999/apis/proxy.otoroshi.io/v1/routes', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Otoroshi-Client-Id': 'admin-api-apikey-id',
            'Otoroshi-Client-Secret': 'admin-api-apikey-secret'
        },
        body: JSON.stringify(route)
    })

    const cookies = await context.cookies();

    await fetch('http://verifier.oto.tools:9999', {
        cookies,
        headers: {
            Accept: 'application/json',
            foo: 'in-foo-header',
            'Authorization': 'Bearer eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJmaXJzdCI6ImZpcnN0LWZyb20tdG9rZW4iLCJ0d28iOiJ0d28tZnJvbS10b2tlbiIsImlhdCI6MTUxNjIzOTAyMiwiZm9vIjoiZm9vLWZyb20tdG9rZW4ifQ.4jMP321LPwIc2wzFfwTo5k0RgZ1wlSPOeZOdHlIzxnjlgPIhNjrL_TElLM_Y673v2NhIVq1fMJap3XXl_iNSzQ'
            /*
            {
                "first": "first-from-token",
                "two": "two-from-token",
                "iat": 1516239022,
                "foo": "foo-from-token"
            } */
        }
    })
        .then(r => r.json())
        .then(response => {
            const token = response.headers.authorization
            const payload = JSON.parse(atob(token.split('.')[1]))

            expect(payload.newValue).toBe("foobar")
            expect(payload.bar).toBeTruthy()
            expect(payload.foo).toBeUndefined()

            expect(payload['get-value-from-token']).toBe("first-from-token")
            expect(payload['get-value-from-header']).toBe("first-from-token")
            expect(payload['get-default-value']).toBe("not-found")
        })

    await fetch('http://otoroshi-api.oto.tools:9999/apis/security.otoroshi.io/v1/jwt-verifiers/jwt_verifier_dev_d8408081-57cc-4fca-a4b3-5c9be2d7fcfb', {
        method: 'DELETE',
        headers: {
            'Otoroshi-Client-Id': 'admin-api-apikey-id',
            'Otoroshi-Client-Secret': 'admin-api-apikey-secret'
        }
    })


    await fetch('http://otoroshi-api.oto.tools:9999/apis/proxy.otoroshi.io/v1/routes/route_c1a2868ca-0b26-4028-acae-420c1da8cb02', {
        method: 'DELETE',
        headers: {
            'Otoroshi-Client-Id': 'admin-api-apikey-id',
            'Otoroshi-Client-Secret': 'admin-api-apikey-secret'
        }
    })
})