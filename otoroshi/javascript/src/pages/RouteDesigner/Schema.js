import React from 'react'
import { LOAD_BALANCING } from './Constants';
import { type, format, constraints } from '@maif/react-forms';

export const COMPONENTS = [
    {
        id: 'Frontend',
        icon: 'eye',
        default: true,
        field: 'frontend',
        props: {
            schema: {
                domains: {
                    type: type.string,
                    array: true,
                    label: 'Domains',
                    createOption: true,
                    isMulti: true
                },
                strip_path: {
                    type: type.bool,
                    label: 'Strip path',
                    help: 'When matching, strip the matching prefix from the upstream request URL. Defaults to true'
                },
                exact: {
                    type: type.bool,
                    label: 'Exact matching'
                },
                headers: {
                    type: type.object,
                    label: 'Metadata',
                    defaultValue: {}
                },
                methods: {
                    type: type.string,
                    label: 'Methods',
                    format: format.select,
                    possibleValues: [
                        'GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT', 'OPTIONS', 'TRACE', 'PATCH'
                    ],
                    isMulti: true
                }
            },
            flow: [
                "domains",
                "strip_path",
                "exact",
                "headers",
                "methods"
            ]
        }
    },
    {
        group: 'Headers',
        icon: 'headphones',
        elements: [
            {
                id: 'Override host header',
                switch: true,
                // default: true,
                description: 'When enabled, Otoroshi will automatically set the Host header to corresponding target host',
                property: 'overrideHost'
            },
            {
                id: 'Send x-forwarded headers',
                description: 'When enabled, Otoroshi will send X-Forwarded-* headers to target',
                switch: true,
                property: 'xForwardedHeaders'
            },
            {
                id: 'Additional headers',
                props: {
                    schema: {
                        additionalHeaders: {
                            type: type.object,
                            format: 'array',
                            label: 'Additional Headers In',
                            help: 'Specify headers that will be added to each client request (from Otoroshi to target). Useful to add authentication.',
                        }
                    },
                    flow: [
                        'additionalHeaders'
                    ]
                }
            },
            {
                id: 'Add missing headers',
                props: {
                    schema: {
                        missingOnlyHeadersIn: {
                            type: type.object,
                            format: 'array',
                            label: 'Missing only Headers In',
                            help: 'Specify headers that will be added to each client request (from Otoroshi to target) if not in the original request.',
                        }
                    },
                    flow: [
                        'missingOnlyHeadersIn'
                    ]
                }
            },
            {
                id: 'Remove incoming headers',
                props: {
                    schema: {
                        removeHeadersIn: {
                            type: type.string,
                            format: 'array',
                            label: 'Remove incoming headers',
                            help: 'Remove headers in the client request (from client to Otoroshi).'
                        }
                    },
                    flow: [
                        'removeHeadersIn'
                    ]
                }
            },
            {
                id: 'Validate headers',
                props: {
                    schema: {
                        headersVerification: {
                            type: type.object,
                            format: 'array',
                            label: 'Headers verification',
                            help: 'Verify that some headers has a specific value (post routing)'
                        }
                    },
                    flow: [
                        'headersVerification'
                    ]
                }
            },
            {
                id: 'Route on headers',
                props: {
                    schema: {
                        matchingHeaders: {
                            type: type.object,
                            format: 'array',
                            label: 'Matching Headers',
                            help: 'Specify headers that MUST be present on client request to route it (pre routing). Useful to implement versioning.'
                        }
                    },
                    flow: [
                        'matchingHeaders'
                    ]
                }
            },
            {
                id: 'Send otoroshi headers',
                onOutputStream: true,
                description: 'When enabled, Otoroshi will send headers to consumer like request id, client latency, overhead, etc ...',
                switch: true,
                property: 'sendOtoroshiHeadersBack'
            },
            {
                id: 'Additional headers',
                onOutputStream: true,
                props: {
                    schema: {
                        'additionalHeadersOut': {
                            type: type.object,
                            format: 'array',
                            label: 'Additional Headers Out',
                            help: 'Specify headers that will be added to each client response (from Otoroshi to client).'
                        }
                    },
                    flow: [
                        'additionalHeadersOut'
                    ]
                }
            },
            {
                id: 'Add missing headers',
                onOutputStream: true,
                props: {
                    schema: {
                        'missingOnlyHeadersOut': {
                            type: type.object,
                            format: 'array',
                            label: 'Missing only Headers Out',
                            help: 'Specify headers that will be added to each client response (from Otoroshi to client) if not in the original response.'
                        }
                    },
                    flow: [
                        'missingOnlyHeadersOut'
                    ]
                }
            },
            {
                id: 'Remove headers',
                onOutputStream: true,
                props: {
                    schema: {
                        'removeHeadersOut': {
                            type: type.string,
                            format: 'array',
                            label: 'Remove outgoing headers',
                            placeholder: 'Remove headers in the client response (from Otoroshi to client).'
                        }
                    },
                    flow: [
                        'removeHeadersOut'
                    ]
                }
            }
        ]
    },
    {
        group: 'security',
        icon: 'lock',
        elements: [
            {
                id: 'Force HTTPS',
                description: 'Will force redirection to https:// if not present',
                switch: true,
                property: 'forceHttps'
            },
            {
                id: 'Web authentication',
                props: {
                    schema: {
                        // FORM
                    },
                    flow: [
                        'securityExcludedPatterns', // STRING LIST,
                        'privateApp', // BOOL
                        'authConfigRef', // STRING REF
                        'strictlyPrivate' // BOOL
                    ]
                }
            },
            {
                id: 'JWT verification',
                props: {
                    schema: {
                        // FORM
                    },
                    flow: [
                        'jwtVerifier'
                    ]
                }
            },
            {
                id: 'Otoroshi exchange protocol',
                props: {
                    schema: {
                        // TODO - set enabled to true and hide it
                        sendStateChallenge: {
                            type: type.bool,
                            label: "Send challenge",
                            help: "When disbaled, Otoroshi will not check if target service respond with sent random value.",
                        },
                        sendInfoToken: {
                            type: type.bool,
                            label: "Send info. token",
                            help: 'When enabled, Otoroshi add an additional header containing current call informations'
                        },
                        secComVersion: {
                            type: type.number,
                            format: format.select,
                            label: "Challenge token version",
                            help: "Version the otoroshi exchange protocol challenge. This option will be set to V2 in a near future.",
                            options: [
                                { label: 'V1 - simple values exchange', value: 1 },
                                { label: 'V2 - signed JWT tokens exchange', value: 2 },
                            ]
                        },
                        secComInfoTokenVersion: {
                            type: type.string,
                            format: format.select,
                            label: "Info. token version",
                            help: 'Version the otoroshi exchange protocol info token. This option will be set to Latest in a near future.',
                            options: [
                                {
                                    label: 'Legacy - legacy version of the info token with flattened values',
                                    value: 'Legacy',
                                },
                                { label: 'Latest - latest version of the info token json values', value: 'Latest' },
                            ]
                        },
                        secComTtl: {
                            type: type.number,
                            label: "Tokens TTL",
                            help: "The number of seconds for tokens (state and info) lifes",
                            placeholder: "10",
                            suffix: "seconds",

                        },
                        'secComHeaders.stateRequestName': {
                            type: type.string,
                            label: "State token header name",
                            help: "The name of the header containing the state token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.state)",
                            placeholder: "Otoroshi-State"
                        },
                        'secComHeaders.stateResponseName': {
                            type: type.string,
                            label: "State token response header name",
                            help: "The name of the header containing the state response token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.stateresp)",
                            placeholder: "Otoroshi-State-Resp"
                        },
                        'secComHeaders.claimRequestName': {
                            type: type.string,
                            label: "Info token header name",
                            help: "The name of the header containing the info token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.claim)",
                            placeholder: "Otoroshi-Claim",
                        },
                        'secComExcludedPatterns': {
                            type: type.string,
                            format: 'array',
                            label: "Excluded patterns",
                            placeholder: "URI pattern",
                            suffix: "regex",
                            help: "URI patterns excluded from the otoroshi exchange protocol"
                        },
                        secComUseSameAlgo: {
                            type: type.bool,
                            label: "Use same algo.",
                            help: "When enabled, all JWT token in this section will use the same signing algorithm"
                        },
                        //             secComSettings: {
                        //                 // TODO  
                        //                 visible: {
                        //                     ref: 'secComUseSameAlgo'
                        //                 },
                        //                 /*
                        //                 <AlgoSettings
                        //     algo={this.state.service.secComSettings}
                        //     path="secComSettings"
                        //     changeTheValue={this.changeTheValue}
                        //   />
                        //                 */
                        //             },
                        //             secComAlgoChallengeOtoToBack: {
                        //                 visible: {
                        //                     ref: 'secComUseSameAlgo',
                        //                     test: is => !is
                        //                 },
                        //                 label: 'Otoroshi to backend',
                        //                 /*
                        //                 <AlgoSettings
                        //           algo={this.state.service.secComAlgoChallengeOtoToBack}
                        //           path="secComAlgoChallengeOtoToBack"
                        //           changeTheValue={this.changeTheValue}
                        //         />
                        //                 */
                        //             },
                        //             secComAlgoChallengeBackToOto: {
                        //                 visible: {
                        //                     ref: 'secComUseSameAlgo',
                        //                     test: is => !is
                        //                 },
                        //                 label: 'Backend to otoroshi'
                        //                 /*
                        //                 <AlgoSettings
                        //           algo={this.state.service.secComAlgoChallengeBackToOto}
                        //           path="secComAlgoChallengeBackToOto"
                        //           changeTheValue={this.changeTheValue}
                        //         />
                        //                 */
                        //             },
                        //             secComAlgoInfoToken: {
                        //                 label: 'Info. token',
                        //                 visible: {
                        //                     ref: 'secComUseSameAlgo',
                        //                     test: is => !is
                        //                 },
                        //                 /*
                        //                 <AlgoSettings
                        //           algo={this.state.service.secComAlgoInfoToken}
                        //           path="secComAlgoInfoToken"
                        //           changeTheValue={this.changeTheValue}
                        //         />
                        //                 */
                        //             }
                    },
                    // TODO - update flow
                    flow: [
                        'sendStateChallenge',
                        'sendInfoToken',
                        'secComVersion',
                        'secComInfoTokenVersion',
                        'secComTtl',
                        'secComHeaders.stateRequestName',
                        'secComHeaders.stateResponseName',
                        'secComHeaders.claimRequestName',
                        'secComExcludedPatterns',
                        'secComUseSameAlgo',
                        // 'secComSettings',
                        // 'secComAlgoChallengeOtoToBack',
                        // 'secComAlgoChallengeBackToOto',
                        // 'secComAlgoInfoToken'
                    ]
                }
            },
            {
                id: 'IP whitelist',
                props: {
                    schema: {
                        'ipFiltering.whitelist': {
                            type: type.string,
                            format: 'array',
                            label: "IP allowed list",
                            placeholder: "IP address that can access the service",
                            help: "List of allowed IP addresses"
                        }
                    },
                    flow: [
                        'ipFiltering.whitelist'
                    ]
                }
            },
            {
                id: 'IP blacklist',
                props: {
                    schema: {
                        'ipFiltering.blacklist': {
                            type: type.string,
                            format: 'array',
                            label: "IP blacklist",
                            placeholder: "IP address that cannot access the service",
                            help: "List of blocked IP addresses"
                        }
                    },
                    flow: [
                        'ipFiltering.blacklist'
                    ]
                }
            },
            {
                id: 'Private patterns',
                props: {
                    schema: {
                        privatePatterns: {
                            type: type.string,
                            format: 'array',
                            label: "Private patterns",
                            placeholder: "URI pattern",
                            suffix: "regex",
                            help: "If you define a public pattern that is a little bit too much, you can make some of public URL private again"
                        }
                    },
                    flow: [
                        'privatePatterns'
                    ]
                }
            },
            {
                id: 'Public pattern',
                props: {
                    schema: {
                        publicPatterns: {
                            type: type.string,
                            format: 'array',
                            label: "Public patterns",
                            placeholder: "URI pattern",
                            suffix: "regex",
                            help: "By default, every services are private only and you'll need an API key to access it. However, if you want to expose a public UI, you can define one or more public patterns (regex) to allow access to anybody. For example if you want to allow anybody on any URL, just use '/.*'"
                        }
                    },
                    flow: [
                        'publicPatterns'
                    ]
                }
            }
        ]
    },
    {
        group: 'Api keys',
        icon: 'key',
        elements: [
            {
                id: 'apikey extraction from basic auth',
                props: {
                    schema: {
                        // TODO - basicAuth.enabled = true when selected
                        'apiKeyConstraints.basicAuth.headerName': {
                            type: type.string,
                            label: "Custom header name",
                            help: "The name of the header to get Authorization",
                            placeholder: "Authorization"
                        },
                        'apiKeyConstraints.basicAuth.queryName': {
                            type: type.string,
                            label: "Custom query param name",
                            help: "The name of the query param to get Authorization",
                            placeholder: "basic_auth"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.basicAuth.headerName',
                        'apiKeyConstraints.basicAuth.queryName'
                    ]
                }
            },
            {
                id: 'apikey extraction from custom headers',
                props: {
                    schema: {
                        'apiKeyConstraints.customHeadersAuth.clientIdHeaderName': {
                            type: type.string,
                            label: "Custom client id header name",
                            help: "The name of the header to get the client id",
                            placeholder: "Otoroshi-Client-Id"
                        },
                        'apiKeyConstraints.customHeadersAuth.clientSecretHeaderName': {
                            type: type.string,
                            label: "Custom client secret header name",
                            help: "The name of the header to get the client secret",
                            placeholder: "Otoroshi-Client-Secret"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.customHeadersAuth.clientIdHeaderName',
                        'apiKeyConstraints.customHeadersAuth.clientSecretHeaderName'
                    ]
                }
            },
            {
                id: 'apikey extraction from jwt token',
                props: {
                    schema: {
                        'apiKeyConstraints.jwtAuth.secretSigned': {
                            type: type.bool,
                            label: "Secret signed",
                            help: "JWT can be signed by apikey secret using HMAC algo."
                        },
                        'apiKeyConstraints.jwtAuth.keyPairSigned': {
                            type: type.bool,
                            label: "Keypair signed",
                            help: "JWT can be signed by an otoroshi managed keypair using RSA/EC algo."
                        },
                        'apiKeyConstraints.jwtAuth.includeRequestAttributes': {
                            type: type.bool,
                            label: "Include Http request attrs.",
                            help: "If enabled, you have to put the following fields in the JWT token corresponding to the current http call (httpPath, httpVerb, httpHost)"
                        },
                        'apiKeyConstraints.jwtAuth.maxJwtLifespanSecs': {
                            type: type.number,
                            label: "Max accepted token lifetime",
                            help: "The maximum number of second accepted as token lifespan",
                            suffix: "seconds"
                        },
                        'apiKeyConstraints.jwtAuth.headerName': {
                            type: type.string,
                            label: "Custom header name",
                            help: "The name of the header to get the jwt token",
                            placeholder: "Authorization or Otoroshi-Token"
                        },
                        'apiKeyConstraints.jwtAuth.queryName': {
                            type: type.string,
                            label: "Custom query param name",
                            help: "The name of the query param to get the jwt token",
                            placeholder: "access_token"
                        },
                        'apiKeyConstraints.jwtAuth.cookieName': {
                            type: type.string,
                            label: "Custom cookie name",
                            help: "The name of the cookie to get the jwt token",
                            placeholder: "access_token"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.jwtAuth.secretSigned',
                        'apiKeyConstraints.jwtAuth.keyPairSigned',
                        'apiKeyConstraints.jwtAuth.includeRequestAttributes',
                        'apiKeyConstraints.jwtAuth.maxJwtLifespanSecs',
                        'apiKeyConstraints.jwtAuth.headerName',
                        'apiKeyConstraints.jwtAuth.queryName',
                        'apiKeyConstraints.jwtAuth.cookieName'
                    ]
                }
            },
            {
                id: 'apikey extraction from client_id',
                props: {
                    schema: {
                        'apiKeyConstraints.clientIdAuth.headerName': {
                            type: type.string,
                            label: "Custom header name",
                            help: "The name of the header to get the client id",
                            placeholder: "x-api-key"
                        },
                        'apiKeyConstraints.clientIdAuth.queryName': {
                            type: type.string,
                            label: "Custom query param name",
                            help: "The name of the query param to get the client id",
                            placeholder: "x-api-key"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.clientIdAuth.headerName',
                        'apiKeyConstraints.clientIdAuth.queryName'
                    ]
                }
            }
        ]
    },
    {
        group: 'Restrictions',
        icon: 'ban',
        elements: [
            {
                id: 'allowed patterns',
                props: {
                    schema: {
                        'restrictions.allowed': {
                            type: type.object,
                            format: 'array',
                            label: "Allowed",
                            help: "Allowed paths"
                        }
                    },
                    flow: [
                        'restrictions.allowed'
                    ]
                }
            },
            {
                id: 'forbidden patterns',
                props: {
                    schema: {
                        'restrictions.forbidden': {
                            type: type.object,
                            format: 'array',
                            label: "Forbidden",
                            help: "Forbidden paths"
                        }
                    },
                    flow: [
                        'restrictions.forbidden'
                    ]
                }
            },
            {
                id: 'not found patterns',
                props: {
                    schema: {
                        'restrictions.notFound': {
                            type: type.object,
                            format: 'array',
                            label: "Not Found",
                            help: "Not found paths"
                        }
                    },
                    flow: [
                        'restrictions.notFound'
                    ]
                }
            },
            {
                id: 'No Tags in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.noneTagIn': {
                            type: type.string,
                            format: 'array',
                            label: "No Tags in",
                            help: "Api used should not have one of the following tags"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.noneTagIn'
                    ]
                }
            },
            {
                id: 'One Tag in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.oneTagIn': {
                            type: type.string,
                            format: 'array',
                            label: "One Tag in",
                            help: "Api used should have at least one of the following tags"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.oneTagIn'
                    ]
                }
            },
            {
                id: 'All Tags in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.allTagsIn': {
                            type: type.string,
                            format: 'array',
                            label: "All Tags in",
                            help: "Api used should have all of the following tags"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.allTagsIn'
                    ]
                }
            },
            {
                id: 'One Meta. in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.oneMetaIn': {
                            type: type.object,
                            format: 'array',
                            label: 'One Meta. in',
                            help: "Api used should have at least one of the following metadata entries"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.oneMetaIn'
                    ]
                }
            },
            {
                id: 'All Meta. in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.allMetaIn': {
                            type: type.object,
                            format: 'array',
                            label: 'All Meta. in',
                            help: "Api used should have all of the following metadata entries"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.allMetaIn'
                    ]
                }
            },
            {
                id: 'No Meta. in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.noneMetaIn': {
                            type: type.object,
                            format: 'array',
                            label: 'No Meta. in',
                            help: "Api used should not have one of the following metadata entries"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.noneMetaIn'
                    ]
                }
            },
            {
                id: 'One Meta key in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.oneMetaKeyIn': {
                            type: type.string,
                            format: 'array',
                            label: "One Meta key in",
                            help: "Api used should have at least one of the following key in metadata"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.oneMetaKeyIn'
                    ]
                }
            },
            {
                id: 'All Meta key in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.allMetaKeysIn': {
                            type: type.string,
                            format: 'array',
                            label: "All Meta key in",
                            help: "Api used should have all of the following keys in metadata"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.allMetaKeysIn'
                    ]
                }
            },
            {
                id: 'No Meta key in',
                props: {
                    schema: {
                        'apiKeyConstraints.routing.noneMetaKeysIn': {
                            type: type.string,
                            format: 'array',
                            label: "No Meta key in",
                            help: "Api used should not have one of the following keys in metadata"
                        }
                    },
                    flow: [
                        'apiKeyConstraints.routing.noneMetaKeysIn'
                    ]
                }
            }
        ]
    },
    {
        group: 'Targets',
        icon: 'bullseye',
        elements: [
            {
                id: 'Loadbalancing',
                default: true,
                onTargetStream: true,
                props: {
                    schema: {
                        'targetsLoadBalancing.type': {
                            type: type.string,
                            format: format.select,
                            label: 'Load balancing',
                            help: 'The load balancing algorithm used',
                            options: LOAD_BALANCING
                        }
                    },
                    flow: [
                        'targetsLoadBalancing.type'
                    ]
                }
            },
            {
                id: 'Target root',
                default: true,
                onTargetStream: true,
                props: {
                    schema: {
                        root: {
                            type: type.string,
                            label: 'Target root',
                            placeholder: 'The root URL of the target service',
                            help: "Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar",
                        }
                    },
                    flow: [
                        'root'
                    ]
                }
            },
            {
                id: 'Targets',
                default: true,
                onTargetStream: true,
                props: {
                    schema: {
                        targets: {
                            // type: type.object,
                            // format: 'array',
                            type: type.string,
                            format: 'array',
                            label: 'Targets',
                            help: "The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures"
                        }
                    },
                    flow: [
                        'targets'
                    ]
                    // schema: {
                    //   host: {
                    //     type: type.string,
                    //     label: 'Host',
                    //     placeholder: 'The host of the target',
                    //   },
                    //   scheme: {
                    //     type: type.string,
                    //     label: 'Scheme',
                    //     placeholder: 'The scheme of the target',
                    //   },
                    //   weight: {
                    //     type: type.number,
                    //     label: 'Weight',
                    //     placeholder: 'The weight of the target in the sequence of targets. Only used with experimental client'
                    //   },
                    //   'mtlsConfig.certs': {
                    //     type: type.string,
                    //     format: 'array',
                    //     label: 'Client certificates',
                    //     placeholder: 'The certificate used when performing a mTLS call'
                    //   },
                    //   'mtlsConfig.trustedCerts': {
                    //     type: type.string,
                    //     format: 'array',
                    //     label: 'Trusted certificates',
                    //     placeholder: 'The trusted certificate used when performing a mTLS call',

                    //   },
                    //   'mtlsConfig.mtls': {
                    //     type: type.bool,
                    //     label: 'Custom TLS Settings',
                    //     placeholder: 'If enabled, Otoroshi will try to provide client certificate trusted by the target server, trust all servers, etc.'
                    //   },
                    //   'mtlsConfig.loose': {
                    //     type: type.bool,
                    //     label: 'TLS Loose',
                    //     placeholder: 'If enabled, Otoroshi will accept any certificate and disable hostname verification'
                    //   },
                    //   'mtlsConfig.trustAll': {
                    //     type: type.bool,
                    //     label: 'Trust all',
                    //     placeholder: 'If enabled, Otoroshi will accept trust all certificates'
                    //   },
                    //   tags: {
                    //     type: type.string,
                    //     format: 'array',
                    //     label: 'Tags',
                    //     placeholder: 'A tag'
                    //   },
                    //   metadata: {
                    //     type: type.object,
                    //     format: 'array',
                    //     label: 'Metadata',
                    //     placeholder: 'Specify metadata for the target'
                    //   },
                    //   protocol: {
                    //     type: type.string,
                    //     format: format.select,
                    //     label: 'Protocol',
                    //     placeholder: 'The protocol of the target. Only used with experimental client',
                    //     options: HTTP_PROTOCOLS,
                    //     defaultValue: HTTP_PROTOCOLS[1]
                    //   },
                    //   'predicate.type': {
                    //     type: type.string,
                    //     format: format.select,
                    //     label: 'Predicate',
                    //     placeholder: 'The predicate of the target. Only used with experimental client',
                    //     options: PREDICATES,
                    //     defaultValue: PREDICATES[0]
                    //   },
                    //   ipAddress: {
                    //     type: type.string,
                    //     label: 'IP Address',
                    //     placeholder: 'The ip address of the target. Could be useful to perform manual DNS resolution. Only used with experimental client',
                    //   }
                    // },
                    // flow: [
                    //   'host',
                    //   'scheme',
                    //   'weight',
                    //   'mtlsConfig.certs',
                    //   'mtlsConfig.trustedCerts',
                    //   'mtlsConfig.mtls',
                    //   'mtlsConfig.loose',
                    //   'mtlsConfig.trustAll',
                    //   'tags',
                    //   'metadata',
                    //   'protocol',
                    //   'predicate.type',
                    //   'ipAddress'
                    // ]
                }
            },
            {
                id: 'Client settings',
                props: {
                    schema: {
                        // FORM
                    },
                    flow: [
                        'clientConfig'
                    ]
                }
            }
        ]
    },
    // {
    //     group: 'Plugins',
    //     icon: 'code',
    //     resources: () => fetch('/bo/api/proxy/api/scripts/_list')
    //         .then(r => r.json())
    //         .then(plugins => plugins.filter(e => !!e.description))
    //         .then(plugins => plugins.map(plugin => ({ ...plugin, id: plugin.name, _id: plugin.id }))),
    //     elements: []
    // }
]