export const LOAD_BALANCING = [
    'RoundRobin',
    'Random',
    'Sticky',
    'IpAddressHash',
    'BestResponseTime',
    'WeightedBestResponseTime'
];

export const HTTP_PROTOCOLS = [
    'HTTP/1.0',
    'HTTP/1.1',
    'HTTP/2.0'
];

export const PREDICATES = [
    'AlwaysMatch',
    'GeolocationMatch',
    'NetworkLocationMatch'
]