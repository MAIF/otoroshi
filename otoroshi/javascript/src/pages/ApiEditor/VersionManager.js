import React, { useState } from 'react';
import { NgForm } from '../../components/nginputs';
import { PublisDraftModalContent } from '../../components/Drafts/DraftEditor';
import { mergeData } from '../../components/Drafts/Compare/utils';
import { firstLetterUppercase } from '../../util';

export function VersionManager({ api, draft, owner, setState }) {
  const [deployment, setDeployment] = useState({
    apiRef: api.id,
    owner,
    at: Date.now(),
    apiDefinition: {
      ...draft.content,
      deployments: [],
    },
    draftId: draft.id
  });

  const getCompareStep = (field) => ({
    renderer: () => {
      return <PublisDraftModalContent draft={draft.content[field]} currentItem={api[field]} />;
    },
  });

  const schema = {
    location: {
      type: 'location',
    },
    apiRef: {
      type: 'string',
      props: {
        readOnly: true,
      },
    },
    owner: {
      type: 'string',
      label: 'Owner',
    },
    at: {
      type: 'datetime',
    },
    changes: {
      renderer: () => {
        return <p>Changes</p>
      }
    },
    routes: getCompareStep('routes'),
    flows: getCompareStep('flows'),
    backends: getCompareStep('backends'),
    subscriptions: getCompareStep('subscriptions'),
    deployments: getCompareStep('deployments'),
    documentation: getCompareStep('documentation'),
    apiDefinition: {
      renderer: () => {
        return <PublisDraftModalContent draft={draft.content} currentItem={api} />;
      },
    },
  };

  const getCompareFlowGroup = (name) => {
    const hasChanged = mergeData(api[name], draft.content[name]).changed

    const migration = {
      "routes": "endpoints"
    }

    return {
      type: 'group',
      name: firstLetterUppercase(migration[name] ?? name),
      collapsed: true,
      fields: [name],
      hasChanged
    }
  }

  const flow = [
    'owner',
    'changes',
    ...[
      getCompareFlowGroup('routes'),
      getCompareFlowGroup('flows'),
      getCompareFlowGroup('backends'),
      getCompareFlowGroup('subscriptions'),
      getCompareFlowGroup('deployments'),
      getCompareFlowGroup('documentation'),
      {
        type: 'group',
        name: 'Global',
        collapsed: true,
        fields: ['apiDefinition'],
        hasChanged: mergeData(api[name], draft.content[name]).changed
      },
    ]
      .sort((a, b) => Number(b.hasChanged) - Number(a.hasChanged))
      .filter(item => item.hasChanged)
  ]

  return (
    <div className="d-flex flex-column flex-grow gap-3 mt-3" style={{ maxWidth: 820 }}>
      <NgForm
        value={deployment}
        onChange={(data) => {
          setDeployment(data);
          setState(data);
        }}
        schema={schema}
        flow={flow}
      />
    </div>
  );
}
