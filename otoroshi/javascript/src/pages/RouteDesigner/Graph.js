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

export const PLUGIN_INFORMATIONS_SCHEMA = {
    enabled: {
        visibleOnCollapse: true,
        type: type.bool,
        label: 'Enabled'
    },
    debug: {
        type: type.bool,
        label: 'Debug'
    },
    include: {
        label: 'Include',
        format: "singleLineCode",
        type: type.string,
        array: true,
        createOption: true
    },
    exclude: {
        label: 'Exclude',
        format: "singleLineCode",
        type: type.string,
        array: true,
        createOption: true
    }
}

export const EXCLUDED_PLUGINS = {
    plugin_visibility: ['internal'],
    ids: ['otoroshi.next.proxy.ProxyEngine']
}

export const LEGACY_PLUGINS_WRAPPER = {
    "app": "otoroshi.next.plugins.wrappers.RequestTransformerWrapper",
    "transformer": "otoroshi.next.plugins.wrappers.RequestTransformerWrapper",
    "validator": "otoroshi.next.plugins.wrappers.AccessValidatorWrapper",
    "preroute": "otoroshi.next.plugins.wrappers.PreRoutingWrapper",
    "sink": "otoroshi.next.plugins.wrappers.RequestSinkWrapper",
    "composite": "otoroshi.next.plugins.wrappers.CompositeWrapper",
    "listener": "",
    "job": "",
    "exporter": "",
    "request-handler": ""
}

export const DEFAULT_FLOW = {
    Frontend: {
        id: 'Frontend',
        icon: 'eye',
        plugin_steps: ["PreRoute"],
        description: "Exposition",
        default: true,
        field: 'frontend',
        onInputStream: true,
        schema: {
            domains: {
                type: type.string,
                format: 'select',
                label: 'Domains',
                createOption: true,
                isMulti: true
            }
        }
    },
    Backend: {
        id: 'Backend',
        icon: 'bullseye',
        group: 'Targets',
        default: true,
        onTargetStream: true,
        field: 'backend'
    }
}