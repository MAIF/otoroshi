const crypto = require('crypto');
const userHash = email => {
  const salt = process.env.SALT || crypto
    .randomBytes(16)
    .toString('hex');

  return crypto
    .pbkdf2Sync(email, salt, 50, 64, `sha256`)
    .toString(`hex`);
}

module.exports = {
  userHash
}