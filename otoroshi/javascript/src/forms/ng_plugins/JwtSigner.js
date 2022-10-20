export default {
  id: "cp:otoroshi.next.plugins.JwtSigner",
  config_schema: {
    "fail_if_present": {
      type: "bool",
      props: {
        label: "Fail if present",
        labelColumn: 6
      }
    },
    "replace_if_present": {
      type: "bool",
      props: {
        label: "Replace if present",
        labelColumn: 6
      }
    },
    verifier: {
      label: "verifier",
      type: "JwtVerifierWizard",
      props: {
        buttonText: "Configure verifier"
      }
    }
  },
  config_flow: [
    "verifier",
    {
      type: 'grid',
      name: 'Override',
      fields: [
        "replace_if_present",
        "fail_if_present"
      ]
    }
  ]
}