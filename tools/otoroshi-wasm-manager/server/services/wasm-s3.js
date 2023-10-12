const { S3 } = require("../s3")
const fs = require('fs-extra');
const AdmZip = require('adm-zip');

const { UserManager } = require('./user');
const { GetObjectCommand, PutObjectCommand } = require("@aws-sdk/client-s3");
const fetch = require("node-fetch");


const isAString = variable => typeof variable === 'string' || variable instanceof String;

const putWasmFileToS3 = (wasmFolder) => {
  const { s3, Bucket } = S3.state()

  return new Promise((resolve, reject) => {
    fs.readFile(wasmFolder, (err, data) => {
      if (err)
        reject(err)
      else
        s3.send(new PutObjectCommand({
          Bucket,
          Key: wasmFolder.split('/').slice(-1)[0],
          Body: data
        }))
          .then(resolve)
          .catch(reject)
    })
  })
}


const putBuildLogsToS3 = (logId, logsFolder) => {
  const { s3, Bucket } = S3.state()

  const zip = new AdmZip()
  zip.addLocalFolder(logsFolder, 'logs')

  return new Promise((resolve, reject) => {
    s3.send(new PutObjectCommand({
      Bucket,
      Key: logId,
      Body: zip.toBuffer()
    }))
      .then(resolve)
      .catch(reject)
  })
}

function putWasmInformationsToS3(userMail, pluginId, newHash, generateWasmName) {
  const userReq = {
    user: {
      email: userMail
    }
  }

  return UserManager.getUser(userReq)
    .then(data => UserManager.updateUser(userReq, {
      ...data,
      plugins: data.plugins.map(plugin => {
        if (plugin.pluginId !== pluginId) {
          return plugin;
        }

        let versions = plugin.versions || [];

        // convert legacy array
        if (versions.length > 0 && isAString(versions[0])) {
          versions = versions.map(name => ({ name }))
        }

        const index = versions.findIndex(item => item.name === generateWasmName);
        if (index === -1)
          versions.push({
            name: generateWasmName,
            updated_at: Date.now(),
            creator: userMail
          })
        else {
          versions[index] = {
            ...versions[index],
            updated_at: Date.now(),
            creator: userMail
          }
        }

        return {
          ...plugin,
          last_hash: newHash,
          wasm: generateWasmName,
          versions
        }
      })
    }))
}

function getWasm(Key, res) {
  const { s3, Bucket } = S3.state()

  return new Promise(resolve => {
    s3.send(new GetObjectCommand({
      Bucket,
      Key
    }))
      .then(data => new fetch.Response(data.Body).buffer())
      .then(data => {
        resolve({ content: data });
      })
      .catch(err => {
        resolve({
          error: err.Code,
          status: err.$metadata.httpStatusCode
        })
      });
  })
    .then(({ content, error, status }) => {
      if (error) {
        res.status(status).json({ error, status })
      } else {
        res.attachment(Key);
        res.send(content);
      }
    });
}

module.exports = {
  putWasmFileToS3,
  putBuildLogsToS3,
  putWasmInformationsToS3,
  getWasm
}