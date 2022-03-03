import { type, format, constraints } from '@maif/react-forms';

const LOAD_BALANCING = [
    'RoundRobin',
    'Random',
    'Sticky',
    'IpAddressHash',
    'BestResponseTime',
    'WeightedBestResponseTime'
];

const HTTP_PROTOCOLS = [
    'HTTP/1.0',
    'HTTP/1.1',
    'HTTP/2.0'
];

const PREDICATES = [
    'AlwaysMatch',
    'GeolocationMatch',
    'NetworkLocationMatch'
]

export default [
    {
        id: 'Frontend',
        plugin_steps: ["PreRoute"],
        description: "Exposition",
        icon: 'eye',
        default: true,
        field: 'frontend',
        onInputStream: true,
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
                options: [
                    'GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT', 'OPTIONS', 'TRACE', 'PATCH'
                ],
                isMulti: true
            }
        }
    },
    {
        icon: 'bullseye',
        id: 'Backend',
        group: 'Targets',
        default: true,
        onTargetStream: true,
        field: 'backend',
        flow: [
            'load_balancing',
            'root',
            'rewrite',
            {
                label: 'Targets',
                flow: ['targets'],
                collapsed: true
            },
            {
                label: 'Client',
                flow: ['client'],
                collapsed: true
            }
        ],
        schema: {
            'load_balancing': {
                type: type.object,
                format: format.select,
                label: 'Load balancing',
                help: 'The load balancing algorithm used',
                options: LOAD_BALANCING,
                transformer: v => ({ label: v, value: { type: v } })
            },
            root: {
                type: type.string,
                label: 'Target root',
                placeholder: 'The root URL of the target service',
                help: "Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar",
            },
            rewrite: {
                type: type.bool,
                label: 'Rewrite'
            },
            targets: {
                type: type.object,
                array: true,
                format: format.form,
                label: null,
                help: "The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures",
                schema: {
                    id: {
                        type: type.string,
                        label: 'Id',
                        visible: false
                    },
                    hostname: {
                        type: type.string,
                        label: 'Hostname'
                    },
                    port: {
                        type: type.number,
                        value: null,
                        constraints: [constraints.nullable()],
                        label: 'Port'
                    },
                    tls: {
                        type: type.bool,
                        label: 'TLS'
                    },
                    weight: {
                        type: type.number,
                        label: 'Weight',
                        value: 1,
                        constraints: [constraints.nullable()],
                    },
                    protocol: {
                        type: type.string,
                        format: format.select,
                        options: HTTP_PROTOCOLS,
                        transformer: r => ({ label: r, value: r })
                    },
                    predicate: {
                        type: type.object,
                        format: format.select,
                        options: PREDICATES,
                        defaultValue: ({ label: PREDICATES[0], value: { type: PREDICATES[0] } }),
                        transformer: r => ({ label: r, value: { type: r } })
                    },
                    ipAddress: {
                        type: type.string,
                        label: 'IP Address',
                        constraints: [constraints.nullable()]
                    },
                    tlsConfig: {
                        type: type.object,
                        format: format.form,
                        label: 'TLS configuration',
                        collapsable: true,
                        schema: {
                            certs: {
                                type: type.string,
                                format: format.select,
                                createOption: true,
                                isMulti: true
                            },
                            trustedCerts: {
                                type: type.string,
                                format: format.select,
                                createOption: true,
                                isMulti: true
                            },
                            loose: {
                                type: type.bool,
                                label: 'Loose'
                            },
                            trustAll: {
                                type: type.bool,
                                label: 'Trust All'
                            }
                        }
                    }
                }
            },
            client: {
                type: type.object,
                format: format.form,
                label: null,
                schema: {
                    retries: {
                        label: 'Retries',
                        type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    max_errors: {
                        label: 'Max errors',
                        type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    retry_initial_delay: {
                        label: 'Retry initial delay', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    backoff_factor: {
                        label: 'Backoff factor', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    connection_timeout: {
                        label: 'Connection timeout', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    idle_timeout: {
                        label: 'IDLE timeout', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    call_and_stream_timeout: {
                        label: 'Call and stream timeout', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    call_timeout: {
                        label: 'Call timeout', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    global_timeout: {
                        label: 'Global timeout', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    sample_interval: {
                        label: 'Sample interval', type: type.number,
                        value: null,
                        constraints: [constraints.nullable()]
                    },
                    proxy: {
                        label: 'Proxy',
                        type: type.object,
                        format: format.form,
                        constraints: [
                            constraints.nullable()
                        ],
                        schema: {
                            host: { label: 'Host', type: type.string },
                            port: { label: 'Port', type: type.string },
                            protocol: { label: 'Protocol', type: type.string },
                            principal: { label: 'Principal', type: type.string },
                            password: { label: 'Password', type: type.string },
                            ntlmDomain: { label: 'NTLM Domain', type: type.string },
                            encoding: { label: 'Encoding', type: type.string },
                            nonProxyHosts: {
                                label: 'Non proxy hosts',
                                type: type.string,
                                array: true
                            }
                        }
                    },
                    cache_connection_settings: {
                        label: 'Cache connection settings',
                        type: type.object,
                        format: format.form,
                        schema: {
                            enabled: {
                                type: type.bool,
                            },
                            queue_size: {
                                type: type.number,
                                value: null,
                                constraints: [constraints.nullable()]
                            }
                        }
                    },
                    custom_timeouts: {
                        label: 'Custom timeouts',
                        type: type.object,
                        array: true,
                        format: format.form,
                        schema: {
                            path: { label: 'Path', type: type.string },
                            call_timeout: { label: 'Call timeout', type: type.number },
                            call_and_stream_timeout: { label: 'Call and stream timeout', type: type.number },
                            connection_timeout: { label: 'Connection timeout', type: type.number },
                            idle_timeout: { label: 'IDLE timeout', type: type.number },
                            global_timeout: { label: 'Global timeout', type: type.number }
                        }
                    }
                }
            }
        }
    }
]