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


export const DEFAULT_FLOW = {
    Frontend: {
        id: 'Frontend',
        plugin_steps: ["PreRoute"],
        description: "Exposition",
        icon: 'eye',
        default: true,
        field: 'frontend',
        onInputStream: true,
    },
    Backend: {
        icon: 'bullseye',
        id: 'Backend',
        group: 'Targets',
        default: true,
        onTargetStream: true,
        field: 'backend'
    }
}