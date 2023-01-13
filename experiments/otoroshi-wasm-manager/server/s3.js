const AWS = require('aws-sdk');

let state = {
  s3: undefined,
  Bucket: undefined
}

const initializeS3Connection = () => {
  AWS.config.update({
    accessKeyId: process.env.ACCESS_KEY_ID,
    secretAccessKey: process.env.SECRET_ACCESS_KEY
  });

  state = {
    s3: new AWS.S3({
      endpoint: process.env.ENDPOINT
    }),
    Bucket: process.env.BUCKET
  }
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
    cleanBucket,
    listObjects,
    'state': () => state
  }
}