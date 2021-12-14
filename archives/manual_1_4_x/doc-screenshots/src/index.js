const cmd = require('node-cmd');
const { spawn } = require('child_process');
const path = require('path');
const _ = require('lodash');
const moment = require('moment');
const fs = require('fs-extra');
const fetch = require('node-fetch');
const { chunksToLinesAsync, chomp } = require('@rauschma/stringio');
const puppeteer = require('puppeteer');

const argv = require('minimist')(process.argv.slice(2));

const otoroshiUrl = argv.url || process.env.OTOROSHI_DOCSCREENS_URL || "http://otoroshi.oto.tools:9999";
const otoroshiUser = argv.user || process.env.OTOROSHI_DOCSCREENS_USER || "admin@otoroshi.io";
const otoroshiPassword = argv.pwd || process.env.OTOROSHI_DOCSCREENS_PWD || "password";

let scenarii = [];
const screenshotsPath = argv['screenshot-path'] || process.env.OTOROSHI_DOCSCREENS_SCREENSHOTS_PATH || 'screenshots';
const rawScenarii = argv.raw || process.env.OTOROSHI_DOCSCREENS_RAW || '[]';
const scenariiPath = argv.scenarii || process.env.OTOROSHI_DOCSCREENS_SCENARII_PATH || './scenarii.json';
const scenariiFolderPath = argv['scenarii-folder'] || process.env.OTOROSHI_DOCSCREENS_SCENARII_FOLDER_PATH;

if (rawScenarii) {
  scenarii = [ ...scenarii, ...JSON.parse(rawScenarii) ];
}
if (scenariiPath) {
  scenarii = [ ...scenarii, ...JSON.parse(fs.readFileSync(scenariiPath)) ];
}
if (scenariiFolderPath) {
  const files = fs.readdirSync(scenariiFolderPath);
  files.filter(f => f.endsWith('.json')).map(f => {
    scenarii = [ ...scenarii, ...JSON.parse(fs.readFileSync(scenariiFolderPath + '/' + f)) ];
  })
}

async function echoReadable(readable) {
  for await (const line of chunksToLinesAsync(readable)) {
    console.log('  ' + chomp(line))
  }
}

function runSystemCommand(command, args, location, env = {}) {
  const source = spawn(command, args, {
    cwd: location,
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', process.stderr]
  });
  return echoReadable(source.stdout);
}

function runScript(script, where, env = {}, fit) {
  return new Promise((success, failure) => {
    const source = spawn(script, [], {
      cwd: where,
      shell: true,
      env: { ...process.env, ...env },
      stdio: ['ignore', 'pipe', process.stderr]
    });
    source.on('close', (code) => {
      if (fit) {
        success('return code: ' + code)
      } else {
        if (code === 0) {
          success('return code: ' + code)
        } else {
          failure(new Error('bad return code: ' + code));
        }
      }
    });
    return echoReadable(source.stdout);
  });
}  

function waitFor(millis) {
  return new Promise(s => {
    setTimeout(() => {
      s();
    }, millis)
  }) 
}

function asyncForEach(_arr, f) {
  return new Promise((success, failure) => {
    const arr = [ ..._arr ];
    function next() {
      const item = arr.shift();
      if (item) {
        const res = f(item);
        if (res && res.then) {
          res.then(() => {
            next();
          })
        } else {
          setTimeout(() => next(), 10);
        }
      } else {
        success();
      }
    }
    next();
  });
}

function runOtoroshi() {
  // TODO: actually run oto
  console.log('trying to run otoroshi ...');
  console.log('otoroshi already running !')
  return Promise.resolve('');
}

async function handleStep(step, browser, page, setPage, logger) {
  const action = step.action || 'none';
  if (step.name) {
    logger(`running action: '${step.name}'`)
  }
  if (action === 'none') {
    return Promise.resolve('');
  } else if (action === 'goto') {
    const path = step.path;
    const page = await browser.newPage();
    setPage(page);
    return page.goto(otoroshiUrl + path).then(() => {
      return page.waitForNavigation();
    });
  } else if (action === 'wait') {
    const what = step.what;
    if (what === 'navigation') {
      return page.waitForNavigation();
    } else {
      return waitFor(what)
    }
  } else if (action === 'click') {
    if (step.wait && step.wait === 'navigation') {
      return page.click(step.selector).then(() => {
        return page.waitForNavigation();
      });
    } else if (step.wait) {
      return page.click(step.selector).then(() => {
        return waitFor(step.wait);
      });
    } else {
      return page.click(step.selector);
    }
  } else if (action === 'type') {
    return page.type(step.selector, step.input);
  } else if (action === 'screenshot') {
    if (step.selector && step.area) {
      return page.$(step.selector).then(element => {
        return element.boundingBox().then(box => {
          const margin = step.area || 10;
          const clip = { 'x': box.x - margin, 'y': box.y - margin, 'width': box.width + (margin * 2), 'height': box.height + (margin * 2) };
          return element.screenshot({ path: `${screenshotsPath}/${step.filename}`, fullPage: step.fullPage || false, clip });
        });
      });
    } else if (step.selector) {
      return page.$(step.selector).then(element => {
        return element.screenshot({ path: `${screenshotsPath}/${step.filename}`, fullPage: step.fullPage || false });
      });
    } else {
      return page.screenshot({ path: `${screenshotsPath}/${step.filename}`, fullPage: step.fullPage || false });
    }
  } else if (action === 'focus') {
    return Promise.resolve('');
  } else if (action === 'hover') {
    return Promise.resolve('');
  } else if (action === 'keyboard') {
    return Promise.resolve('');
  } else if (action === 'mouse') {
    return Promise.resolve('');
  } else {
    return Promise.resolve('');
  }
}

async function handleScenario(scenario, browser, _page) {
  console.log('======================================')
  console.log(`running scenario: '${scenario.name}'\n`)
  const logger = (...args) => console.log(`[${scenario.name}]`, ...args)
  let page = _page;
  const setPage = (p) => page = p;
  await asyncForEach(scenario.steps, step => {
    return handleStep(step, browser, page, setPage, logger);
  });
  console.log('======================================')
}

async function handleScenarii(scenarii, browser, page) {
  await asyncForEach(scenarii, (s) => {
    return handleScenario(s, browser, page);
  });
}

async function runScreenshots() {
  try {
    console.log('launching browser ...')
    const browser = await puppeteer.launch({
      args: [`--window-size=1920,1080`],
      defaultViewport: {
        width: 1920,
        height: 1080
      }
    });
    const page = await browser.newPage();
    await page.deleteCookie({ name: 'otoroshi-session', domain: '.oto.tools' });
    await page.goto(otoroshiUrl);
    console.log('login default admin user ...')
    await page.type('input[name=email]', otoroshiUser)
    await page.type('input[name="password"]', otoroshiPassword)
    await page.click('button[type="submit"]')
    await page.waitForNavigation();
    await waitFor(2000);
    console.log('closing popup ...')
    await page.click('#app > div > div.topbar-popup > button');
    console.log('login done, running scenarii !')
    await handleScenarii(scenarii, browser, page);
    console.log('closing browser ...')
    await browser.close();
  } catch(ex) {
    console.log(ex);
  }
}

function cleanupScreenshots() {
  const files = fs.readdirSync(screenshotsPath);
  files.filter(f => f.endsWith('.png')).map(f => {
    fs.rmSync(screenshotsPath + '/' + f)
  })
}

runOtoroshi().then(() => {
  cleanupScreenshots();
  runScreenshots();
})
