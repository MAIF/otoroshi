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
  - checksum/redis-secret: hash of the bundled cloudpirates/redis Secret's .data (via lookup) —
                           captures rotations of the URI / password. Only emitted on
                           `helm upgrade` (lookup is empty on first install, which is fine
                           since pods are being created fresh anyway).
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
{{- if and .Values.redis.deploy .Values.redis.auth.enabled (not .Values.env.redisURL) -}}
  {{- $redisSecretName := default (printf "%s-redis" .Release.Name) .Values.redis.auth.existingSecret -}}
  {{- $redisSecret := lookup "v1" "Secret" .Release.Namespace $redisSecretName -}}
  {{- if and $redisSecret $redisSecret.data -}}
    {{- $annots = append $annots (printf "checksum/redis-secret: %s" ($redisSecret.data | toJson | sha256sum)) -}}
  {{- end -}}
{{- end -}}
{{- join "\n" $annots -}}
{{- end }}

{{/*
Resolve the REDIS_URL env var entry for the Otoroshi container.

Resolution order:
  1. env.redisURL non-empty → use it as an inline value (user passthrough — for
     external/managed Redis, embed credentials in the URL itself; Otoroshi's
     default Lettuce driver only reads the password from the URI, not from a
     separate REDIS_PASSWORD env var).
  2. redis.deploy=true AND redis.auth.enabled=true → sourced from the bundled
     cloudpirates/redis Secret's `uri` key. The subchart computes the full URL
     (`redis://default:<password>@<release>-redis.<ns>.svc:6379`) — this is
     the recommended path because it solves both the service hostname and the
     embedded-password requirement in one shot.
  3. redis.deploy=true AND redis.auth.enabled=false → plain URL pointing at
     the unauthenticated cloudpirates Service (`<release>-redis:6379`). Note:
     auth=false leaves the data plane open to anyone in the cluster — only
     for throwaway tests.
  4. Neither set → no REDIS_URL is emitted; Otoroshi will fail to start.

The output is a list item starting with `- name: REDIS_URL`. Caller controls indent.
*/}}
{{- define "otoroshi.redisURLEnv" -}}
{{- if .Values.env.redisURL -}}
- name: REDIS_URL
  value: {{ .Values.env.redisURL | quote }}
{{- else if and .Values.redis.deploy .Values.redis.auth.enabled -}}
{{- $secretName := default (printf "%s-redis" .Release.Name) .Values.redis.auth.existingSecret -}}
- name: REDIS_URL
  valueFrom:
    secretKeyRef:
      name: {{ $secretName }}
      key: uri
{{- else if .Values.redis.deploy -}}
- name: REDIS_URL
  value: {{ printf "redis://%s-redis:6379" .Release.Name | quote }}
{{- end -}}
{{- end }}

{{/*
Resolve the REDIS_PASSWORD env var entry. ONLY emitted when the user
explicitly sets `env.redisPassword` or `env.redisPasswordExistingSecret.name`
— these are LEGACY knobs for non-Lettuce Redis drivers (Jedis, …). The
default Lettuce driver IGNORES REDIS_PASSWORD and reads the password from
the URI itself; for that path, embed the password in `env.redisURL` (or rely
on the bundled subchart's Secret.uri — handled by otoroshi.redisURLEnv).

The output is a list item starting with `- name: REDIS_PASSWORD`. Caller
controls indent. Empty when neither knob is set.
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
