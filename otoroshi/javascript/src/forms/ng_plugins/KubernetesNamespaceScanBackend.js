export default {
  id: 'cp:otoroshi.next.plugins.KubernetesNamespaceScanBackend',
  config_schema: {
    namespaces: {
      type: 'array',
      label: 'Namespaces to scan',
      props: {
        description: 'List of Kubernetes namespaces to scan for CRDs',
        placeholder: 'default, kube-system, otoroshi',
      },
    }
  },
  config_flow: [
    'namespaces'
  ],
};