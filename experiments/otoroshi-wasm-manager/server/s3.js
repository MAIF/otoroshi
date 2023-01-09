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

module.exports = {
  S3: {
    initializeS3Connection,
    'state': () => state
  }
}