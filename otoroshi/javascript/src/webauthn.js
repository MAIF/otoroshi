// Only one webauthn ceremony can be pending in a browser: while one is running, any new call to
// `navigator.credentials` is rejected with "A request is already pending.". Two things leave a
// ceremony pending: this page (a modal closed while the browser dialog is open) and the browser
// itself, which keeps its dialog alive after our promise has been resolved (chrome asking where to
// save the passkey it just created). We can only do something about the first one, so we keep the
// controller of the last ceremony started here and abort it before starting a new one, and we retry
// for a while when the browser is still busy with its own dialog.
const RETRY_DELAYS = [400, 800, 1600, 2400, 3200];

let currentCeremony = null;

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// the message is the only reliable part: chrome raises an OperationError, firefox a NotAllowedError
function isBrowserBusy(err) {
  return err && /already pending|already in progress/i.test(err.message || '');
}

function ceremony(kind, publicKey) {
  const previous = currentCeremony;
  if (previous) {
    previous.abort();
  }
  const controller = new AbortController();
  currentCeremony = controller;
  const settled = () => {
    if (currentCeremony === controller) {
      currentCeremony = null;
    }
  };
  // this ceremony has been superseded by a new one: there is nothing left to do here and nothing to
  // report to the user, the new ceremony owns the flow now. An aborted ceremony can either reject or
  // resolve with no credentials depending on the browser
  const superseded = () => new Promise(() => {});
  const attempt = (retries) =>
    navigator.credentials[kind]({ publicKey, signal: controller.signal }).then(
      (credentials) => {
        if (!credentials || controller.signal.aborted) return superseded();
        settled();
        return credentials;
      },
      (err) => {
        if (controller.signal.aborted) return superseded();
        if (isBrowserBusy(err) && retries.length > 0) {
          // the browser is still busy with the dialog of a previous ceremony, give it some time:
          // once that dialog is closed, this ceremony goes on as if nothing happened
          return wait(retries[0]).then(() => attempt(retries.slice(1)));
        }
        settled();
        if (isBrowserBusy(err)) {
          throw new Error(
            'The browser is still busy with a previous passkey dialog. Close it and try again, or reload the page.'
          );
        }
        throw err;
      }
    );
  // let the browser tear down the ceremony we just aborted before asking for a new one
  return previous ? wait(50).then(() => attempt(RETRY_DELAYS)) : attempt(RETRY_DELAYS);
}

// starts a registration ceremony, `publicKey` being a PublicKeyCredentialCreationOptions
export function createCredentials(publicKey) {
  return ceremony('create', publicKey);
}

// starts an authentication ceremony, `publicKey` being a PublicKeyCredentialRequestOptions
export function getCredentials(publicKey) {
  return ceremony('get', publicKey);
}

// closes the browser dialog of the pending ceremony if there is one. Called when leaving the screen
// that started it, so that a ceremony is never left pending behind a closed modal
export function abortCeremony() {
  if (currentCeremony) {
    currentCeremony.abort();
    currentCeremony = null;
  }
}
