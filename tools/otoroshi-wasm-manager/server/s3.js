const AWS = require('aws-sdk');
const dns = require('dns');
const url = require('url');
const manager = require('./logger');
const { ENV, STORAGE } = require('./configuration');
const log = manager.createLogger('wasm-manager');

let state = {
  s3: undefined,
  Bucket: undefined
}

const configured = () => ENV.S3_BUCKET;

const initializeS3Connection = () => {
  AWS.config.update({
    accessKeyId: ENV.S3_ACCESS_KEY_ID,
    secretAccessKey: ENV.S3_SECRET_ACCESS_KEY
  });

  if (ENV.DOCKER_USAGE) {
    const URL = url.parse(ENV.S3_ENDPOINT)

    return new Promise(resolve => dns.lookup(URL.hostname, function (err, ip) {
      log.debug(`${URL.protocol}//${ip}:${URL.port}${URL.pathname}`)
      state = {
        s3: new AWS.S3({
          endpoint: `${URL.protocol}//${ip}:${URL.port}${URL.pathname}`,
          s3ForcePathStyle: ENV.S3_FORCE_PATH_STYLE
        }),
        Bucket: ENV.S3_BUCKET
      }
      resolve()
    }))
  } else {
    state = {
      s3: new AWS.S3({
        endpoint: ENV.S3_ENDPOINT,
        s3ForcePathStyle: ENV.S3_FORCE_PATH_STYLE
      }),
      Bucket: ENV.S3_BUCKET
    }

    console.log("[S3 INITIALIZATION](ok): S3 Bucket initialized");
    return Promise.resolve()
  }
}

const createBucketIfMissing = () => {
  const params = { Bucket: state.Bucket }

  return state.s3.headBucket(params)
    .promise()
    .then(() => log.info("Using existing bucket"))
    .catch(res => {
      if (res.statusCode === 404) {
        log.error(`Bucket ${state.Bucket} is missing.`)
        return new Promise(resolve => {
          state.s3.createBucket(params, err => {
            if (err) {
              throw err;
            } else {
              log.info(`Bucket ${state.Bucket} created.`)
              resolve()
            }
          });
        })
      } else {
        return res
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
    configured,
    initializeS3Connection,
    createBucketIfMissing,
    cleanBucket,
    listObjects,
    'state': () => state
  }
}