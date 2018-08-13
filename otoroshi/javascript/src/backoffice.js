import 'es6-shim';
import 'whatwg-fetch';
import 'core-js/es6/map';
import 'core-js/es6/set';
import './raf';

import 'react-select/dist/react-select.css';
import 'react-table/react-table.css';
import './style/main.scss';

import Symbol from 'es-symbol';
import $ from 'jquery';
import React from 'react';
import ReactDOM from 'react-dom';
import browserUpdate from 'browser-update';
import { BackOfficeApp } from './apps/BackOfficeApp';
import { U2FLoginPage } from './pages/U2FLoginPage';
import { GenericLoginPage } from './pages/GenericLoginPage';
import * as BackOfficeServices from './services/BackOfficeServices';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

require('bootstrap/dist/js/bootstrap.min');

Number.prototype.prettify = function() {
  return this.toString().replace(/(\d)(?=(\d{3})+(?!\d))/g, '$1 ');
};

const pattern = '38384040373937396665';

function Konami(callback) {
  let input = '';
  document.addEventListener(
    'keydown',
    event => {
      input += event ? event.keyCode : event.keyCode;
      if (input.length > pattern.length) {
        input = input.substr(input.length - pattern.length);
      }
      if (input === pattern) {
        callback(); // eslint-disable-line
        input = '';
      }
    },
    false
  );
}

function setupKonami() {
  Konami(() => {
    function showClippy() {
      window.clippy.load('Clippy', function(agent) {
        agent.moveTo(window.innerWidth - 200, 150);
        agent.show();
        setTimeout(() => {
          agent.speak('Welcome to Otoroshi. How can I help you ?');
          setInterval(() => {
            agent.animate();
          }, 10000);
        }, 1000);
      });
    }

    if (!document.getElementById('clippycss')) {
      const css = document.createElement('link');
      css.setAttribute('id', 'clippycss');
      css.setAttribute('rel', 'stylesheet');
      css.setAttribute('type', 'text/css');
      css.setAttribute('media', 'all');
      css.setAttribute('href', '/__otoroshi_assets/clippy/build/clippy.css');
      const js = document.createElement('script');
      js.setAttribute('src', '/__otoroshi_assets/clippy/build/clippy.min.js');
      document.head.appendChild(css);
      document.body.appendChild(js);
      js.addEventListener(
        'load',
        () => {
          showClippy();
        },
        false
      );
    } else {
      showClippy();
    }
  });
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

export function init(node) {
  setupKonami();
  setupOutdatedBrowser();
  ReactDOM.render(<BackOfficeApp />, node);
}

export function login(node) {
  setupOutdatedBrowser();
  ReactDOM.render(<U2FLoginPage />, node);
}

export function genericLogin(opts, node) {
  setupOutdatedBrowser();
  ReactDOM.render(<GenericLoginPage {...opts} />, node);
}
