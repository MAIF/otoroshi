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
