const path = require('path');
const fs = require('fs');

const files = [
  { file: './kubernetes/kustomize/overlays/cluster/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  { file: './kubernetes/kustomize/overlays/cluster-baremetal/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  { file: './kubernetes/kustomize/overlays/cluster-baremetal-daemonset/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  { file: './kubernetes/kustomize/overlays/simple/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  { file: './kubernetes/kustomize/overlays/simple-baremetal/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  { file: './kubernetes/kustomize/overlays/simple-baremetal-daemonset/deployment.yaml', replace: (from, to, source) => source.replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`) },
  {
    file: './manual/src/main/paradox/deploy/kubernetes.md',
    replace: (from, to, source) => source.replace(`?ref=v${from}`, `?ref=v${to}`).replace(`maif/otoroshi:${from}`, `maif/otoroshi:${to}`)
  },
  { file: './manual/src/main/paradox/code/openapi.json' },
  { file: './manual/src/main/paradox/getting-started.md' },
  { file: './manual/src/main/paradox/how-to-s/import-export-otoroshi-datastore.md' },
  { file: './manual/src/main/paradox/how-to-s/setup-otoroshi-cluster.md' },
  { file: './manual/src/main/paradox/includes/fetch-and-start.md' },
  { file: './manual/src/main/paradox/includes/initialize.md' },
  { file: './manual/src/main/paradox/index.md' },
  { file: './manual/src/main/paradox/install/get-otoroshi.md' },
  { file: './manual/src/main/paradox/topics/expression-language.md' },
  { file: './manual/src/main/paradox/snippets/build.gradle' },
  { file: './manual/src/main/paradox/snippets/build.sbt' },
  { file: './manual/src/main/paradox/snippets/fetch.sh' },
];

function changeVersion(where, from, to, exclude = []) {
  console.log(`Changing version from '${from}' to '${to}'`)
  files.filter(f => !(exclude.indexOf(f.file) > -1)).map(file => {
    const filePath = path.resolve(where, file.file);
    const content = fs.readFileSync(filePath, 'utf8');
    console.log('Changing version in', filePath);
    const replace = file.replace || ((f, t, s) => s.replace(new RegExp(f.replace(new RegExp('\\.', 'g'), '\\.'), 'g'), t));
    const newContent = replace(from, to, content);
    fs.writeFileSync(filePath, newContent);
  });
}

changeVersion(process.env.WHERE, process.env.VERSION_FROM, process.env.VERSION_TO)
