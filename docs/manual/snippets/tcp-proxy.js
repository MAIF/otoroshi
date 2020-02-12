const proxy = require("node-tcp-proxy");

const hosts = ["192.168.1.40", "192.168.1.41", "192.168.1.42"];
const portsHttp = [8080, 8080, 8080];
const portsHttps = [8443, 8443, 8443];

const proxyHttp = proxy.createProxy(80, hosts, portsHttp, {
  tls: false
});

const proxyHttps = proxy.createProxy(443, hosts, portsHttps, {
  tls: false
});

