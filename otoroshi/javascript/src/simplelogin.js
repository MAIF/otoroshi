import 'es6-shim';
import 'whatwg-fetch';
import 'core-js/es6/map';
import 'core-js/es6/set';
import './raf';

import 'react-table/react-table.css';
import './style/main.scss';

import Symbol from 'es-symbol';
import $ from 'jquery';
import React from 'react';
import ReactDOM from 'react-dom';
import browserUpdate from 'browser-update';
import { SimpleLoginPage } from './pages/SimpleLoginPage';

import { registerAlert, registerConfirm, registerPrompt, registerPopup } from './components/window';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

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

export function simpleLogin(opts, node) {
  setupOutdatedBrowser();
  setupWindowUtils();

  ReactDOM.render(<SimpleLoginPage {...opts} />, node);
}
