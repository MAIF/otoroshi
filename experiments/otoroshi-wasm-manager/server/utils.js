const crypto = require('crypto');
const hash = value => {
  const salt = process.env.SALT || crypto
    .randomBytes(16)
    .toString('hex');

  return crypto
    .pbkdf2Sync(value, salt, 50, 64, `sha256`)
    .toString(`hex`);
}

module.exports = {
  hash
}