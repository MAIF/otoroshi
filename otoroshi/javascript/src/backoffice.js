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
import { GenericLoginPage, GenericLoginPageWithWebAuthn } from './pages/GenericLoginPage';
import { SelfUpdatePage } from './pages/SelfUpdatePage';
import * as BackOfficeServices from './services/BackOfficeServices';

import { registerAlert, registerConfirm, registerPrompt, registerPopup } from './components/window';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

require('bootstrap/dist/js/bootstrap.min');

Number.prototype.prettify = function () {
  return this.toString().replace(/(\d)(?=(\d{3})+(?!\d))/g, '$1 ');
};

window._fetch = window.fetch;
window.fetch = function (...params) {
  const url = params[0];
  const options = params[1];
  const doNotPassTenant =
    window.__otoroshi__env__latest.userAdmin ||
    window.__otoroshi__env__latest.bypassUserRightsCheck;
  if (!doNotPassTenant && params.length == 2 && _.isObject(options)) {
    const currentTenant = window.localStorage.getItem('Otoroshi-Tenant') || 'default';
    return window
      ._fetch(url, {
        ...options,
        headers: { ...options.headers, 'Otoroshi-Tenant': currentTenant },
      })
      .then((r) => {
        if (r.status === 401 || r.status === 403) {
          if (window.toast) {
            window.toast('Authorization error', "You're not allowed to do that !", 'error');
          }
          throw new Error("You're not allowed to do that !");
        } else {
          return r;
        }
      });
  } else {
    // console.log('do not pass tenant for', url, {
    //   plength: params.length,
    //   iso: _.isObject(options),
    //   userAdmin: window.__otoroshi__env__latest.userAdmin,
    //   bypassUserRightsCheck: window.__otoroshi__env__latest.bypassUserRightsCheck
    // });
    return window._fetch(...params);
  }
};

const pattern = '38384040373937396665';

function Konami(callback) {
  let input = '';
  document.addEventListener(
    'keydown',
    (event) => {
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
      window.clippy.load('Clippy', function (agent) {
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

function setupWindowUtils() {
  registerAlert();
  registerConfirm();
  registerPrompt();
  registerPopup();
}

export function init(node) {
  setupKonami();
  setupOutdatedBrowser();
  setupWindowUtils();
  ReactDOM.render(<BackOfficeApp />, node);
}

export function login(node, otoroshiLogo) {
  setupOutdatedBrowser();
  setupWindowUtils();
  ReactDOM.render(<U2FLoginPage otoroshiLogo={otoroshiLogo} />, node);
}

export function genericLogin(opts, node) {
  setupOutdatedBrowser();
  setupWindowUtils();
  if (opts.webauthn) {
    ReactDOM.render(<GenericLoginPageWithWebAuthn {...opts} />, node);
  } else {
    ReactDOM.render(<GenericLoginPage {...opts} />, node);
  }
}

export function selfUpdate(opts, node) {
  setupOutdatedBrowser();
  setupWindowUtils();
  ReactDOM.render(<SelfUpdatePage {...opts} />, node);
}
