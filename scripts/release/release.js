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

const files = [
  {
    file: './clients/cli/Cargo.toml',
    replace: (from, to, source) => source.replace(`version = "${from}"`, `version = "${to}"`)
  },
  {
    file: './clients/cli/src/main.rs',
    replace: (from, to, source) => source.replace(`version = "${from}"`, `version = "${to}"`)
  },
  { file: './demos/basic-setup/docker-compose.yml' },
  { file: './demos/service-mesh/docker-compose-manual.yml' },
  { file: './demos/service-mesh/docker-compose.yml' },
  { file: './docker/build/build.sh' },
  { file: './docker/demo/Dockerfile' },
  { file: './manual/build.sbt' },
  { file: './manual/src/main/paradox/cli.md' },
  { file: './manual/src/main/paradox/code/swagger.json' },
  { file: './manual/src/main/paradox/getotoroshi/frombinaries.md' },
  { file: './manual/src/main/paradox/getotoroshi/fromdocker.md' },
  { file: './manual/src/main/paradox/index.md' },
  { file: './manual/src/main/paradox/quickstart.md' },
  { file: './manual/src/main/paradox/snippets/build.gradle' },
  { file: './manual/src/main/paradox/snippets/build.sbt' },
  { file: './otoroshi/app/controllers/SwaggerController.scala' },
  { file: './otoroshi/app/env/Env.scala' },
  { file: './otoroshi/build.sbt', replace: (from, to, source) => source.replace(`version := "${from}"`, `version := "${to}"`) },
  { file: './readme.md' },
  { file: './scripts/upload.sh' },
  { file: './scripts/wrk.sh' },
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

function runScript(script, where, env = {}) {
  const source = spawn(script, [], {
    cwd: where,
    shell: true,
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', process.stderr]
  });
  return echoReadable(source.stdout);
}

async function changeVersion(where, from, to) {
  return new Promise(s => {
    files.map(file => {
      const filePath = path.resolve(where, file.file);
      const content = fs.readFileSync(filePath, 'utf8');
      console.log('Changing version in', filePath);
      const replace = file.replace || ((f, t, s) => s.replace(f, t));
      const newContent = replace(from, to, content);
      fs.writeFileSync(filePath, newContent);
    });
    s();
  });
}

async function buildVersion(version, where, releaseDir) {
  // format code
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/fmt.sh')], where);
  await runSystemCommand('git', ['commit', '-am', `Format code before release`], location);
  // clean
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/build.sh'), 'clean'], where);
  // build ui
  await runScript(`
    source $NVM_TOOL
    nvm install 8.6.0
    nvm use 8.6.0
    cd ${where}/otoroshi/javascript
    yarn install
    cd ${where}
    sh ${where}/scripts/build.sh ui
  `, where);
  // build bootstrap server
  await runScript(`
    cd ${where}/otoroshi
    sbt ";clean;compile;assembly"
  `, where);
  await runScript(`
    cd ${where}/otoroshi/target/scala-2.12/
    java -jar otoroshi.jar &
    sleep 20
    curl http://otoroshi.foo.bar:8080/api/swagger.json > ${releaseDir}/swagger.json
    cp ${releaseDir}/swagger.json ${where}/manual/src/main/paradox/code/
    ps aux | grep java | grep otoroshi.jar | awk '{print $2}' | xargs kill  >> /dev/null
    rm -f ./RUNNING_PID
  `, where);
  await runSystemCommand('git', ['commit', '-am', `Update swagger file before release`], location);
  // build doc with schemas
  await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/doc.sh'), 'all'], where);
  await runSystemCommand('git', ['add', '--all'], location);
  await runSystemCommand('git', ['commit', '-am', `Update site documentation before release`], location);
  // run test and build server
  await runScript(`
    export JAVA_HOME=$JDK8_HOME
    export PATH=\${JAVA_HOME}/bin:\${PATH}
    cd ${where}/otoroshi
    sbt ";test;dist;assembly"
  `, where);
  // await runSystemCommand('/bin/sh', [path.resolve(where, './scripts/build.sh'), 'server'], where);
  await runSystemCommand('cp', ['-v', path.resolve(where, './otoroshi/target/scala-2.12/otoroshi.jar'), path.resolve(where, releaseDir)], where);
  await runSystemCommand('cp', ['-v', path.resolve(where, `./otoroshi/target/universal/otoroshi-${version}.zip`),  path.resolve(where, releaseDir)], where);
}


async function publishDockerOtoroshi(location, version) {
  await runSystemCommand('cp', [path.resolve(location, `./otoroshi/target/universal/otoroshi-${version}.zip`), path.resolve(location, `./docker/build/otoroshi-dist.zip`)], location);
  await runSystemCommand('sh', [path.resolve(location, `./docker/build/build.sh`), 'push-all', version], path.resolve(location, `./docker/build`));
}

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

async function publishDockerDemo(location, version) {
  await runScript(`
    cd $LOCATION/docker/demo
    docker build --no-cache -t otoroshi-demo .
    docker tag otoroshi-demo "maif/otoroshi-demo:$VERSION" 
    docker tag otoroshi-demo "maif/otoroshi-demo:latest"
    docker push "maif/otoroshi-demo:$VERSION"
    docker push "maif/otoroshi-demo:latest"
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

async function githubTag(location, version) {
  await runSystemCommand('git', ['commit', '-am', `Prepare the release of Otoroshi version ${version}`], location);
  await runSystemCommand('git', ['tag', '-am', `Release Otoroshi version ${version}`, version], location);
}

async function pushToBintray(location, version) {
  await runScript(`
    curl -T "$LOCATION/release-$VERSION/otoroshi.jar" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi.jar' "https://api.bintray.com/content/maif/binaries/otoroshi.jar/$VERSION/otoroshi.jar"
    curl -T "$LOCATION/release-$VERSION/otoroshi-$VERSION.zip" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi-dist' "https://api.bintray.com/content/maif/binaries/otoroshi-dist/$VERSION/otoroshi-dist.zip"
    curl -T "$LOCATION/release-$VERSION/linux-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: linux-otoroshicli' "https://api.bintray.com/content/maif/binaries/linux-otoroshicli/$VERSION/otoroshicli"
    # curl -T "$LOCATION/release-$VERSION/mac-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: mac-otoroshicli' "https://api.bintray.com/content/maif/binaries/mac-otoroshicli/$VERSION/otoroshicli"
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

async function publishSbt(location, version) {
  await runScript(`
    cd $LOCATION/otoroshi
    export JAVA_HOME=$JDK8_HOME
    export PATH=\${JAVA_HOME}/bin:\${PATH}
    sbt publish
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

async function createGithubRelease(version) {
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
  });
}

async function installDependencies(location) {
  await runSystemCommand('yarn', ['install'], path.resolve(location, './demos/loadbalancing'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './demos/snowmonkey'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/clevercloud'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/common'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/kubernetes'));
  await runSystemCommand('yarn', ['install'], path.resolve(location, './connectors/rancher'));
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

async function releaseOtoroshi(from, to, next, last, location, dryRun) {
  console.log(`Releasing Otoroshi from version '${from}' to version '${to}'/'${next}' (${location})`);
  console.log(`Don't forget to set JAVA_HOME to JDK8_HOME and to docker login`);
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
    steps = fs.readFileSync(releaseFile, 'utf8').split('\n').map(line => JSON.parse(line));
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
    await runSystemCommand('git', ['commit', '-am', `Update version to ${next}`], location);
  });
  await ensureStep('BUILD_OTOROSHI', releaseFile, () => buildVersion(to, location, releaseDir));
  await ensureStep('BUILD_LINUX_CLI', releaseFile, () => buildLinuxCLI(location, to));
  if (!dryRun) {
    await ensureStep('CREATE_GITHUB_RELEASE', releaseFile, () => createGithubRelease(to));
    await ensureStep('CREATE_GITHUB_TAG', releaseFile, () => githubTag(location, to));
    await ensureStep('PUSH_TO_BINTRAY', releaseFile, () => pushToBintray(location, to));
    await ensureStep('PUBLISH_LIBRARIES', releaseFile, () => publishSbt(location, to));
    await ensureStep('PUBLISH_DOCKER_OTOROSHI', releaseFile, () => publishDockerOtoroshi(location, to));
    await ensureStep('PUBLISH_DOCKER_OTOROSHI_CLI', releaseFile, () => publishDockerCli(location, to));
    await ensureStep('PUBLISH_DOCKER_OTOROSHI_DEMO', releaseFile, () => publishDockerDemo(location, to));
    await ensureStep('CHANGE_TO_DEV_VERSION', releaseFile, () => changeVersion(location, to, next));
    await ensureStep('PUSH_TO_GITHUB', releaseFile, async () => {
      await runSystemCommand('git', ['commit', '-am', `Update version to ${next}`], location);
      await runSystemCommand('git', ['push', 'origin', 'master'], location);
      await runSystemCommand('git', ['push', '--tags'], location);
    });
  }
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

releaseOtoroshi(releaseFrom, releaseTo, releaseNext, releaseLast, location, dryRun);
