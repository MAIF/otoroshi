import { JwtVerifierLauncher } from "../wizards/JwtVerifierLauncher";

export default {
  id: "cp:otoroshi.next.plugins.JwtVerificationOnly",
  config_schema: {
    verifier: {
      label: "Verifier",
      type: "JwtVerifierWizard",
      props: {
        componentLauncher: JwtVerifierLauncher,
        componentsProps: {
          allowedStrategy: 'PassThrough'
        }
      }
    },
    fail_if_absent: {
      type: "box-bool",
      label: "Fail if absent",
      props: {
        description: 'If a token is present in the incoming request, the call will fail.'
      }
    }
  },
  config_flow: [
    "fail_if_absent",
    "verifier"
  ]
}