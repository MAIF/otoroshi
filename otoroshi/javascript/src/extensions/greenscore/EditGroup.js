import React, { useEffect, useState } from 'react';
import { useParams, useHistory } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { NgForm } from '../../components/nginputs';
import { GroupRoutes, ThresholdsTable } from './GroupRoutes';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

function SaveButton({ saveAndExit, group, isNew, client, title }) {
  const history = useHistory();

  return (
    <FeedbackButton
      className="ms-2"
      text={title ? title : isNew ? 'Create group' : 'Save'}
      type="save"
      style={{
        maxHeight: 32,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '.5rem 1rem',
        borderRadius: 6,
      }}
      onPress={() => {
        return (isNew ? client.create(group) : client.update(group)).then(() => {
          if (saveAndExit) history.push('/extensions/green-score/groups');
        });
      }}
    />
  );
}

export default function EditGroup({}) {
  const params = useParams();
  const history = useHistory();

  const client = BackOfficeServices.apisClient(
    'green-score.extensions.otoroshi.io',
    'v1',
    'green-scores'
  );

  const [group, setGroup] = useState();
  const [routes, setRoutes] = useState(undefined);
  const [rulesBySection, setRulesBySection] = useState(undefined);

  const isNew = params.group_id === 'new';

  useEffect(() => {
    Promise.all([
      isNew ? client.template() : client.findById(params.group_id),
      getRulesBySection(),
      BackOfficeServices.nextClient
        .forEntity(BackOfficeServices.nextClient.ENTITIES.ROUTES)
        .findAll(),
    ]).then(([group, rulesBySection, routes]) => {
      setGroup(group);
      setRoutes(routes);
      setRulesBySection(
        rulesBySection.reduce((acc, rule) => {
          if (acc[rule.section]) {
            return {
              ...acc,
              [rule.section]: [...acc[rule.section], rule],
            };
          } else {
            return {
              ...acc,
              [rule.section]: [rule],
            };
          }
        }, {})
      );
    });
  }, []);

  const getRulesBySection = () =>
    fetch('/bo/api/proxy/api/extensions/green-score/template', {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then((r) => r.json());

  const flow = [
    '_loc',
    {
      type: 'group',
      name: 'Informations',
      collapsable: false,
      fields: ['id', 'name', 'description'],
    },
    {
      type: 'group',
      name: 'Thresholds',
      fields: ['thresholds'],
    },
    {
      type: 'group',
      name: 'Routes.',
      collapsed: false,
      collapsable: false,
      fields: ['routes'],
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['metadata', 'tags'],
    },
  ];
  const schema = {
    id: {
      type: 'string',
      label: 'Id',
      disabled: true,
    },
    name: {
      type: 'string',
      label: 'Name',
    },
    description: {
      type: 'string',
      label: 'Description',
    },
    _loc: {
      type: 'location',
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      type: 'string',
      label: 'Tags',
      array: true,
    },
    routes: {
      renderer: (props) => (
        <GroupRoutes {...props} allRoutes={routes} rulesBySection={rulesBySection} />
      ),
    },
    thresholds: {
      renderer: ThresholdsTable,
    },
  };

  if (!group || !routes || !rulesBySection) return null;

  return (
    <div style={{ position: 'relative' }}>
      <div
        className="d-flex"
        style={{
          position: 'absolute',
          top: 0,
          marginTop: 'calc(-2.5rem - 20px)',
          right: 0,
        }}>
        <FeedbackButton
          style={{
            padding: '0 .5rem 0 0.25rem',
            borderRadius: 6,
            background: 'transparent',
            color: 'var(--text)',
          }}
          onPress={() => Promise.resolve(history.push('/extensions/green-score/groups'))}
          icon={() => <i className="fas fa-chevron-left" />}
          text="Back "
        />
        {!isNew && <SaveButton client={client} title="Save and stay" group={group} />}
        <SaveButton isNew={isNew} client={client} group={group} saveAndExit />
      </div>
      <NgForm flow={flow} schema={schema} value={group} onChange={setGroup} />
    </div>
  );
}
