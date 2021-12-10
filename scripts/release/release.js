// node release.js --from=1.4.2-dev --to=1.4.0 --next=1.4.2-dev --last=1.3.1 --location=/path/to/otoroshi
const cmd = require('node-cmd');
const { spawn } = require('child_process');
const path = require('path');
const _ = require('lodash');
const moment = require('moment');
const fs = require('fs-extra');
const fetch = require('node-fetch');
const { chunksToLinesAsync, chomp } = require('@rauschma/stringio');
const argv = require('minimist')(process.argv.slice(2));

const BINTRAY_API_KEY = process.env.BINTRAY_API_KEY;
const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const JDK8_HOME = process.env.JDK8_HOME;
const JAVA_HOME = process.env.JAVA_HOME;

const files = [
  { file: './kubernetes/kustomize/overlays/cluster/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  { file: './kubernetes/kustomize/overlays/cluster-baremetal/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  { file: './kubernetes/kustomize/overlays/cluster-baremetal-daemonset/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  { file: './kubernetes/kustomize/overlays/simple/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  { file: './kubernetes/kustomize/overlays/simple-baremetal/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  { file: './kubernetes/kustomize/overlays/simple-baremetal-daemonset/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`) },
  {
    file: './manual/src/main/paradox/deploy/kubernetes.md',
    replace: (from, to, source) => source.replace(`?ref=v${from}`, `?ref=v${to}`).replace(`maif/otoroshi:${from}-jdk11`, `maif/otoroshi:${to}-jdk11`)
  },
  {
    file: './kubernetes/helm/otoroshi/Chart.yaml',
    replace: (from, to, source) => source.replace(`appVersion: ${from}`, `appVersion: ${to}`)
  },
  {
    file: './kubernetes/helm/otoroshi/values.yaml',
    replace: (from, to, source) => source.replace(`tag: ${from}-jdk11`, `tag: ${to}-jdk11`)
  },
  {
    file: './clients/cli/Cargo.toml',
    replace: (from, to, source) => source.replace(`version = "${from}"`, `version = "${to}"`)
  },
  {
    file: './clients/cli/src/main.rs',
    replace: (from, to, source) => source.replace(`version = "${from}"`, `version = "${to}"`)
  },
  {
    file: './clients/tcp-udp-tunnel-client/client.js',
    replace: (from, to, source) => source.replace(`Otoroshi TCP tunnel CLI, version ${from}`, `Otoroshi TCP tunnel CLI, version ${to}`)
  },
  { file: './demos/basic-setup/docker-compose.yml' },
  { file: './demos/service-mesh/docker-compose-manual.yml' },
  { file: './demos/service-mesh/docker-compose.yml' },
  { file: './docker/build/build.sh' },
  { file: './manual/build.sbt' },
  { file: './manual/src/main/paradox/code/openapi.json' },
  { file: './manual/src/main/paradox/getotoroshi/frombinaries.md' },
  { file: './manual/src/main/paradox/getotoroshi/fromdocker.md' },
  { file: './manual/src/main/paradox/index.md' },
  { file: './manual/src/main/paradox/quickstart.md' },
  { file: './manual/src/main/paradox/snippets/build.gradle' },
  { file: './manual/src/main/paradox/snippets/build.sbt' },
  { file: './otoroshi/app/controllers/SwaggerController.scala' },
  { file: './otoroshi/app/openapi/openapi.scala' },
  { file: './otoroshi/app/env/Env.scala' },
  { file: './otoroshi/build.sbt', replace: (from, to, source) => source.replace(`version := "${from}"`, `version := "${to}"`) },
  { file: './readme.md' },
];

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

async function keypress() {
  process.stdin.setRawMode(true)
  return new Promise(resolve => process.stdin.once('data', () => {
    process.stdin.setRawMode(false)
    resolve()
  }))
}

let steps = [];

async function ensureStep(step, file, f) {
  const found = _.find(steps, s => s.step === step && s.state === 'stop');
  if (!!found) {
    console.log(`Step ${step} already done ... moving along`);
    return Promise.resolve('');
  }
  console.log(`
===================================================================================================  
== Step: ${step}
===================================================================================================  
  `);
  fs.appendFileSync(file, JSON.stringify({ timestamp: Date.now(), at: moment().format('YYYY-MM-DD hh:mm:ss.SSS'), step, state: 'start' }) + '\n');
  return f().then(() => {
    fs.appendFileSync(file, JSON.stringify({ timestamp: Date.now(), at: moment().format('YYYY-MM-DD hh:mm:ss.SSS'), step, state: 'stop' }) + '\n');
  }, e => {
    fs.appendFileSync(file, JSON.stringify({ timestamp: Date.now(), at: moment().format('YYYY-MM-DD hh:mm:ss.SSS'), step, state: 'error', error: e.message }) + '\n');
    throw new Error(e);
  });
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

async function changeVersion(where, from, to, exclude = []) {
  console.log(`Changing version from '${from}' to '${to}'`)
  return new Promise(s => {
    files.filter(f => !(exclude.indexOf(f.file) > -1)).map(file => {
      const filePath = path.resolve(where, file.file);
      const content = fs.readFileSync(filePath, 'utf8');
      console.log('Changing version in', filePath);
      const replace = file.replace || ((f, t, s) => s.replace(new RegExp(f.replace(new RegExp('\\.', 'g'), '\\.'), 'g'), t));
      const newContent = replace(from, to, content);
      fs.writeFileSync(filePath, newContent);
    });
    s();
  });
}

async function formatCode(version, where, releaseDir) {
  // format code
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/fmt.sh')], where);
  await runSystemCommand('git', ['commit', '-am', `Format code before release`], location);
}

async function cleanup(version, where, releaseDir) {
  // clean
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/build.sh'), 'clean'], where);
}

async function buildUi(version, where, releaseDir) {
  // build ui
  await runScript(`
    cd ${where}/otoroshi/javascript
    yarn install
    cd ${where}
    sh ${where}/scripts/build.sh ui
  `, where);
}

async function buildOpenApi(version, where, releaseDir) {
  // build openapi
  await runScript(`
    cd ${where}/otoroshi
    sbt ";clean;compile;testOnly OpenapiGeneratorTests"
  `, where);
  await runSystemCommand('cp', [`${where}/otoroshi/public/openapi.json`, `${releaseDir}/openapi.json`], location);
  await runSystemCommand('cp', [`${releaseDir}/openapi.json`, `${where}/manual/src/main/paradox/code/`], location);
  await runSystemCommand('git', ['add', `${releaseDir}/openapi.json`], location);
  await runSystemCommand('git', ['add', `${where}/manual/src/main/paradox/code/openapi.json`], location);
  await runSystemCommand('git', ['commit', '-am', `Update openapi file before release`], location);
}

async function buildPluginDoc(version, where, releaseDir) {
  // build plugins doc
  // needs JDK11 !!!! 
  await runScript(`
    cd ${where}/otoroshi
    sbt ";clean;compile;testOnly PluginDocTests"
  `, where);
  await runSystemCommand('git', ['add', `${where}/manual/src/main/paradox/plugins`], location);
  await runSystemCommand('git', ['commit', '-am', `Update plugin documentation`], location);
}

async function buildDocumentation(version, where, releaseDir, releaseFile) {
  // build doc with schemas
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/doc.sh'), 'all'], where);
  await runSystemCommand('zip', ['-r', path.resolve(releaseDir, `otoroshi-manual-${version}.zip`), path.resolve(where, 'docs/manual'), '-x', '*.DS_Store'], where);
  await runSystemCommand('git', ['add', '--all'], location);
  await runSystemCommand('git', ['commit', '-am', `Update site documentation before release`], location);
}

async function buildDistribution(version, where, releaseDir, releaseFile) {
  // run test and build server
  await runScript(`
  export JAVA_HOME=$JDK8_HOME
  export PATH=\${JAVA_HOME}/bin:\${PATH}
  cd ${where}/otoroshi
  sbt ";dist;assembly"
  `, where);
  // await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/build.sh'), 'server'], where);
  await runSystemCommand('cp', ['-v', path.resolve(where, './otoroshi/target/scala-2.12/otoroshi.jar'), path.resolve(where, releaseDir)], where);
  await runSystemCommand('cp', ['-v', path.resolve(where, `./otoroshi/target/universal/otoroshi-${version}.zip`),  path.resolve(where, releaseDir)], where);
}

async function buildVersion(version, where, releaseDir, releaseFile) {
  await ensureStep('BUILD_VERSION_FORMAT_CODE', releaseFile, () => formatCode(version, where, releaseDir, releaseFile));
  await ensureStep('BUILD_VERSION_CLEANUP', releaseFile, () => cleanup(version, where, releaseDir, releaseFile));
  await ensureStep('BUILD_VERSION_BUILD_OPENAPI', releaseFile, () => buildOpenApi(version, where, releaseDir, releaseFile));
  // await ensureStep('BUILD_VERSION_BUILD_PLUGINS_DOC', releaseFile, () => buildPluginDoc(version, where, releaseDir, releaseFile));
  await ensureStep('BUILD_VERSION_BUILD_DOCUMENTATION', releaseFile, () => buildDocumentation(version, where, releaseDir, releaseFile));
  await ensureStep('BUILD_VERSION_BUILD_UI', releaseFile, () => buildUi(version, where, releaseDir, releaseFile));
  await ensureStep('BUILD_VERSION_BUILD_DISTRIBUTION', releaseFile, () => buildDistribution(version, where, releaseDir, releaseFile));
}

async function publishDockerOtoroshi(location, version) {
  await runSystemCommand('cp', [path.resolve(location, `./otoroshi/target/universal/otoroshi-${version}.zip`), path.resolve(location, `./docker/build/otoroshi-dist.zip`)], location);
  await runSystemCommand('cp', [path.resolve(location, `./otoroshi/target/scala-2.12/otoroshi.jar`), path.resolve(location, `./docker/build/otoroshi.jar`)], location);
  await runSystemCommand('sh', [path.resolve(location, `./docker/build/build.sh`), 'push-all', version], path.resolve(location, `./docker/build`));
  await runSystemCommand('sh', [path.resolve(location, `./clients/sidecar/build.sh`), 'push-all', version], path.resolve(location, `./clients/sidecar`));
}

async function buildTcpTunnelingCli(location, version) {
  await runScript(`
    cd ${location}/clients/tcp-udp-tunnel-client
    yarn install
    yarn pkg
    cp -v "$LOCATION/clients/tcp-udp-tunnel-client/binaries/otoroshi-tcp-udp-tunnel-cli-linux" "$LOCATION/release-$VERSION/"
    cp -v "$LOCATION/clients/tcp-udp-tunnel-client/binaries/otoroshi-tcp-udp-tunnel-cli-macos" "$LOCATION/release-$VERSION/"
    cp -v "$LOCATION/clients/tcp-udp-tunnel-client/binaries/otoroshi-tcp-udp-tunnel-cli-win.exe" "$LOCATION/release-$VERSION/"
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      BINTRAY_API_KEY,
      GITHUB_TOKEN
    }
  );
}

async function buildTcpTunnelingCliGUI(location, version) {
  await runScript(`
    cd ${location}/clients/tcp-udp-tunnel-client-gui
    yarn install
    yarn dist-mac
    hdiutil create -format UDZO -srcfolder "$LOCATION/clients/tcp-udp-tunnel-client-gui/dist/otoroshi-tunneling-client-darwin-x64/otoroshi-tunneling-client.app" "$LOCATION/release-$VERSION/otoroshi-tunneling-client.dmg"
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      BINTRAY_API_KEY,
      GITHUB_TOKEN
    }
  );
}

async function githubTag(location, version) {
  await runSystemCommand('git', ['commit', '-am', `Prepare the release of Otoroshi version ${version}`], location);
  await runSystemCommand('git', ['tag', '-am', `Release Otoroshi version ${version}`, 'v' + version], location);
}

async function publishMavenCentral(location, version) {
  await runScript(`
    cd $LOCATION/otoroshi
    export JAVA_HOME=$JDK8_HOME
    export PATH=\${JAVA_HOME}/bin:\${PATH}
    sbt ";doc;packageDoc;publishSigned;sonatypeBundleRelease"
    cd $LOCATION
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      PGP_PASSPHRASE: process.env.PGP_PASSPHRASE,
      PGP_SECRET: process.env.PGP_SECRET,
      SONATYPE_PASSWORD: process.env.SONATYPE_PASSWORD,
      SONATYPE_USERNAME: process.env.SONATYPE_USERNAME,
    }
  );
}

async function createGithubRelease(version, location) {
  return fetch('https://api.github.com/repos/MAIF/otoroshi/releases', {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'Authorization': `token ${GITHUB_TOKEN}`,
    },
    body: JSON.stringify({
      "tag_name": `v${version}`,
      "name": `${version}`,
      "body": `Otoroshi version ${version}`,
      "draft": true,
      "prerelease": false
    })
  }).then(r => r.json()).then(r => {
    console.log(r);
    return uploadAllFiles(r, location, version);
  });
}

async function uploadAllFiles(release, location, to) {
  await uploadFilesToRelease(release, { name: 'otoroshi.jar', path: path.resolve(location, `otoroshi.jar`) });
  await uploadFilesToRelease(release, { name: `otoroshi-${to}.zip`, path: path.resolve(location, `otoroshi-${to}.zip`) });
  await uploadFilesToRelease(release, { name: `otoroshi-manual-${to}.zip`, path: path.resolve(location, `otoroshi-manual-${to}.zip`) });
  await uploadFilesToRelease(release, { name: `otoroshi-tcp-udp-tunnel-cli-linux`, path: path.resolve(location, `otoroshi-tcp-udp-tunnel-cli-linux`) });
  await uploadFilesToRelease(release, { name: `otoroshi-tcp-udp-tunnel-cli-macos`, path: path.resolve(location, `otoroshi-tcp-udp-tunnel-cli-macos`) });
  await uploadFilesToRelease(release, { name: `otoroshi-tcp-udp-tunnel-cli-win.exe`, path: path.resolve(location, `otoroshi-tcp-udp-tunnel-cli-win.exe`) });
  await uploadFilesToRelease(release, { name: `otoroshi-tunneling-client.dmg`, path: path.resolve(location, `otoroshi-tunneling-client.dmg`) });
}

async function uploadFilesToRelease(release, file) {
  const assetUrl = `https://uploads.github.com/repos/MAIF/otoroshi/releases/${release.id}/assets?name=${file.name}`;
  return fetch(assetUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/octet-stream',
      'Authorization': `token ${GITHUB_TOKEN}`,
    },
    body: fs.readFileSync(file.path)
  }).then(r => r.text()).then(r => {
    console.log(r);
    return r;
  })  
}

async function installDependencies(location) {
  await runSystemCommand('yarn', ['install'], path.resolve(location, './demos/loadbalancing'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './demos/snowmonkey'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/clevercloud'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/common'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/kubernetes'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/rancher'));
}

async function releaseOtoroshi(from, to, next, last, location, dryRun) {
  console.log(`Releasing Otoroshi from version '${from}' to version '${to}'/'${next}' (${location})`);
  console.log(`Don't forget to set JAVA_HOME to JDK8_HOME and to docker login`);
  console.log(`Don't forget to setup nvm with latest 13.x`);
  console.log(`Don't forget to update the CHANGELOG file before starting`);
  console.log(`Press a key to continue ...`)
  await keypress();
  const releaseDir = path.resolve(location, `./release-${to}`);
  const releaseFile = path.resolve(releaseDir, 'release-steps');
  if (!fs.pathExistsSync(location)) {
    const last = location.split('/').pop();
    await runSystemCommand('git', ['clone', 'https://github.com/MAIF/otoroshi.git', last, '--depth=1'], path.resolve(location, '..'));
  }
  fs.mkdirpSync(releaseDir);
  if (!fs.pathExistsSync(releaseFile)) {
    fs.createFileSync(releaseFile);
  } else {
    steps = fs.readFileSync(releaseFile, 'utf8').split('\n').map(a => a.trim()).filter(a => a !== '').map(line => JSON.parse(line));
  }
  
  await ensureStep('INSTALL_DEPS', releaseFile, () => installDependencies(location));
  await ensureStep('CHANGE_TO_RELEASE_VERSION', releaseFile, async () => {
    {
      const filePath = path.resolve(location, './docs/index.html');
      const content = fs.readFileSync(filePath, 'utf8');
      console.log('Changing version in', filePath);
      const newContent = content.replace(last, to);
      fs.writeFileSync(filePath, newContent);
    }
    await changeVersion(location, from, to);
    await changeVersion(location, last, to);
    await runSystemCommand('git', ['commit', '-am', `Update version to ${to}`], location);
  });
  // await ensureStep('BUILD_OTOROSHI', releaseFile, () => buildVersion(to, location, releaseDir, releaseFile));
  await buildVersion(to, location, releaseDir, releaseFile);
  await ensureStep('BUILD_TCP_TUNNEL_CLI', releaseFile, () => buildTcpTunnelingCli(location, to));
  await ensureStep('BUILD_TCP_TUNNEL_CLI_GUI', releaseFile, () => buildTcpTunnelingCliGUI(location, to));
  if (!dryRun) {
    await ensureStep('CREATE_GITHUB_RELEASE', releaseFile, () => createGithubRelease(to, releaseDir));
    await ensureStep('CREATE_GITHUB_TAG', releaseFile, () => githubTag(location, to));
    await ensureStep('PUBLISH_LIBRARIES_TO_CENTRAL', releaseFile, () => publishMavenCentral(location, to));
    await ensureStep('PUBLISH_DOCKER_OTOROSHI', releaseFile, () => publishDockerOtoroshi(location, to));
    await ensureStep('CHANGE_TO_DEV_VERSION', releaseFile, () => changeVersion(location, to, next, ['./readme.md']));
    await ensureStep('PUSH_TO_GITHUB', releaseFile, async () => {
      await runSystemCommand('git', ['commit', '-am', `Update version to ${next}`], location);
      await runSystemCommand('git', ['pull', '--rebase', 'origin', 'master'], location);
      await runSystemCommand('git', ['push', 'origin', 'master'], location);
      await runSystemCommand('git', ['push', '--tags'], location);
      console.log("Release done !");
    });
    process.exit(0);
  }
}

function printEnv() {
  console.log("current env is: ")
  console.log({
    JDK8_HOME,
    JAVA_HOME,
    dryRun,
    releaseFrom,
    releaseTo,
    releaseNext,
    releaseLast,
    location
  })
  runScript('java -version', argv.location || __dirname, {})
  runScript('node -v', argv.location || __dirname, {})
}

const dryRun = argv.dry || false;
const releaseFrom = argv.from;
const releaseTo = argv.to;
const releaseNext = argv.next;
const releaseLast = argv.last;
const location = argv.location || __dirname;

if (!JDK8_HOME) {
  throw new Error('No JDK8_HOME defined !')
}
if (!releaseFrom) {
  throw new Error('No current version')
}
if (!releaseTo) {
  throw new Error('No release version')
}
if (!releaseNext) {
  throw new Error('No next version')
}

if (!releaseLast) {
  throw new Error('No last version')
}

printEnv();

releaseOtoroshi(releaseFrom, releaseTo, releaseNext, releaseLast, location, dryRun);


/*
# usage 

export JAVA_HOME=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk//Contents/Home
export JDK8_HOME=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk//Contents/Home
export PATH=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk//Contents/Home/bin:$PATH
nvm use 13
docker login
node release.js --from=1.5.0-dev --to=1.5.0-alpha.13 --next=1.5.0-dev --last=1.5.0-alpha.12 --location=/Users/mathieuancelin/projects/otoroshi

*/

/*
async function publishDockerCli(location, version) {
  await runScript(`
    cd $LOCATION/docker/otoroshicli
    cp ../../clients/cli/target/release/otoroshicli ./otoroshicli
    docker build --no-cache -t otoroshicli .
    rm ./otoroshicli
    docker tag otoroshicli "maif/otoroshicli:$VERSION" 
    docker tag otoroshicli "maif/otoroshicli:latest"
    docker push "maif/otoroshicli:$VERSION"
    docker push "maif/otoroshicli:latest"
    cd $LOCATION
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      BINTRAY_API_KEY,
      GITHUB_TOKEN
    }
  );
}

async function buildMacCLI(location, version) {
  await runScript(`
    # build cli for mac
    sh ./scripts/build.sh cli
    cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
    mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/mac-otoroshicli"
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      BINTRAY_API_KEY,
      GITHUB_TOKEN
    }
  );
}

async function buildLinuxCLI(location, version) {
  await runScript(`
    # build cli for linux
    sh ./scripts/cli-linux-build.sh
    cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
    mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/linux-otoroshicli"  
    `, 
    location, 
    {
      LOCATION: location,
      VERSION: version,
      BINTRAY_API_KEY,
      GITHUB_TOKEN
    }
  );
}
*/
