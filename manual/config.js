const fs = require('fs');

const src = __dirname + '/src/main/paradox/snippets/reference.conf';
const dest = __dirname + '/src/main/paradox/snippets/reference-env.conf';

const content = fs.readFileSync(src).toString('utf8');
const finalContent = content.split('\n').filter(l => l.indexOf('{') > -1 || l.indexOf('}') > -1 || l.indexOf('${?') > -1).join('\n');
fs.writeFileSync(dest, finalContent);

const finalContent2 = content.split('\n').filter(l => l.indexOf('include') !== 0).join('\n');
fs.writeFileSync(src, finalContent2);
