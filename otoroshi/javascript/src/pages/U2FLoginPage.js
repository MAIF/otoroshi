import React, { Component } from "react";

function Base64Url() {
  let chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  // Use a lookup table to find the index.
  let lookup = new Uint8Array(256);
  for (let i = 0; i < chars.length; i++) {
    lookup[chars.charCodeAt(i)] = i;
  }

  let encode = function (arraybuffer) {
    let bytes = new Uint8Array(arraybuffer),
      i,
      len = bytes.length,
      base64url = "";

    for (i = 0; i < len; i += 3) {
      base64url += chars[bytes[i] >> 2];
      base64url += chars[((bytes[i] & 3) << 4) | (bytes[i + 1] >> 4)];
      base64url += chars[((bytes[i + 1] & 15) << 2) | (bytes[i + 2] >> 6)];
      base64url += chars[bytes[i + 2] & 63];
    }

    if (len % 3 === 2) {
      base64url = base64url.substring(0, base64url.length - 1);
    } else if (len % 3 === 1) {
      base64url = base64url.substring(0, base64url.length - 2);
    }

    return base64url;
  };

  let decode = function (base64string) {
    let bufferLength = base64string.length * 0.75,
      len = base64string.length,
      i,
      p = 0,
      encoded1,
      encoded2,
      encoded3,
      encoded4;

    let bytes = new Uint8Array(bufferLength);

    for (i = 0; i < len; i += 4) {
      encoded1 = lookup[base64string.charCodeAt(i)];
      encoded2 = lookup[base64string.charCodeAt(i + 1)];
      encoded3 = lookup[base64string.charCodeAt(i + 2)];
      encoded4 = lookup[base64string.charCodeAt(i + 3)];

      bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
      bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
      bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
    }

    return bytes.buffer;
  };

  return {
    decode: decode,
    encode: encode,
    fromByteArray: encode,
    toByteArray: decode,
  };
}

const base64url = Base64Url();

function responseToObject(response) {
  if (response.u2fResponse) {
    return response;
  } else {
    let clientExtensionResults = {};

    try {
      clientExtensionResults = response.getClientExtensionResults();
    } catch (e) {
      console.error("getClientExtensionResults failed", e);
    }

    if (response.response.attestationObject) {
      return {
        type: response.type,
        id: response.id,
        response: {
          attestationObject: base64url.fromByteArray(
            response.response.attestationObject
          ),
          clientDataJSON: base64url.fromByteArray(
            response.response.clientDataJSON
          ),
        },
        clientExtensionResults,
      };
    } else {
      return {
        type: response.type,
        id: response.id,
        response: {
          authenticatorData: base64url.fromByteArray(
            response.response.authenticatorData
          ),
          clientDataJSON: base64url.fromByteArray(
            response.response.clientDataJSON
          ),
          signature: base64url.fromByteArray(response.response.signature),
          userHandle:
            response.response.userHandle &&
            base64url.fromByteArray(response.response.userHandle),
        },
        clientExtensionResults,
      };
    }
  }
}

// Inject styles for animations
const injectStyles = () => {
  if (document.getElementById("otoroshi-admin-login-styles")) return;
  const style = document.createElement("style");
  style.id = "otoroshi-admin-login-styles";
  style.textContent = `
    @keyframes oto-fade-in {
      from { opacity: 0; transform: translateY(8px); }
      to { opacity: 1; transform: translateY(0); }
    }
    @keyframes oto-glow-pulse {
      0%, 100% { opacity: 0.4; }
      50% { opacity: 0.7; }
    }
    @keyframes oto-spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    .oto-login-input::placeholder {
      color: rgba(255, 255, 255, 0.3);
    }
    .oto-login-input:focus {
      border-color: #f9b000;
      box-shadow: 0 0 0 1px #f9b000, 0 0 16px rgba(249, 176, 0, 0.12);
      outline: none;
    }
    .oto-login-btn:hover:not(:disabled) {
      background: #e6a200;
      box-shadow: 0 8px 24px rgba(249, 176, 0, 0.3);
      transform: translateY(-1px);
    }
    .oto-login-btn:active:not(:disabled) {
      transform: translateY(0);
    }
    .oto-login-btn:disabled {
      opacity: 0.7;
      cursor: not-allowed;
    }
    .oto-webauthn-btn:hover:not(:disabled) {
      background: rgba(255, 255, 255, 0.08);
      border-color: rgba(255, 255, 255, 0.35);
    }
    .oto-footer-link:hover {
      color: #f9b000;
    }
  `;
  document.head.appendChild(style);
};

const styles = {
  container: {
    minHeight: "100vh",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    background: "linear-gradient(135deg, #1b1b2f 0%, #162447 50%, #1f4068 100%)",
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif',
    padding: "24px",
    position: "relative",
    overflow: "hidden",
  },
  backgroundOrb: {
    position: "absolute",
    top: "10%",
    left: "50%",
    transform: "translateX(-50%)",
    width: "600px",
    height: "600px",
    background:
      "radial-gradient(circle, rgba(249, 176, 0, 0.06) 0%, transparent 70%)",
    pointerEvents: "none",
    animation: "oto-glow-pulse 4s ease-in-out infinite",
  },
  content: {
    position: "relative",
    zIndex: 1,
    width: "100%",
    maxWidth: "400px",
    animation: "oto-fade-in 0.5s ease-out",
  },
  brandSection: {
    textAlign: "center",
    marginBottom: "32px",
  },
  logo: {
    height: "120px",
    width: "auto",
  },
  card: {
    background: "rgba(0, 0, 0, 0.35)",
    backdropFilter: "blur(20px)",
    border: "1px solid rgba(255, 255, 255, 0.1)",
    borderRadius: "12px",
    padding: "32px",
    boxShadow: "0 25px 50px -12px rgba(0, 0, 0, 0.4)",
  },
  cardHeader: {
    marginBottom: "28px",
    textAlign: "center",
  },
  title: {
    margin: "0 0 8px",
    color: "#fff",
    fontSize: "24px",
    fontWeight: "700",
    letterSpacing: "-0.02em",
  },
  subtitle: {
    margin: 0,
    color: "rgba(255, 255, 255, 0.6)",
    fontSize: "14px",
    lineHeight: "1.5",
  },
  form: {
    display: "flex",
    flexDirection: "column",
    gap: "20px",
  },
  inputGroup: {
    display: "flex",
    flexDirection: "column",
    gap: "8px",
  },
  label: {
    color: "rgba(255, 255, 255, 0.7)",
    fontSize: "13px",
    fontWeight: "500",
  },
  input: {
    width: "100%",
    padding: "12px 14px",
    fontSize: "14px",
    color: "#fff",
    backgroundColor: "rgba(0, 0, 0, 0.3)",
    border: "1px solid rgba(255, 255, 255, 0.12)",
    borderRadius: "8px",
    outline: "none",
    transition: "all 0.2s ease",
    boxSizing: "border-box",
  },
  errorContainer: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
    padding: "12px 14px",
    backgroundColor: "rgba(220, 38, 38, 0.1)",
    border: "1px solid rgba(220, 38, 38, 0.2)",
    borderRadius: "8px",
    color: "#fca5a5",
    fontSize: "13px",
  },
  errorIcon: {
    width: "16px",
    height: "16px",
    flexShrink: 0,
    color: "#f87171",
  },
  successContainer: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
    padding: "12px 14px",
    backgroundColor: "rgba(16, 185, 129, 0.1)",
    border: "1px solid rgba(16, 185, 129, 0.2)",
    borderRadius: "8px",
    color: "#6ee7b7",
    fontSize: "13px",
  },
  successIcon: {
    width: "16px",
    height: "16px",
    flexShrink: 0,
    color: "#10b981",
  },
  buttonGroup: {
    display: "flex",
    flexDirection: "column",
    gap: "12px",
    marginTop: "4px",
  },
  button: {
    width: "100%",
    padding: "12px 20px",
    fontSize: "14px",
    fontWeight: "700",
    color: "#1b1b1d",
    background: "#f9b000",
    border: "none",
    borderRadius: "8px",
    cursor: "pointer",
    transition: "all 0.2s ease",
    boxShadow: "0 4px 14px rgba(249, 176, 0, 0.2)",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
  },
  webauthnButton: {
    width: "100%",
    padding: "12px 20px",
    fontSize: "14px",
    fontWeight: "500",
    color: "rgba(255, 255, 255, 0.7)",
    background: "transparent",
    border: "1px solid rgba(255, 255, 255, 0.2)",
    borderRadius: "8px",
    cursor: "pointer",
    transition: "all 0.2s ease",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
  },
  spinner: {
    width: "18px",
    height: "18px",
    animation: "oto-spin 1s linear infinite",
  },
  spinnerTrack: {
    opacity: 0.2,
  },
  spinnerHead: {
    opacity: 1,
  },
  footer: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
    marginTop: "32px",
    color: "rgba(255, 255, 255, 0.35)",
    fontSize: "12px",
  },
  footerLink: {
    color: "rgba(255, 255, 255, 0.5)",
    textDecoration: "none",
    transition: "color 0.2s ease",
  },
};

export class U2FLoginPage extends Component {
  state = {
    email: "",
    password: "",
    error: null,
    message: null,
    loading: false,
  };

  componentDidMount() {
    injectStyles();
  }

  onChange = (e) => {
    this.setState({ [e.target.name]: e.target.value, error: null });
  };

  handleError = (mess, t) => {
    return (err) => {
      console.log(err && err.message ? err.message : err);
      this.setState({
        error: err && err.message ? err.message : err,
        loading: false,
      });
      throw err;
    };
  };

  simpleLogin = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    this.setState({ message: null, loading: true, error: null });
    return fetch(`/bo/simple/login`, {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        username,
        password,
      }),
    }).then((r) => {
      if (r && r.ok) {
        this.setState({ message: "Login successful, redirecting..." });
        window.location.href = "/bo/dashboard";
      } else {
        this.webAuthnLogin();
      }
    }, this.handleError("Invalid username or password"));
  };

  webAuthnLogin = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    const label = this.state.label;
    this.setState({
      message: "Waiting for security key...",
      loading: true,
      error: null,
    });
    fetch(`/bo/webauthn/login/start`, {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        username,
        password,
        label,
        origin: window.location.origin,
      }),
    })
      .then((r) => {
        if (r.status === 200 || r.status == 201) {
          return r.json();
        } else {
          throw new Error("Invalid username or password");
        }
      }, this.handleError("Invalid username or password"))
      .then((payload) => {
        const requestId = payload.requestId;
        const options = payload.request.publicKeyCredentialRequestOptions;
        options.challenge = base64url.decode(options.challenge);
        options.allowCredentials = options.allowCredentials.map((c) => {
          c.id = base64url.decode(c.id);
          return c;
        });
        return navigator.credentials
          .get(
            {
              publicKey: options,
            },
            this.handleError("WebAuthn authentication failed")
          )
          .then((credentials) => {
            const json = responseToObject(credentials);
            return fetch(`/bo/webauthn/login/finish`, {
              method: "POST",
              credentials: "include",
              headers: {
                Accept: "application/json",
                "Content-Type": "application/json",
              },
              body: JSON.stringify({
                requestId,
                webauthn: json,
                otoroshi: {
                  origin: window.location.origin,
                  username,
                  password,
                },
              }),
            })
              .then((r) => r.json(), this.handleError("Authentication failed"))
              .then((data) => {
                this.setState(
                  {
                    error: null,
                    email: "",
                    password: "",
                    message: "Login successful, redirecting...",
                    loading: false,
                  },
                  () => {
                    window.location.href = "/bo/dashboard";
                  }
                );
              }, this.handleError("Login failed"));
          });
      }, this.handleError("Authentication failed"));
  };

  render() {
    const { email, password, error, message, loading } = this.state;
    const canSubmit = email && password && !loading;

    return (
      <div style={styles.container}>
        {/* Background glow */}
        <div style={styles.backgroundOrb} />

        <div style={styles.content}>
          {/* Logo */}
          <div style={styles.brandSection}>
            <img
              src={this.props.otoroshiLogo}
              style={styles.logo}
              alt="Otoroshi"
            />
          </div>

          {/* Login card */}
          <div style={styles.card}>
            <div style={styles.cardHeader}>
              <h1 style={styles.title}>Admin console</h1>
              <p style={styles.subtitle}>Sign in to access Otoroshi</p>
            </div>

            <form onSubmit={this.simpleLogin} style={styles.form}>
              <div style={styles.inputGroup}>
                <label style={styles.label}>Username</label>
                <input
                  type="text"
                  name="email"
                  className="oto-login-input"
                  style={styles.input}
                  value={email}
                  onChange={this.onChange}
                  placeholder="admin@otoroshi.io"
                  autoFocus
                  disabled={loading}
                  autoComplete="username"
                />
              </div>

              <div style={styles.inputGroup}>
                <label style={styles.label}>Password</label>
                <input
                  type="password"
                  name="password"
                  className="oto-login-input"
                  style={styles.input}
                  value={password}
                  onChange={this.onChange}
                  placeholder="Enter your password"
                  disabled={loading}
                  autoComplete="current-password"
                />
              </div>

              {error && (
                <div style={styles.errorContainer}>
                  <svg
                    style={styles.errorIcon}
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                      clipRule="evenodd"
                    />
                  </svg>
                  <span>{error}</span>
                </div>
              )}

              {message && !error && (
                <div style={styles.successContainer}>
                  <svg
                    style={styles.successIcon}
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                      clipRule="evenodd"
                    />
                  </svg>
                  <span>{message}</span>
                </div>
              )}

              <div style={styles.buttonGroup}>
                <button
                  type="submit"
                  className="oto-login-btn"
                  style={styles.button}
                  disabled={!canSubmit}
                >
                  {loading ? (
                    <>
                      <svg
                        style={styles.spinner}
                        viewBox="0 0 24 24"
                        fill="none"
                      >
                        <circle
                          style={styles.spinnerTrack}
                          cx="12"
                          cy="12"
                          r="10"
                          stroke="currentColor"
                          strokeWidth="3"
                        />
                        <path
                          style={styles.spinnerHead}
                          d="M12 2a10 10 0 0110 10"
                          stroke="currentColor"
                          strokeWidth="3"
                          strokeLinecap="round"
                        />
                      </svg>
                      Signing in...
                    </>
                  ) : (
                    <>
                      Sign in
                      <svg
                        width="16"
                        height="16"
                        viewBox="0 0 20 20"
                        fill="currentColor"
                      >
                        <path
                          fillRule="evenodd"
                          d="M10.293 3.293a1 1 0 011.414 0l6 6a1 1 0 010 1.414l-6 6a1 1 0 01-1.414-1.414L14.586 11H3a1 1 0 110-2h11.586l-4.293-4.293a1 1 0 010-1.414z"
                          clipRule="evenodd"
                        />
                      </svg>
                    </>
                  )}
                </button>
              </div>
            </form>

          </div>

          {/* Footer */}
          <div style={styles.footer}>
            <span>Otoroshi API Gateway</span>
            <span style={{ color: "#3f3f46" }}>·</span>
            <a
              href="https://www.otoroshi.io"
              target="_blank"
              rel="noopener noreferrer"
              className="oto-footer-link"
              style={styles.footerLink}
            >
              Documentation
            </a>
          </div>
        </div>
      </div>
    );
  }
}
