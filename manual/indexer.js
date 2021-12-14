const fs = require('fs');

function walkSync(dir, initDir, filelist = []) {
  var files = fs.readdirSync(dir);
  files.forEach((file) => {
    if (fs.statSync(dir + '/' + file).isDirectory()) {
      filelist = walkSync(dir + '/' + file, initDir, filelist);
    } else {
      if (file.indexOf('.md') > -1) {
        const content = fs.readFileSync(dir + '/' + file).toString('utf8');
        if (content.split('\n')[0].trim().indexOf('#') !== 0) {
          console.log((dir + '/' + file).replace(initDir, ''))
        }
        filelist.push({ 
          name: file, 
          id: (dir + '/' + file).replace(initDir, ''),
          url: (dir + '/' + file).replace(initDir, '').replace('.md', '.html'),
          title: content.split('\n')[0].replace('# ', ''),
          content: content
        });
      }
    }
  });
  return filelist;
}

const src = __dirname + '/src/main/paradox';
const files = walkSync(src, src);
const result = files.filter(f => f.name.endsWith('.md'));
fs.writeFileSync(__dirname + '/src/main/paradox/content.json', JSON.stringify(result));
fs.writeFileSync(__dirname + '/src/main/paradox/content-pretty.json', JSON.stringify(result, null, 2));