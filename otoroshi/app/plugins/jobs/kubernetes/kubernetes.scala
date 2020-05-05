package otoroshi.plugins.jobs.kubernetes

// TODO: watch res to trigger sync
// TODO: review crds filter by provider, match on kube-path
// TODO: Ajouter openapi aux crd
// TODO: Customizers spécifique par type
// TODO: Csr dans la ressource cert placée en meta + caDN, génération à la volée, matchs sur le hash de la config csr
// TODO: Doc
// TODO: Tester un deploy en vrai pour une démo

// https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/
// https://github.com/containous/traefik-helm-chart/tree/master/traefik/crds
// https://github.com/containous/traefik/blob/v1.7.24/examples/k8s/traefik-deployment.yaml
// https://docs.traefik.io/v1.7/configuration/backends/kubernetes/
// https://github.com/helm/charts/blob/master/stable/traefik/values.yaml
// https://docs.traefik.io/v1.7/user-guide/kubernetes/#deploy-traefik-using-helm-chart
// https://kubernetes.io/fr/docs/concepts/services-networking/ingress/
// https://kubernetes.io/fr/docs/concepts/services-networking/service/
// https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.17/#servicespec-v1-core
// https://kubernetes.io/fr/docs/concepts/services-networking/ingress/


