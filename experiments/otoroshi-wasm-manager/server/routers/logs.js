const express = require('express');
const path = require('path');
const fs = require('fs-extra');

const { Server } = require("socket.io");

// path.join(process.cwd(), 'logs', req.params.id, 'stdout.log')

let io;

const createLogsWebSocket = server => {
  io = new Server(server);
  io.on('connection', (socket) => {
    // console.log('a user connected');
    socket.on('disconnect', () => {
      // console.log('user disconnected');
    });
  });
}

const emit = (channel, message) => {
  io.emit(channel, message)
}

module.exports = {
  IO: {
    createLogsWebSocket,
    emit
  }
}