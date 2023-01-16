
const missingCredentials = res => {
  res
    .status(401)
    .json({
      error: 'missing credentials'
    })
}

const extractUserFromQuery = (req, res, next) => {
  const jwtUser = req.headers[process.env.OTOROSHI_USER_HEADER] || req.headers['otoroshi-user']
  if (jwtUser) {
    try {
      const decodedToken = JSON.parse(Buffer.from(jwtUser.split('.')[1], 'base64').toString())
      res.append('user', decodedToken)
      next()
    } catch (_) {
      missingCredentials(res)
    }
  } else {
    missingCredentials(res)
  }
}

module.exports = {
  extractUserFromQuery
}