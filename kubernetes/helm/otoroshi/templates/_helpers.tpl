{{/*
Expand the name of the chart.
*/}}
{{- define "otoroshi.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "otoroshi.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "otoroshi.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "otoroshi.labels" -}}
helm.sh/chart: {{ include "otoroshi.chart" . }}
{{ include "otoroshi.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "otoroshi.selectorLabels" -}}
app.kubernetes.io/name: {{ include "otoroshi.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "otoroshi.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "otoroshi.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Pod-level scheduling and security fields, emitted under `spec.template.spec`.
Empty values are skipped so the rendered manifest stays clean.
*/}}
{{- define "otoroshi.podSchedulingFields" -}}
{{- with .Values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.topologySpreadConstraints }}
topologySpreadConstraints:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- if .Values.priorityClassName }}
priorityClassName: {{ .Values.priorityClassName | quote }}
{{- end }}
{{- with .Values.podSecurityContext }}
securityContext:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Pod-template annotations that trigger a rolling restart when watched secrets change.
Emits (when applicable):
  - checksum/admin-secret: hash of the rendered secret.yaml (chart-managed creds) OR
                           hash of the user-provided existingSecret's .data (via lookup).
  - checksum/redis-password: hash of the bundled bitnami redis Secret's .data (via lookup).
                             Only emitted on `helm upgrade` (lookup is empty on first install,
                             which is fine since pods are being created fresh anyway).
The output is one annotation per line; the caller is responsible for the surrounding indent (nindent).
*/}}
{{- define "otoroshi.checksumAnnotations" -}}
{{- $annots := list -}}
{{- if .Values.env.existingSecret -}}
  {{- $existing := lookup "v1" "Secret" .Release.Namespace .Values.env.existingSecret -}}
  {{- if and $existing $existing.data -}}
    {{- $annots = append $annots (printf "checksum/admin-secret: %s" ($existing.data | toJson | sha256sum)) -}}
  {{- end -}}
{{- else -}}
  {{- $annots = append $annots (printf "checksum/admin-secret: %s" (include (print .Template.BasePath "/secret.yaml") . | sha256sum)) -}}
{{- end -}}
{{- if and .Values.redis.deploy .Values.redis.auth.enabled (not .Values.env.redisPasswordExistingSecret.name) (not .Values.env.redisPassword) -}}
  {{- $redisSecretName := default (printf "%s-redis" .Release.Name) .Values.redis.auth.existingSecret -}}
  {{- $redisSecret := lookup "v1" "Secret" .Release.Namespace $redisSecretName -}}
  {{- if and $redisSecret $redisSecret.data -}}
    {{- $annots = append $annots (printf "checksum/redis-password: %s" ($redisSecret.data | toJson | sha256sum)) -}}
  {{- end -}}
{{- end -}}
{{- join "\n" $annots -}}
{{- end }}

{{/*
Resolve the REDIS_PASSWORD env var entry for the Otoroshi container.
Resolution order:
  1. user-provided existing Secret (env.redisPasswordExistingSecret.name)
  2. inline value (env.redisPassword)
  3. bundled bitnami/redis subchart Secret when redis.deploy=true AND redis.auth.enabled=true
       (or when user supplied redis.auth.existingSecret)
  4. nothing — assumes passwordless Redis
The output is a list item starting with `- name: REDIS_PASSWORD`, ready to be appended
to a container `env:` block. Caller is responsible for the surrounding indent (use nindent).
*/}}
{{- define "otoroshi.redisPasswordEnv" -}}
{{- if .Values.env.redisPasswordExistingSecret.name -}}
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.env.redisPasswordExistingSecret.name }}
      key:  {{ default "redis-password" .Values.env.redisPasswordExistingSecret.key }}
{{- else if .Values.env.redisPassword -}}
- name: REDIS_PASSWORD
  value: {{ .Values.env.redisPassword | quote }}
{{- else if and .Values.redis.deploy .Values.redis.auth.enabled -}}
{{- $secretName := default (printf "%s-redis" .Release.Name) .Values.redis.auth.existingSecret -}}
{{- $secretKey := default "redis-password" .Values.redis.auth.existingSecretPasswordKey -}}
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secretName }}
      key:  {{ $secretKey }}
{{- end -}}
{{- end }}

{{/*
Resolve the external Service type. Honors `loadbalancer.type` first; falls back
to the deprecated `loadbalancer.enabled` boolean (true → LoadBalancer, false → NodePort)
for backward compatibility; defaults to LoadBalancer.
*/}}
{{- define "otoroshi.loadBalancerType" -}}
{{- $t := "LoadBalancer" -}}
{{- if hasKey .Values.loadbalancer "enabled" -}}
  {{- if not .Values.loadbalancer.enabled -}}{{- $t = "NodePort" -}}{{- end -}}
{{- end -}}
{{- if .Values.loadbalancer.type -}}{{- $t = .Values.loadbalancer.type -}}{{- end -}}
{{- $t -}}
{{- end }}

{{/*
Resolve the chart-managed admin credentials, preserving values from any existing Secret
so they stay stable across `helm upgrade`. Returns a dict with: password, clientId, clientSecret, otoroshiSecret.
*/}}
{{- define "otoroshi.adminCredentials" -}}
{{- $secretName := printf "%s-admin-secret" .Values.name -}}
{{- $existing := (lookup "v1" "Secret" .Release.Namespace $secretName) -}}
{{- $password := .Values.env.password -}}
{{- $clientId := .Values.env.clientId -}}
{{- $clientSecret := .Values.env.clientSecret -}}
{{- $otoroshiSecret := .Values.env.secret -}}
{{- if and $existing $existing.data -}}
  {{- if and (not $password) (hasKey $existing.data "password") }}{{- $password = (index $existing.data "password" | b64dec) -}}{{- end -}}
  {{- if and (not $clientId) (hasKey $existing.data "clientId") }}{{- $clientId = (index $existing.data "clientId" | b64dec) -}}{{- end -}}
  {{- if and (not $clientSecret) (hasKey $existing.data "clientSecret") }}{{- $clientSecret = (index $existing.data "clientSecret" | b64dec) -}}{{- end -}}
  {{- if and (not $otoroshiSecret) (hasKey $existing.data "otoroshiSecret") }}{{- $otoroshiSecret = (index $existing.data "otoroshiSecret" | b64dec) -}}{{- end -}}
{{- end -}}
{{- if not $password }}{{- $password = randAlphaNum 32 -}}{{- end -}}
{{- if not $clientId }}{{- $clientId = randAlphaNum 16 -}}{{- end -}}
{{- if not $clientSecret }}{{- $clientSecret = randAlphaNum 32 -}}{{- end -}}
{{- if not $otoroshiSecret }}{{- $otoroshiSecret = randAlphaNum 64 -}}{{- end -}}
{{- $creds := dict "password" $password "clientId" $clientId "clientSecret" $clientSecret "otoroshiSecret" $otoroshiSecret -}}
{{- $creds | toJson -}}
{{- end }}
