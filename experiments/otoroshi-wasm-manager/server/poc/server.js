const express = require('express');
const app = express();
const bodyParser = require('body-parser');
const { exec } = require("child_process");
const path = require('path');
const fs = require('fs-extra')

app.use(bodyParser.urlencoded());
app.use(bodyParser.json())
app.use(bodyParser.text())

const cloneRepo = async () => {
    const processName = `process-${Date.now()}`;
    fs.copySync(path.join(__dirname, 'process'), path.join(__dirname, processName))
    return processName;
}

const overrideLibCode = (filename, code) => {
    return new Promise(resolve => {
        fs.writeFile(path.join(__dirname, filename, 'src', 'lib.rs'), code, function (err) {
            if (err) {
                resolve({ error: err })
            }

            resolve({})
        })
    });
}

const cargoBuild = (filename, res) => {
    const proc = exec(
        "cargo build --release --target wasm32-unknown-unknown",
        {
            cwd: path.join(__dirname, filename)
        },
        (error, stdout, stderr) => {
            if (error) {
                console.log(`error: ${error.message}`);
            }
            if (stderr) {
                // console.log(`stderr: ${stderr}`);
            }
            console.log('build completed!')
        });

    proc.on('exit', () => {
        new Promise(resolve => {
            fs.copyFile(
                path.join(__dirname, filename, 'target', 'wasm32-unknown-unknown', 'release', 'http_plugin.wasm'),
                path.join(__dirname, 'store', `${filename}.wasm`), err => {
                    fs.removeSync(path.join(__dirname, filename))
                    if (err)
                        resolve(res.json({ error: err }))
                    else
                        resolve(res.json({ message: `You can now use the following http path to otoroshi http://localhost:3000/wasm/${filename}.wasm` }))
                })
        })

        // res.sendFile('http_plugin.wasm', {
        //     root: path.join(__dirname, filename, 'target', 'wasm32-unknown-unknown', 'release')
        // }, err => {
        //     if (err)
        //         console.log(err)
        //     fs.removeSync(path.join(__dirname, filename))
        // })
    })
}

app.post('/', async (req, res) => {

    try {
        const filename = await cloneRepo();

        const overrideError = await overrideLibCode(filename, req.body)
        if (overrideError.error)
            throw err

        await cargoBuild(filename, res)
    } catch (err) {
        res.json({ error: err })
    }
});

app.get('/wasm/:id', (req, res) => {
    res.sendFile(req.params.id, {
        root: path.join(__dirname, 'store')
    }, err => {
        if (err)
            console.log(err)
    })
})

app.get('/', (req, res) => {
    console.log(req.headers)
    res.sendFile(path.join(__dirname, '/index.html'));
})

app.listen(5001, () => console.log('Listening ...'))
