import 'es6-shim';
import 'whatwg-fetch';
import 'core-js/es/map';
import 'core-js/es/set';
import './raf';

import 'react-table/react-table.css';

import Symbol from 'es-symbol';
import React from 'react';
import ReactDOM from 'react-dom';
import browserUpdate from 'browser-update';
import { MultiLoginPage } from './pages/MultiLoginPage';

import { registerAlert, registerConfirm, registerPrompt, registerPopup } from './components/window';

import './style/main.scss';

if (!window.Symbol) {
  window.Symbol = Symbol;
}

function setupOutdatedBrowser() {
  browserUpdate({
    // test: true,
    l: 'en',
    noclose: true,
    notify: {
      i: 11,
      f: -4,
      o: -12,
      s: 11,
      c: -6,
    },
  });
}

function setupWindowUtils() {
  registerAlert();
  registerConfirm();
  registerPrompt();
  registerPopup();
}

export function multiLogin(opts, node) {
  setupOutdatedBrowser();
  setupWindowUtils();

  ReactDOM.render(<MultiLoginPage {...opts} />, node);
}
