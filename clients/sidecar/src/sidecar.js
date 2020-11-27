const { InternalProxy, ExternalProxy } = require('./proxy');

const EXTERNAL_PORT = parseInt(process.env.EXTERNAL_PORT || '8443', 10);
const INTERNAL_PORT = parseInt(process.env.INTERNAL_PORT || '8080', 10);

const internalProxy = InternalProxy({ }).listen({ hostname: '127.0.0.1', port: INTERNAL_PORT })
const externalProxy = ExternalProxy({ }).listen({ hostname: '0.0.0.0', port: EXTERNAL_PORT })