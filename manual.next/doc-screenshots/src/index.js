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

//console.log(process.argv);
// console.log(argv);

const otoroshiUrl = argv.url || process.env.OTOROSHI_DOCSCREENS_URL || "http://otoroshi.oto.tools:9999";
const otoroshiUser = argv.user || process.env.OTOROSHI_DOCSCREENS_USER || "admin@otoroshi.io";
const otoroshiPassword = argv.pwd || process.env.OTOROSHI_DOCSCREENS_PWD || "password";

let scenarii = [];
const otoroshiPath = argv['otoroshi-path'] || process.env.OTOROSHI_DOCSCREENS_OTOROSHI_PATH || './otoroshi.jar';
const noOtoroshi = argv['no-otoroshi'] || (process.env.OTOROSHI_DOCSCREENS_OTOROSHI_PATH === "true") || false
const screenshotsPath = argv['screenshot-path'] || process.env.OTOROSHI_DOCSCREENS_SCREENSHOTS_PATH || 'screenshots';
const rawScenarii = argv.raw || process.env.OTOROSHI_DOCSCREENS_RAW || '[]';
const scenariiPath = argv.scenarii || process.env.OTOROSHI_DOCSCREENS_SCENARII_PATH;
const scenariiFolderPath = argv['scenarii-folder'] || process.env.OTOROSHI_DOCSCREENS_SCENARII_FOLDER_PATH;
const apiCommandsFolder = argv['api-commands-folder'] || process.env.OTOROSHI_DOCSCREENS_API_COMMANDS_FOLDER_PATH;
const parseMdFilesFrom = argv['parse-md-files-from'] || process.env.OTOROSHI_DOCSCREENS_PARSE_MD_FILES_FROM;

function setupScenarii() {
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
  if (parseMdFilesFrom) {
    parseMdFiles(parseMdFilesFrom);
  }
  if (argv.debug) {
    console.log(JSON.stringify(scenarii, null, 2))
  }
}

function walkSync(dir, initDir, filelist = []) {
  var files = fs.readdirSync(dir);
  files.forEach((file) => {
    if (fs.statSync(dir + '/' + file).isDirectory()) {
      filelist = walkSync(dir + '/' + file, initDir, filelist);
    } else {
      const content = fs.readFileSync(dir + '/' + file).toString('utf8');
      filelist.push({ 
        name: file, 
        path: dir + '/' + file,
        content: content
      });
    }
  });
  return filelist;
}

const shortCuts = {
  'goto-organizations': [{ name: 'goto-organizations', action: 'goto', path: '/bo/dashboard/organizations' }],
  'goto-edit-organization': [{ name: 'goto-edit-organization', action: 'goto', path: '/bo/dashboard/organizations/edit/$id' }],
  'goto-add-organization': [{ name: 'goto-add-organization', action: 'goto', path: '/bo/dashboard/organizations/add/$id' }],
  //////////////////
  'goto-teams': [{ name: 'goto-teams', action: 'goto', path: '/bo/dashboard/teams' }],
  'goto-edit-team': [{ name: 'goto-edit-team', action: 'goto', path: '/bo/dashboard/teams/edit/$id' }],
  'goto-add-team': [{ name: 'goto-add-team', action: 'goto', path: '/bo/dashboard/teams/add/$id' }],
  //////////////////
  'goto-groups': [{ name: 'goto-groups', action: 'goto', path: '/bo/dashboard/groups' }],
  'goto-edit-group': [{ name: 'goto-edit-group', action: 'goto', path: '/bo/dashboard/groups/edit/$id' }],
  'goto-add-group': [{ name: 'goto-add-group', action: 'goto', path: '/bo/dashboard/groups/add/$id' }],
  //////////////////
  'goto-services': [{ name: 'goto-services', action: 'goto', path: '/bo/dashboard/services' }],
  'goto-edit-service': [{ name: 'goto-edit-service', action: 'goto', path: '/bo/dashboard/services/edit/$id' }],
  'goto-add-service': [{ name: 'goto-add-service', action: 'goto', path: '/bo/dashboard/services/add/$id' }],
  //////////////////
  'goto-apikeys': [{ name: 'goto-apikeys', action: 'goto', path: '/bo/dashboard/apikeys' }],
  'goto-edit-apikey': [{ name: 'goto-edit-apikey', action: 'goto', path: '/bo/dashboard/apikeys/edit/$id' }],
  'goto-add-apikey': [{ name: 'goto-add-apikey', action: 'goto', path: '/bo/dashboard/apikeys/add/$id' }],
  //////////////////
  'goto-auths': [{ name: 'goto-auths', action: 'goto', path: '/bo/dashboard/auth-configs' }],
  'goto-edit-auth': [{ name: 'goto-edit-auth', action: 'goto', path: '/bo/dashboard/auth-configs/edit/$id' }],
  'goto-add-auth': [{ name: 'goto-add-auth', action: 'goto', path: '/bo/dashboard/auth-configs/add/$id' }],
  //////////////////
  'goto-jwts': [{ name: 'goto-jwts', action: 'goto', path: '/bo/dashboard/jwt-verifiers' }],
  'goto-edit-jwt': [{ name: 'goto-edit-jwt', action: 'goto', path: '/bo/dashboard/jwt-verifiers/edit/$id' }],
  'goto-add-jwt': [{ name: 'goto-add-jwt', action: 'goto', path: '/bo/dashboard/jwt-verifiers/add/$id' }],
  //////////////////
  'goto-certificates': [{ name: 'goto-certificates', action: 'goto', path: '/bo/dashboard/certificates' }],
  'goto-edit-certificate': [{ name: 'goto-edit-certificate', action: 'goto', path: '/bo/dashboard/certificates/edit/$id' }],
  'goto-add-certificate': [{ name: 'goto-add-certificate', action: 'goto', path: '/bo/dashboard/certificates/add/$id' }],
  //////////////////
  'goto-plugins': [{ name: 'goto-plugins', action: 'goto', path: '/bo/dashboard/plugins' }],
  'goto-edit-plugin': [{ name: 'goto-edit-plugin', action: 'goto', path: '/bo/dashboard/plugins/edit/$id' }],
  'goto-add-plugin': [{ name: 'goto-add-plugin', action: 'goto', path: '/bo/dashboard/plugins/add/$id' }],
  //////////////////
  'goto-exporters': [{ name: 'goto-exporters', action: 'goto', path: '/bo/dashboard/exporters' }],
  'goto-edit-exporter': [{ name: 'goto-edit-exporter', action: 'goto', path: '/bo/dashboard/exporters/edit/$id' }],
  'goto-add-exporter': [{ name: 'goto-add-exporter', action: 'goto', path: '/bo/dashboard/exporters/add/$id' }],
  //////////////////
  'goto-tcp-services': [{ name: 'goto-tcp-services', action: 'goto', path: '/bo/dashboard/tcp/services' }],
  'goto-edit-tcp-service': [{ name: 'goto-edit-tcp-service', action: 'goto', path: '/bo/dashboard/tcp/services/edit/$id' }],
  'goto-add-tcp-service': [{ name: 'goto-add-tcp-service', action: 'goto', path: '/bo/dashboard/tcp/services/add/$id' }],
  //////////////////
  'goto-dangerzone': [{ name: 'goto-dangerzone', action: 'goto', path: '/bo/dashboard/dangerzone' }],
  'goto-cluster': [{ name: 'goto-cluster', action: 'goto', path: '/bo/dashboard/cluster' }],
  'goto-snowmonkey': [{ name: 'goto-snowmonkey', action: 'goto', path: '/bo/dashboard/snowmonkey' }],
  'goto-privappssessions': [{ name: 'goto-privappssessions', action: 'goto', path: '/bo/dashboard/sessions/private' }],
  'goto-adminssessions': [{ name: 'goto-adminssessions', action: 'goto', path: '/bo/dashboard/sessions/admin' }],
  'goto-alerts': [{ name: 'goto-alerts', action: 'goto', path: '/bo/dashboard/alerts' }],
  'goto-audits': [{ name: 'goto-audits', action: 'goto', path: '/bo/dashboard/audits' }],
  'goto-global-events': [{ name: 'goto-global-events', action: 'goto', path: '/bo/dashboard/events' }],
  'goto-global-status': [{ name: 'goto-global-status', action: 'goto', path: '/bo/dashboard/status' }],
  'goto-global-stats': [{ name: 'goto-global-stats', action: 'goto', path: '/bo/dashboard/stats' }],
  'goto-home': [{ name: 'goto-home', action: 'goto', path: '/bo/dashboard' }],
}

/* supported steps

- goto theUrl
- click #theSelector>.foo
- wait 10
- spot #theSelector>.foo
- scroll-to #theSelector>.foo
- screenshot foo.png
- screenshot-area foo.png #theSelector>.foo
- screenshot-static foo.png left:top:width:height
- type #theSelector>.foo hello world !
- send-api foo.json
- all shortcuts

*/
function parseMdFiles(from) {
  const files = walkSync(from, from);
  const mdFiles = files.filter(f => f.name.indexOf('.md') > -1);
  const filesWithScenarii = mdFiles.filter(f => f.content.indexOf('<!-- oto-scenario') > -1)
  if (filesWithScenarii.length > 0) {
    filesWithScenarii.map(file => {
      let inside = false;
      let scens = [];
      let scenlines = [];
      file.content.split('\n').map(line => {
        if (line.trim().indexOf('<!-- oto-scenario') === 0) {
          inside = true;
        }
        if (inside && line.trim().indexOf('-->') === 0) {
          inside = false;
          scens = [ ...scens, scenlines]
          scenlines = [];
        }
        if (inside) {
          if (line.trim().length > 0) {
            scenlines = [ ...scenlines, line ];
          }
        }
      })
      scens.map((lines, idx) => {
        const scen = { name: `scenario-${file.name}-${idx}`, steps: [] };
        lines.filter(l => l.indexOf(' - ') === 0).map(l => l.replace(' - ', '')).map((line, idx2) => {
          const parts = line.split(' ');
          const action = parts[0];
          const filename = file.name.replace(/\./g, '-');
          if (shortCuts[action]) {
            const id = parts[1];
            scen.steps = [ ...scen.steps, ...shortCuts[action].map(s => ({ ...s, path: s.path.replace('$id', id)})) ]
          } else if (action === 'goto') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-goto`,
              action: 'goto',
              path: parts[1]
            })
          } else if (action === 'click') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-click`,
              action: 'click',
              selector: parts[1]
            })
          } else if (action === 'wait') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-wait`,
              action: 'wait',
              what: parseInt(parts[1], 10)
            })
          } else if (action === 'spot') {
            // TODO: support spot-at
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-spot`,
              action: 'spot',
              selector: parts[1]
            })
          }  else if (action === 'scroll-to') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-scroll-to`,
              action: 'scroll-to',
              selector: parts[1]
            })
          } else if (action === 'screenshot') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-screenshot`,
              action: 'screenshot',
              filename: parts[1]
            })
          } else if (action === 'screenshot-area') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-screenshotarea`,
              action: 'screenshot',
              filename: parts[1],
              selector: parts.slice(2).join(' '),
              area: 10
            })
          } else if (action === 'screenshot-static') {
            const boxParams = parts[2].split(':');
            const box = { left: boxParams[0], top: boxParams[1], width: boxParams[2], height: boxParams[3] };
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-screenshotstatic`,
              action: 'screenshot-static',
              filename: parts[1],
              box: box
            })
          } else if (action === 'type') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-type`,
              action: 'type',
              selector: parts[1],
              input: parts.slice(2).join(' '),
            })
          } else if (action === 'send-api') {
            scen.steps.push({
              name: `scenario-${filename}-${idx}-step-${idx2}-sendapi`,
              action: 'send-api',
              filename: parts[1],
            })
          }
        });
        // console.log(scen)
        scenarii = [ ...scenarii, scen ];
      })
    })
  }
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

let lastProcess = null;

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
          success(new Error('bad return code: ' + code));
        }
      }
    });
    lastProcess = source;
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

async function waitForOtoroshi(max) {
  let count = 0;
  return new Promise((success, failure) => {

    function call() {
      if (count <= max) {
        count = count + 1;
        return fetch(`${otoroshiUrl}/ready`, {
          method: 'GET',
          headers: {
            'Accept': 'application/json'
          }
        }).then(r => {
          if (r.status === 200) {
            success();
          } else {
            setTimeout(() => {
              call()
            }, 1000);
          }
        }).catch(e => {
          setTimeout(() => {
            call()
          }, 1000);
        }) 
      } else {
        failure('too much calls: ' + count);
      }
    }

    call();

  });

}

async function isOtoroshiRunning() {
  return fetch(`${otoroshiUrl}/ready`, {
    method: 'GET',
    headers: {
      'Accept': 'application/json'
    }
  }).then(r => {
    return true;
  }).catch(e => {
    return false;
  }) 
}

async function runOtoroshi() {
  if (!noOtoroshi) {
    console.log('trying to run otoroshi ...');
    const running = await isOtoroshiRunning();
    if (running) {
      console.log('otoroshi is already running !');
      return Promise.resolve(lastProcess);
    } else {
      runScript(`
        java -DrunMode=screenshot-generator-otoroshi -Dhttp.port=9999 -Dhttps.port=9998 -Dapp.adminLogin=${otoroshiUser} -Dapp.adminPassword=${otoroshiPassword} -Dapp.importFrom=${__dirname}/../data/otoroshi.json -jar ${__dirname}/${otoroshiPath}
      `, 
      __dirname, 
      {})
      await waitForOtoroshi(30);
      console.log('otoroshi is running !')
      return lastProcess;
    }
  } else {
    return Promise.resolve(lastProcess);
  }
}

async function handleStep(step, browser, page, setPage, logger) {
  const action = step.action || 'none';
  if (step.name) {
    logger(`running action: '${step.name}'`)
  }
  if (action === 'none') {
    return Promise.resolve('');
  } else if (action === 'spot') {
    const selector = step.selector;
    const color = step.color || 'red';
    const width = step.width || '3px';
    const borderStyle = step.borderStyle || 'solid';
    if (step.at) {
      return page.evaluate((c, w, bs, at) => {
        const element = document.createElement('div');
        element.style.outline = `${w} ${bs} ${c}`;
        element.style.backgroundColor = 'rgba(0, 0, 0, 0);';
        element.style.width = at.width;
        element.style.height = at.height;
        element.style.left = at.left;
        element.style.top = at.top;
        element.style.zIndex = '10000';
        element.style.position = 'fixed';
        document.body.appendChild(element);
      }, color, width, borderStyle, step.at);
    } else if (step.selector) {
      return page.evaluate((s, c, w, bs) => {
        const element = document.querySelector(s);
        if (element) {
          element.style.outline = `${w} ${bs} ${c}`;
        }
      }, selector, color, width, borderStyle);
    } else {
      return Promise.resolve('');
    }
  } else if (action === 'scroll-to') {
    return page.evaluate((selector) => {
      document.querySelector(selector)
        .scrollIntoView({ behavior: 'auto', block: 'end', inline: 'end' });
    }, step.selector);
  } else if (action === 'goto') {
    const path = step.path;
    //page.close();
    //const newPage = await browser.newPage();
    //setPage(newPage);
    // console.log('goto', otoroshiUrl + path)
    return page.goto(otoroshiUrl + path).then(() => {
      // console.log('waiting ... ')
      // return page.waitForNavigation();
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
  } else if (action === 'screenshot-static') {   
    const box = step.box;   
    const margin = 0;
    const clip = { 'x': box.x - margin, 'y': box.y - margin, 'width': box.width + (margin * 2), 'height': box.height + (margin * 2) };
    return element.screenshot({ path: `${screenshotsPath}/${step.filename}`, clip });
  } else if (action === 'send-api') {   
    if (apiCommandsFolder) {
      const filename = step.filename;   
      const commandsRaw = fs.readFileSync(`${apiCommandsFolder}/${filename}`);
      const commands = JSON.parse(commandsRaw);
      return new Promise((success, failure) => {
        function next() {
          const action = commands.shift();
          if (action) {
            const headers = action.headers || {};
            fetch(`${otoroshiUrl}${action.path}`, {
              method: action.method,
              headers: { 
                ...headers,
                'Accept': 'application/json',
                'Content-Type': headers['Content-Type'] || (action.body ? 'application/json' : headers['Content-Type']),
                Authorization: `Basic ${Buffer.from('admin-api-apikey-id:admin-api-apikey-secret').toString('base64')}`
              },
              body: action.body
            }).then(r => {
              return r.json().then(body => {
                if (argv.debug) {
                  console.log(`api call "${step.name}": ${r.status} - ${body}`)
                }
              });
            })
          } else {
            success();
          }
        }
        next();
      });
    } else {
      console.log('error: api-commands-folder not specified ...')
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
  // console.log('======================================')
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

async function runScreenshots(process) {
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
    try {
      await page.click('#app > div > div.topbar-popup > button');
    } catch (e) {
      console.log('ignoring', e.message)
    }
    console.log('login done, running scenarii !')
    console.log('======================================')
    await handleScenarii(scenarii, browser, page);
    console.log('closing browser ...')
    await browser.close();
    if (process) {
      console.log('killing the process !', process.pid)
      process.kill(9);
      try {
        await runScript(`ps aux  |  grep -i screenshot-generator-otoroshi  |  awk '{print $2}'  |  xargs kill -9`, __dirname, {}).catch(e => {
          console.log('kill catch error', e.message)
        });
      } catch(e) {
        console.log('kill error', e.message)
      }
    }
    await waitFor(2000);
  } catch(ex) {
    console.log(ex);
  }
}

function cleanupScreenshots() {
  const files = fs.readdirSync(screenshotsPath);
  files.filter(f => f.endsWith('.png')).map(f => {
    fs.removeSync(screenshotsPath + '/' + f)
  })
}

setupScenarii();
runOtoroshi().then((p) => {
  // cleanupScreenshots();
  runScreenshots(p).then(() => {
    process.exit(0);
  });
});
