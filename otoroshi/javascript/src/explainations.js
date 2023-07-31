
const CIRCUIT_BREAKER_BACKOFF_FACTOR =
    'Specify the factor to multiply the delay for each retry';
const CIRCUIT_BREAKER_CLIENT_RETRIES =
    'Specify how many times the client will retry to fetch the result of the request after an error before giving up.';
const CIRCUIT_BREAKER_MAX_ERRORS =
    'Specify how many errors can pass before opening the circuit breaker';
const CIRCUIT_BREAKER_GLOBAL_TIMEOUT =
    'Specify how long the global call (with retries) should last at most in milliseconds.';
const CIRCUIT_BREAKER_CONNECTION_TIMEOUT =
    'Specify how long each connection should last at most in milliseconds.';
const CIRCUIT_BREAKER_IDLE_TIMEOUT =
    'Specify how long each connection can stay in idle state at most in milliseconds.';
const CIRCUIT_BREAKER_CALL_TIMEOUT =
    'Specify how long each call should last at most in milliseconds.';
const CIRCUIT_BREAKER_CALL_AND_STREAM_TIMEOUT =
    'Specify how long each call should last at most in milliseconds for handling the request and streaming the response.';
const CIRCUIT_BREAKER_RETRY_INITIAL_DELAY =
    'Specify the delay between two retries. Each retry, the delay is multiplied by the backoff factor';
const CIRCUIT_BREAKER_SAMPLE_INTERVAL =
    'Specify the sliding window time for the circuit breaker in milliseconds, after this time, error count will be reseted';
const CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_ENABLED =
    'Use a cache at host connection level to avoid reconnection time';
const CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_QUEUE_SIZE =
    'Queue size for an open tcp connection';
const CIRCUIT_BREAKER_CUSTOM_TIMEOUT_PATH = 'Path on which the timeout will be active';

const explaintations = {
    CIRCUIT_BREAKER_BACKOFF_FACTOR,
    CIRCUIT_BREAKER_CLIENT_RETRIES,
    CIRCUIT_BREAKER_MAX_ERRORS,
    CIRCUIT_BREAKER_GLOBAL_TIMEOUT,
    CIRCUIT_BREAKER_CONNECTION_TIMEOUT,
    CIRCUIT_BREAKER_IDLE_TIMEOUT,
    CIRCUIT_BREAKER_CALL_TIMEOUT,
    CIRCUIT_BREAKER_CALL_AND_STREAM_TIMEOUT,
    CIRCUIT_BREAKER_RETRY_INITIAL_DELAY,
    CIRCUIT_BREAKER_SAMPLE_INTERVAL,
    CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_ENABLED,
    CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_QUEUE_SIZE,
}

export default explaintations;