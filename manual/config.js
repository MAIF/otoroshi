const fs = require('fs');

const src = __dirname + '/next/docs/snippets/reference.conf';
const dest = __dirname + '/next/docs/snippets/reference-env.conf';

const content = fs.readFileSync(src).toString('utf8');
const finalContent = content.split('\n').filter(l => {
  const isNotUsed = l.indexOf("#not-used") > -1;
  if (!isNotUsed) {
    return l.indexOf('{') > -1 || l.indexOf('}') > -1 || l.indexOf('${?') > -1
  } else {
    return false
  }
}).join('\n');
fs.writeFileSync(dest, finalContent);

const finalContent2 = content.split('\n').filter(l => {
  const isNotUsed = l.indexOf("#not-used") > -1;
  if (!isNotUsed) {
    return l.indexOf('include') !== 0
  } else {
    return false
  }
}).join('\n');
fs.writeFileSync(src, finalContent2);
