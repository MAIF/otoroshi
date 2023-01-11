const manager = require("../logger");
const { S3 } = require("../s3");
const { hash } = require("../utils");

const log = manager.createLogger('[user SERVICE]')

const getUser = req => {
  const state = S3.state()

  const jsonProfile = hash(req.user ? req.user.email : 'admin@otoroshi.io');

  log.info(`getUser ${jsonProfile}`)

  return new Promise(resolve => {
    state.s3.getObject({
      Bucket: state.Bucket,
      Key: `${jsonProfile}.json`
    }, (err, data) => {
      if (err) {
        log.info('getUser', err)
        resolve({})
      }
      try {
        resolve(JSON.parse(data.Body.toString('utf-8')))
      } catch (err) {
        console.log(err)
        resolve({})
      }
    })
  })
}

const updateUser = (req, content) => {
  const state = S3.state()

  const jsonProfile = hash(req.user ? req.user.email : 'admin@otoroshi.io');

  log.info(`updateUser ${jsonProfile}`)

  return new Promise(resolve => {
    state.s3.putObject({
      Bucket: state.Bucket,
      Key: `${jsonProfile}.json`,
      Body: JSON.stringify(content)
    }, (err, data) => {
      if (err) {
        log.info('updateUser', err)
        resolve({
          error: err.code,
          status: err.statusCode
        })
      }
      resolve({
        status: 200
      })
    })
  })
}

module.exports = {
  getUser,
  updateUser
}