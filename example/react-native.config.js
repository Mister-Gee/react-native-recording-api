const path = require('path');
const pkg = require('../package.json');

module.exports = {
  dependencies: {
    [pkg.name]: {
      root: path.join(__dirname, '..'),
      platforms: {
        // Android-only library: nothing to link on iOS.
        ios: null,
        android: {},
      },
    },
  },
};
