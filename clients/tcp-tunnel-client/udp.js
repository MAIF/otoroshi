var dgram = require('dgram');
var events = require('events');
var util = require('util');
var net = require('net');

var UdpProxy = function (options) {
    "use strict";
    var proxy = this;
    var localUdpType = 'udp4';
    var serverPort = options.localport || 0;
    var serverHost = options.localaddress || '0.0.0.0';
    var proxyHost = options.proxyaddress || '0.0.0.0';
    this.tOutTime = options.timeOutTime || 10000;
    this.family = 'IPv4';
    this.udpType = 'udp4';
    this.host = options.address || 'localhost';
    this.port = options.port || 41234;
    this.connections = {};
    if (options.ipv6) {
        this.udpType = 'udp6';
        this.family = 'IPv6';
        proxyHost = net.isIPv6(options.proxyaddress) ? options.proxyaddress : '::0';
    }
    this._details = {
        target: {
            address: this.host,
            family: this.family,
            port: this.port
        }
    };
    this._detailKeys = Object.keys(this._details);
    if (options.localipv6) {
        localUdpType = 'udp6';
        serverHost = net.isIPv6(options.localaddress) ? options.localaddress : '::0';
    }
    this._server = dgram.createSocket(localUdpType);
    this._server.on('listening', function () {
        var details = proxy.getDetails({server: this.address()});
        setImmediate(function() {
            proxy.emit('listening', details);
        });
    }).on('message', function (msg, sender) {
        var client = proxy.createClient(msg, sender);
        if (!client._bound) client.bind(0, proxyHost);
        else client.emit('send', msg, sender);
    }).on('error', function (err) {
        this.close();
        proxy.emit('error', err);
    }).on('close', function () {
        proxy.emit('close');
    }).bind(serverPort, serverHost);
};

util.inherits(UdpProxy, events.EventEmitter);

UdpProxy.prototype.getDetails = function getDetails(initialObj) {
    var self = this;
    return this._detailKeys.reduce(function (obj, key) {
        obj[key] = self._details[key];
        return obj;
    }, initialObj);
};

UdpProxy.prototype.hashD = function hashD(address) {
    return (address.address + address.port).replace(/\./g, '');
};

UdpProxy.prototype.send = function send(msg, port, address, callback) {
    this._server.send(msg, 0, msg.length, port, address, callback);
};

UdpProxy.prototype.createClient = function createClient(msg, sender) {
    var senderD = this.hashD(sender);
    var proxy = this;
    var client;
    if (this.connections.hasOwnProperty(senderD)) {
        client = this.connections[senderD];
        clearTimeout(client.t);
        client.t = null;
        return client;
    }
    client = dgram.createSocket(this.udpType);
    client.once('listening', function () {
        var details = proxy.getDetails({route: this.address(), peer: sender});
        this.peer = sender;
        this._bound = true;
        proxy.emit('bound', details);
        this.emit('send', msg, sender);
    }).on('message', function (msg, sender) {
        proxy.handleProxyMsg(this, proxy, msg, sender, this.peer);
    }).on('close', function () {
        proxy.emit('proxyClose', this.peer);
        this.removeAllListeners();
        delete proxy.connections[senderD];
    }).on('error', function (err) {
        this.close();
        proxy.emit('proxyError', err);
    }).on('send', function (msg, sender) {
        proxy.handleMessage(this, proxy, msg, sender);
    });
    this.connections[senderD] = client;
    return client;
};

UdpProxy.prototype.handleProxyMsg = function handleProxyMsg(socket, proxy, msg, sender, peer) {
    proxy.send(msg, peer.port, peer.address, function (err, bytes) {
        if (err) socket.emit('proxyError', err);
    });
    proxy.emit('proxyMsg', msg, sender, peer);
};

UdpProxy.prototype.handleMessage = function handleMessage(socket, proxy, msg, sender) {
    proxy.emit('message', msg, sender);
    socket.send(msg, 0, msg.length, proxy.port, proxy.host, function (err, bytes) {
        if (err) proxy.emit('proxyError', err);
        if (!socket.t) socket.t = setTimeout(function () {
            socket.close();
        }, proxy.tOutTime);
    });
};


UdpProxy.prototype.close = function close(callback) {
    // close clients
    var proxyConnections = this.connections;
    Object.keys(proxyConnections).forEach(function (senderD) {
        var client = proxyConnections[senderD];
        if (client.t) {
            clearTimeout(client.t);
            client.t = null;
            client.close();
        }
    });
    this.connections = {};
    this._server.close(callback || function () {});
};