const { Server } = require("socket.io");

let io;

const createLogsWebSocket = server => {
  io = new Server(server);
}

const emit = (channel, group, message) => {
  io.emit(channel, `[${group}] ${message}`)
}

const emitError = (channel, group, message) => {
  io.emit(channel, `ERROR - [${group}] ${message}`)
}

module.exports = {
  WebSocket: {
    createLogsWebSocket,
    emit,
    emitError,
  }
}