const { S3Client, HeadBucketCommand, CreateBucketCommand, DeleteObjectCommand, ListObjectsCommand } = require('@aws-sdk/client-s3');

const dns = require('dns');
const url = require('url');
const manager = require('./logger');
const { ENV } = require('./configuration');
const log = manager.createLogger('wasm-manager');

let state = {
  s3: undefined,
  Bucket: undefined
}

const configured = () => ENV.S3_BUCKET;

const initializeS3Connection = () => {
  if (ENV.DOCKER_USAGE) {
    const URL = url.parse(ENV.S3_ENDPOINT)
    return new Promise(resolve => dns.lookup(URL.hostname, function (err, ip) {
      log.debug(`${URL.protocol}//${ip}:${URL.port}${URL.pathname}`)
      state = {
        s3: new S3Client({
          region: ENV.S3_REGION,
          endpoint: `${URL.protocol}//${ip}:${URL.port}${URL.pathname}`,
          s3ForcePathStyle: ENV.S3_FORCE_PATH_STYLE
        }),
        Bucket: ENV.S3_BUCKET
      }
      resolve()
    }))
  } else {
    state = {
      s3: new S3Client({
        region: ENV.S3_REGION,
        endpoint: ENV.S3_ENDPOINT,
        s3ForcePathStyle: ENV.S3_FORCE_PATH_STYLE,
      }),
      Bucket: ENV.S3_BUCKET
    }

    console.log("[S3 INITIALIZATION](ok): S3 Bucket initialized");
    return Promise.resolve()
  }
}

const createBucketIfMissing = () => {
  const params = { Bucket: state.Bucket }

  return state.s3.send(new HeadBucketCommand(params))
    .then(() => log.info("Using existing bucket"))
    .catch(res => {
      if (res.httpStatusCode === 404) {
        log.error(`Bucket ${state.Bucket} is missing.`)
        return new Promise(resolve => {
          state.s3.send(new CreateBucketCommand(params), err => {
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
            return state.s3.send(new DeleteObjectCommand({
              Bucket: state.Bucket,
              Key
            }))
              .then(resolve)
              .catch(reject)
          }))
            .then(resolve)
      })
  })
}

const listObjects = () => {
  state.s3.send(new ListObjectsCommand({
    Bucket: state.Bucket
  }))
    .then(console.log)
    .catch(console.log)
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