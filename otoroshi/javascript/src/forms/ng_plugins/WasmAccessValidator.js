import React, { useEffect, useState } from 'react'
import { NgSelectRenderer } from '../../components/nginputs';

export default {
  id: 'cp:otoroshi.next.plugins.WasmAccessValidator',
  config_schema: {
    source: {
      type: 'select',
      label: 'Source',
      props: {
        optionsFrom: 'http://localhost:5001/ui/plugins',
        optionsTransformer: {
          label: 'filename',
          value: 'pluginId',
        },
      }
      // type: 'string',
      // label: 'Source',
      // renderer: props => {
      //   const [plugins, setPlugins] = useState([])

      //   useEffect(() => {
      //     fetch('http://localhost:5001/ui/plugins', {

      //     })
      //       .then(res => res.json())
      //       .then(res => setPlugins(res))
      //   }, [])

      //   return (
      //     <div className="mt-3">
      //       <NgSelectRenderer
      //         placeholder="Select a wasm plugin to execute"
      //         ngOptions={{
      //           spread: true,
      //         }}
      //         onChange={(id) => {

      //         }}
      //         options={plugins}
      //         optionsTransformer={(arr) => arr.map((item) => ({ value: item.pluginId, label: item.filename }))}
      //       />
      //     </div>
      //   );

      // props: {
      //   subTitle: `http://xxx.xxx or https://xxx.xxx or file://path or base64://encodedstring`
      // }
    },
    memoryPages: {
      type: 'number',
      label: 'Max number of pages',
      props: {
        defaultValue: 4,
        subTitle: 'Configures memory for the Wasm runtime. Memory is described in units of pages (64KB) and represent contiguous chunks of addressable memory'
      }
    },
    functionName: {
      type: 'string',
      label: 'The name of the exported function to invoke'
    },
    config: {
      label: 'Static configuration',
      type: 'object'
    },
    allowedHosts: {
      label: 'Allowed hosts',
      type: 'array',
      array: true,
      format: null
    }
  },
  config_flow: [
    'source',
    'memoryPages',
    'functionName',
    'config',
    'allowedHosts'
  ]
};
