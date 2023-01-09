const AWS = require('aws-sdk');

AWS.config.update({
  accessKeyId: 'J11Q131JBRSOXFEOIHR8',
  secretAccessKey: 'AENGXRKsfd2snKwQaH9xRVZDDKRrCXVjhctZWaRn'
});

const s3 = new AWS.S3({
  endpoint: 'cellar-c2.services.clever-cloud.com'
});

const bucketParams = {
  Bucket: 'wasm-manager'
};

s3.listObjects(bucketParams, function (err, res) {
  console.log(err, res)
});