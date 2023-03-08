const AWS = require('aws-sdk');
const dns = require('dns');
const url = require('url');
const manager = require('./logger');
const log = manager.createLogger('wasm-manager');

let state = {
  s3: undefined,
  Bucket: undefined
}

const initializeS3Connection = () => {
  AWS.config.update({
    accessKeyId: process.env.S3_ACCESS_KEY_ID,
    secretAccessKey: process.env.S3_SECRET_ACCESS_KEY
  });

  if (process.env.DOCKER_USAGE) {
    const URL = url.parse(process.env.S3_ENDPOINT)

    return new Promise(resolve => dns.lookup(URL.hostname, function (err, ip) {
      // console.log(URL)
      log.debug(`${URL.protocol}//${ip}:${URL.port}${URL.pathname}`)
      state = {
        s3: new AWS.S3({
          endpoint: `${URL.protocol}//${ip}:${URL.port}${URL.pathname}`,
          s3ForcePathStyle: process.env.S3_FORCE_PATH_STYLE
        }),
        Bucket: process.env.S3_BUCKET
      }
      resolve()
    }))
  } else {
    state = {
      s3: new AWS.S3({
        endpoint: process.env.S3_ENDPOINT,
      }),
      Bucket: process.env.S3_BUCKET
    }
    return Promise.resolve();
  }
}

const createBucketIfMissing = () => {
  const params = { Bucket: state.Bucket }

  return state.s3.headBucket(params)
    .promise()
    .then(res => {
      if (res.statusCode === 404) {
        return new Promise(resolve => {
          state.s3.createBucket(params, err => {
            if (err) {
              return log.error('err createBucket', err);
            } else {
              resolve()
            }
          });
        })
      }
    })
}

const cleanBucket = () => {
  return new Promise((resolve, reject) => {
    state
      .s3
      .listObjects({
        Bucket: state.Bucket
      }, (err, data) => {
        if (err)
          reject(err)
        else
          Promise.all(data.Contents.map(({ Key }) => {
            return state.s3.deleteObject({
              Bucket: state.Bucket,
              Key
            }, (err, d) => {
              reject(err)
            })
          }))
            .then(resolve)
      })
  })
}

const listObjects = () => {
  state.s3
    .listObjects({
      Bucket: state.Bucket
    }, (err, data) => {
      if (err)
        console.log(err)
      else
        console.log(data)
    })
}

module.exports = {
  S3: {
    initializeS3Connection,
    createBucketIfMissing,
    cleanBucket,
    listObjects,
    'state': () => state
  }
}