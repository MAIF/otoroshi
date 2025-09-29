import React, { Suspense, useCallback, useState } from 'react';
import { NgAnyRenderer, NgForm, NgStringRenderer } from '../../components/nginputs';
import { PillButton } from '../../components/PillButton';
import CodeInput from '../../components/inputs/CodeInput';
import { INFORMATION_FIELDS, splitInformationAndContent } from './WorkflowsDesigner';
import { getNodeFromKind } from './models/Functions';
import { MarkdownInput } from '../../components/nginputs/MarkdownInput';
import { TextInput } from '../../components/inputs';

function setEnabled(state) {
  if (state?.enabled === undefined)
    return {
      ...state,
      enabled: true,
    };
  return state;
}

function getExample(functionData) {
  const FORBIDDEN_FIELDS = ['function']

  if (!functionData?.example)
    return null

  return <div className='relative mb-3'>
    <div className="absolute top-2 right-2" style={{ gap: '.5rem' }}>
      <span className="badge bg-warning">
        EXAMPLE
      </span>
    </div>
    <MarkdownInput
      className="markdown-description mt-3"
      readOnly={true}
      preview={true}
      value={"```\n" + JSON.stringify(Object.fromEntries(
        Object.entries(functionData.example || {}).filter(([key, _]) => !INFORMATION_FIELDS.includes(key) && !FORBIDDEN_FIELDS.includes(key))
      ), null, 4) + "\n```\n"}
    />
  </div>
}

export function ModalEditor({ node }) {
  if (!node) return null;

  const { data, id } = node;

  const isAnOperator = data.operators;
  const isAFunction = data.content.function;

  const functionData = isAFunction ? getNodeFromKind(isAFunction) : undefined;

  let schema = {
    description: {
      type: 'string',
      label: 'Description',
      help: 'A comment for readability/debugging',
    },
    enabled: {
      label: 'Enabled',
      type: 'bool',
    },
    breakpoint: {
      label: 'Breakpoint',
      type: 'bool',
    },
    result: {
      type: 'string',
      label: 'Result',
      help: 'Name of memory variable to store output',
    },
  };

  if (isAFunction) {
    schema = {
      ...schema,
      args: functionData?.form_schema ? {
        type: 'form',
        label: 'Arguments',
        flow: Object.keys(functionData?.form_schema || {}),
        schema: functionData?.form_schema || {},
      } : {
        type: 'object',
        label: 'Arguments',
        itemRenderer: useCallback(({ entry, onChangeValue, onChangeKey, idx }) => {
          const [key, value] = entry
          return <div className='d-flex flex-column' style={{ flex: 1 }} key={idx}>
            <TextInput
              flex={true}
              value={key}
              onChange={onChangeKey} />
            <CodeInput
              value={value}
              editorOnly
              rawSchema={{
                props: {
                  height: '100%',
                  ace_config: {
                    fontSize: 14,
                  },
                },
              }}
              onChange={onChangeValue} />
          </div>
        }, [])
      },
    }

    data.schema = functionData?.form_schema;
  } else {
    schema = {
      ...schema,
      ...data.form_schema,
    };
  }

  schema = {
    ...schema,
    example: {
      renderer: () => getExample(functionData)
    }
  }

  // const hasArgsSchema = Object.keys(functionData?.form_schema || {}).length > 0;
  const argsFlow = ['args', 'result'];

  const defaultFlow = [...(data.flow || Object.keys(data.schema || {})), 'result'].filter(
    (field) => field.length > 0
  );

  const getConfigurationGroup = () => {
    const configuration = {
      type: 'group',
      name: 'Configuration',
      collapsable: false,
      fields: [],
    };

    let fields = defaultFlow

    if (isAFunction)
      fields = argsFlow

    return {
      ...configuration,
      fields: [
        functionData?.example ? {
          type: 'group',
          name: 'Examples',
          collapsed: true,
          fields: ['example']
        } : undefined,
        ...fields
      ].filter(f => f)
    }
  };

  let flow = [
    {
      type: 'group',
      name: 'General',
      collapsable: false,
      fields: [!isAnOperator ? 'enabled' : '', 'breakpoint', 'description'].filter((field) => field.length > 0),
    },
    getConfigurationGroup(),
  ];

  const value = setEnabled({
    ...node.data.information,
    ...node.data.content,
  });

  const [state, setState] = useState(value);
  const [jsonView, setJsonView] = useState(false);

  const handleCodeInputChange = (newData) => {
    onChange({ ...state, ...JSON.parse(newData) });
  };

  const onChange = (newData) => {
    const { information, content } = splitInformationAndContent(newData);

    if (data.operators) {
      data.functions.handleDataChange(id, {
        [data.kind]: content,
        information,
      });
    } else {
      data.functions.handleDataChange(id, {
        information,
        content,
      });
    }
    setState(newData);
  };

  const getCodeInputValue = () => {
    const fields = flow[1]?.fields;

    return Object.fromEntries(
      Object.entries(state).filter(([key, _]) => fields.includes(key))
    )
  };

  return (
    <div className="modal-editor d-flex flex-column" style={{ flex: 1 }}>
      <p className="p-3 m-0 whats-next-title">
        {functionData
          ? (functionData.display_name ? functionData.display_name : functionData.name)
          : node.data.display_name
            ? node.data.display_name
            : node.data.name}
      </p>

      <PillButton
        className="mt-3"
        rightEnabled={!jsonView}
        leftText="Visual Editor"
        rightText="Code Editor"
        onLeftClick={() => setJsonView(false)}
        onRightClick={() => setJsonView(true)}
      />

      {jsonView ? (
        <Suspense fallback={<div>Loading ...</div>}>
          <div style={{ flex: 1 }} className="py-3">
            {getExample(functionData)}
            <NgAnyRenderer
              ngOptions={{
                spread: true,
              }}
              mode='jsonOrPlaintext'
              height="100%"
              value={getCodeInputValue()}
              onChange={handleCodeInputChange}
            />
          </div>
        </Suspense>
      ) : (
        <div className="p-3">
          <NgForm schema={schema} flow={flow} value={state} onChange={onChange} />
        </div>
      )}
    </div>
  );
}
