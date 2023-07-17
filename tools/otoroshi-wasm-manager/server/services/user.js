const manager = require("../logger");
const { S3 } = require("../s3");
const { format } = require("../utils");

const log = manager.createLogger('[user SERVICE]')

const getUsers = () => {
  const { s3, Bucket } = S3.state()

  return new Promise(resolve => {
    s3.getObject({
      Bucket,
      Key: 'users.json'
    }, (err, data) => {
      if (err) {
        resolve([])
      } else {
        try {
          resolve(JSON.parse(data.Body.toString('utf-8')))
        } catch (err) {
          console.log(err)
          resolve([])
        }
      }
    })
  })
}

const addUser = user => {
  const { s3, Bucket } = S3.state()

  return new Promise(resolve => {
    s3.getObject({
      Bucket,
      Key: 'users.json'
    }, (err, data) => {
      let users = []
      if (!err) {
        try {
          users = JSON.parse(data.Body.toString('utf-8'))
        } catch (err) { }
      }

      s3.putObject({
        Bucket,
        Key: 'users.json',
        Body: JSON.stringify([
          ...users,
          user
        ])
      }, resolve)
    })
  })
}

const createUserIfNotExists = req => {
  const { s3, Bucket } = S3.state()

  const user = format(req.user.email)

  return new Promise((resolve, reject) => {
    s3.getObject({
      Bucket,
      Key: `${user}.json`
    }, (err, data) => {
      if (err) {
        if (err.code === 'NoSuchKey') {
          addUser(user)
            .then(resolve)
        } else {
          reject(err)
        }
      } else {
        resolve(true)
      }
    })
  })
}

const _getUser = key => {
  const { s3, Bucket } = S3.state()

  log.debug(`search user: ${key}`)

  return new Promise(resolve => {
    s3.getObject({
      Bucket,
      Key: `${key}.json`
    }, (err, data) => {
      if (err) {
        resolve({})
      }
      try {
        if (data && data.Body)
          resolve(JSON.parse(data.Body.toString('utf-8')))
        else
          resolve({})
      } catch (err) {
        resolve({})
      }
    })
  })
}

const getUserFromString = _getUser

const getUser = req => {
  const user = format(req.user.email)
  return _getUser(user)
}

const updateUser = (req, content) => {
  const { s3, Bucket } = S3.state()

  const jsonProfile = format(req.user.email);

  // log.info(`updateUser ${jsonProfile}`)

  return new Promise(resolve => {
    s3.putObject({
      Bucket,
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
  UserManager: {
    getUser,
    getUserFromString,
    createUserIfNotExists,
    getUsers,
    updateUser
  }
}