import { HTTP_PROTOCOLS, LOAD_BALANCING, PREDICATES } from './Constants';
import { type, format, constraints } from '@maif/react-forms';

export default [
    {
        id: 'Frontend',
        plugin_steps: ["PreRoute"],
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
                label: 'Targets',
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
                        label: 'Port'
                    },
                    tls: {
                        type: type.bool,
                        label: 'TLS'
                    },
                    weight: {
                        type: type.number,
                        label: 'Weight',
                        defaultValue: 1
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
            }
        }
    }
]