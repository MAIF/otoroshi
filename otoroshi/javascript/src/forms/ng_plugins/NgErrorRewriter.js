import React from "react";
import { NgAnyRenderer } from "../../components/nginputs/inputs";

export default {
  id: "cp:otoroshi.next.plugins.NgErrorRewriter",
  config_schema: {
    templates: {
      label: "Templates",
      help:
        "Error page templates, keyed by content-type (use 'default' as the catch-all). The matching one is picked by negotiating the client Accept header.",
      type: "object",
      itemRenderer: (props) => {
        const contentType = (props.entry && props.entry[0]) || "";
        const language = contentType.includes("json")
          ? "json"
          : contentType.includes("xml")
          ? "xml"
          : "html";
        return (
          <div style={{ width: "100%" }}>
            <input
              type="text"
              className="form-control mb-1"
              placeholder="Content-Type (e.g. text/html, application/json, default)"
              value={contentType}
              onChange={(e) => props.onChangeKey(e.target.value)}
            />
            <NgAnyRenderer
              schema={{
                props: {
                  ngOptions: { spread: true },
                  language,
                  height: "200px",
                },
              }}
              value={props.value}
              onChange={(code) => props.onChangeValue(code)}
            />
          </div>
        );
      },
    },
    ranges: {
      label: "Ranges",
      type: "array",
      array: true,
      format: "form",
      schema: {
        from: {
          label: "From",
          type: "number",
        },
        to: {
          label: "To",
          type: "number",
        },
      },
    },
    use_otoroshi_error_template: {
      label: "Fallback on Otoroshi error template",
      help:
        "When no configured template matches the client Accept, render the default Otoroshi error template (negotiated HTML/JSON)",
      type: "bool",
    },
    apply_el: {
      label: "Apply expression language",
      help:
        "Evaluate Otoroshi EL expressions (${...}) in the rendered templates",
      type: "bool",
    },
    max_body_size: {
      label: "Max body size",
      help:
        "Maximum number of bytes of the original response body read and captured in the event",
      type: "number",
      props: {
        suffix: "bytes",
      },
    },
    preserved_headers: {
      label: "Preserved headers",
      help:
        "Headers from the original backend response to keep on the rewritten response",
      type: "string",
      array: true,
    },
    additional_headers: {
      label: "Additional headers",
      help:
        "Extra headers added/forced on the rewritten response (e.g. hardening headers)",
      type: "object",
    },
    export: {
      label: "Export error",
      help: "Generate event that can be exported using data exporters",
      type: "bool",
    },
    log: {
      label: "Log error",
      help: "Log the error response in otoroshi logs",
      type: "bool",
    },
  },
  config_flow: [
    "log",
    "export",
    "ranges",
    "use_otoroshi_error_template",
    "apply_el",
    "max_body_size",
    "preserved_headers",
    "additional_headers",
    "templates",
  ],
};
