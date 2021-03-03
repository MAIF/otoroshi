const fs = require('fs');
const path = require("path");

function walkSync(dir, recursive, filelist = []) {
  // console.log("walking through", dir, "found", filelist.length, "files so far.");
  try {
    let files = fs.readdirSync(dir, { encoding: 'utf8', withFileTypes: true });
    files.forEach((file) => {
      if (file.isDirectory()) {
        // console.log(dir + '/' + file.name, "is a directory")
        if (recursive) {
          try {
            walkSync(dir + '/' + file.name, recursive, filelist);
          } catch(ex) {
            // console.log(ex)
          }
        }
      } else {
        // console.log('adding', dir + '/' + file.name)
        const p = path.parse(file.name);
        if (p.ext === '.scala') {
          filelist.push({ path: `${dir}/${file.name}`, name: file.name, dir });
       }
      }
    });
    return filelist;
  } catch(ex) {
    // console.log(ex)
    return filelist;
  }
}; 

//console.log(__dirname, process.cwd())

const files = walkSync(path.join(process.cwd(), 'otoroshi', 'app'), true, []);
const packages = {};
const packagesFirstLevel = {};

const lines = files.map(file => {
  const content = fs.readFileSync(file.path).toString('utf8').split('\n');
  const firstLine = content[0];
  if (firstLine.indexOf('package') === 0) {
    const package = firstLine.replace("package ", '').trim();
    packages[package] = '';
    if (package.indexOf('.') > -1) {
      packagesFirstLevel[package.split('.')[0]] = '';
    } else {
      packagesFirstLevel[package] = '';
    }
    if (firstLine.indexOf('package otoroshi') < 0) {
      return `// app${file.path.replace(process.cwd(), '')}\n${firstLine}\n`;
    } else {
      return '';
    }
  } else {
    return '';
  }
}).filter(i => i.length > 0)

console.log(lines.join('\n'))
console.log(files.length, lines.length / 3)
console.log(Object.keys(packagesFirstLevel))
