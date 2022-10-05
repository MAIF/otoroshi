export default {
  "id": "cp:otoroshi.next.plugins.SOAPAction",
  "config_schema": {
    "convert_request_body_to_xml": {
      "label": "convert_request_body_to_xml",
      "type": "bool"
    },
    "charset": {
      "label": "charset",
      "type": "string"
    },
    "jq_request_filter": {
      "label": "jq_request_filter",
      "type": "string"
    },
    "preserve_query": {
      "label": "preserve_query",
      "type": "bool"
    },
    "action": {
      "label": "action",
      "type": "string"
    },
    "jq_response_filter": {
      "label": "jq_response_filter",
      "type": "string"
    },
    "url": {
      "label": "url",
      "type": "string"
    },
    envelope: {
      type: 'code',
      props: {
        label: 'Envelope',
        editorOnly: true,
      },
    }
  },
  "config_flow": [
    "url",
    "jq_response_filter",
    "action",
    "preserve_query",
    "jq_request_filter",
    "envelope",
    "charset",
    "convert_request_body_to_xml"
  ]
}