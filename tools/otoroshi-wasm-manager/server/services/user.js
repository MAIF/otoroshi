const consumers = require('node:stream/consumers')
const { GetObjectCommand, PutObjectCommand, HeadObjectCommand } = require("@aws-sdk/client-s3");
const manager = require("../logger");
const { S3 } = require("../s3");
const { format } = require("../utils");
const fetch = require('node-fetch');

const log = manager.createLogger('[user SERVICE]')

const getUsers = () => {
  const { s3, Bucket } = S3.state()

  return s3.send(new GetObjectCommand({
    Bucket,
    Key: 'users.json'
  }))
    .then(data => new fetch.Response(data.Body).json())
    .catch(err => console.log(err))
}

const addUser = user => {
  const { s3, Bucket } = S3.state()

  return new Promise((resolve, reject) => {
    s3.send(new GetObjectCommand({
      Bucket,
      Key: 'users.json'
    }))
      .then(data => {
        let users = []
        try {
          users = JSON.parse(data.Body.toString('utf-8'))
        } catch (err) { }

        s3.send(new PutObjectCommand({
          Bucket,
          Key: 'users.json',
          Body: JSON.stringify([
            ...users,
            user
          ])
        }))
          .then(resolve)
      })
      .catch(err => reject(err))
  })
}

const createUserIfNotExists = req => {
  const { s3, Bucket } = S3.state()

  const user = format(req.user.email)

  return new Promise((resolve, reject) => s3.send(new HeadObjectCommand({
    Bucket,
    Key: `${user}.json`
  }))
    .then(() => resolve(true))
    .catch(err => {
      if (err) {
        if (err.Code === 'NoSuchKey') {
          addUser(user)
            .then(resolve)
            .catch(reject)
        } else {
          reject(err)
        }
      } else {
        resolve(true)
      }
    }))
}

const _getUser = key => {
  const { s3, Bucket } = S3.state()

  log.debug(`search user: ${key}`)


  return new Promise(resolve => {
    s3.send(new GetObjectCommand({
      Bucket,
      Key: `${key}.json`
    }))
      .then(data => {
        try {
          if (data && data.Body) {
            consumers.json(data.Body)
              .then(resolve)
          }
          else
            resolve({})
        } catch (err) {
          resolve({})
        }
      })
      .catch(_ => resolve({}))
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
    s3.send(new PutObjectCommand({
      Bucket,
      Key: `${jsonProfile}.json`,
      Body: JSON.stringify(content)
    }))
      .then(_ => resolve({
        status: 200
      }))
      .catch(err => {
        log.info('updateUser', err)
        resolve({
          error: err.Code,
          status: err.$metadata.httpStatusCode
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