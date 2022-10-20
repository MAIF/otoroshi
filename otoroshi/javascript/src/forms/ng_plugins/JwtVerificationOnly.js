export default {
  id: "cp:otoroshi.next.plugins.JwtVerificationOnly",
  config_schema: {
    verifier: {
      label: "verifier",
      type: "select",
      props: {
        optionsFrom: "/bo/api/proxy/api/verifiers",
        optionsTransformer: {
          label: "name",
          value: "id"
        }
      }
    },
    fail_if_absent: {
      label: "fail_if_absent",
      type: "bool"
    }
  },
  config_flow: [
    "fail_if_absent",
    "verifier"
  ]
}