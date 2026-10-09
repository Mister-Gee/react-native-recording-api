// Android-only: nothing to link on iOS, where the JS API reports `unsupported`.
module.exports = {
  dependency: {
    platforms: {
      ios: null,
    },
  },
};
