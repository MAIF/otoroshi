
const missingCredentials = res => {
  res
    .status(401)
    .json({
      error: 'missing credentials'
    })
}

const extractUserFromQuery = (req, res, next) => {
  if (process.env.MODE === 'PROD') {
    const jwtUser = req.headers[process.env.OTOROSHI_USER_HEADER] || req.headers['otoroshi-user']
    if (jwtUser) {
      try {
        const decodedToken = JSON.parse(Buffer.from(jwtUser.split('.')[1], 'base64').toString())
        req.user = decodedToken.user
        next()
      } catch (_) {
        missingCredentials(res)
      }
    } else {
      missingCredentials(res)
    }
  } else {
    req.user = { email: 'admin@otoroshi.io' }
    next()
  }
}

module.exports = {
  Security: {
    extractUserFromQuery
  }
}