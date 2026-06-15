var PROTO_PATH = __dirname + '/helloworld.proto';

var assert = require('assert');
var async = require('async');
var _ = require('lodash');
var grpc = require('@grpc/grpc-js');
var protoLoader = require('@grpc/proto-loader');
var reflection = require('@grpc/reflection');  // ← ADD THIS

var packageDefinition = protoLoader.loadSync(
  PROTO_PATH,
  {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true
  });
var protoDescriptor = grpc.loadPackageDefinition(packageDefinition);
var helloworld = protoDescriptor.helloworld;

function doSayHello(call, callback) {
  callback(null, { message: 'Hello! ' + call.request.name });
}

function doSayRepeatHello(call) {
  var senders = [];
  function sender(name) {
    return (callback) => {
      call.write({
        message: 'Hey! ' + name
      });
      _.delay(callback, 500);
    };
  }
  for (var i = 0; i < call.request.count; i++) {
    senders[i] = sender(call.request.name + i);
  }
  async.series(senders, () => {
    call.end();
  });
}

function getServer() {
  var server = new grpc.Server();
  server.addService(helloworld.Greeter.service, {
    sayHello: doSayHello,
    sayRepeatHello: doSayRepeatHello,
  });

  // ← ADD THESE LINES
  const reflectionImpl = new reflection.ReflectionService(packageDefinition);
  reflectionImpl.addToServer(server);

  return server;
}

if (require.main === module) {
  var server = getServer();
  console.log('binding :8082')
  server.bindAsync(
    '0.0.0.0:8082', grpc.ServerCredentials.createInsecure(), (err, port) => {
      assert.ifError(err);
      server.start();
      console.log('Reflection enabled');  // ← ADD THIS
    });
}

exports.getServer = getServer;