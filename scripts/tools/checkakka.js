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

const files = walkSync(path.join(process.cwd(), 'otoroshi', 'app'), true, []);
const results = [];

files.map(file => {
  const lines = fs.readFileSync(file.path).toString('utf8').split('\n');
  const imports = lines.filter(line => line.trim().indexOf('import akka.') === 0).map(line => line.trim())
  if (imports.length > 0) results.push({ file: file.path, imports })
})

console.log(results.length, 'files')
console.log(JSON.stringify(results, null, 2))
console.log(results.length, 'files', 'on', files.length, 'files')

