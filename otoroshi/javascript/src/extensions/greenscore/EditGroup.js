import React, { useEffect, useState } from 'react';
import { useParams, useHistory } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { NgForm } from '../../components/nginputs';
import GroupRoutes from './routesForm';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

export default function EditGroup({ }) {
    const params = useParams();
    const history = useHistory()

    const client = BackOfficeServices.apisClient(
        'green-score.extensions.otoroshi.io',
        'v1',
        'green-scores'
    );

    const [group, setGroup] = useState();
    const [routes, setRoutes] = useState(undefined);
    const [rulesBySection, setRulesBySection] = useState(undefined);

    useEffect(() => {
        Promise.all([
            client.findById(params.group_id),
            getRulesBySection(),
            BackOfficeServices.nextClient
                .forEntity(BackOfficeServices.nextClient.ENTITIES.ROUTES)
                .findAll()
        ]).then(([group, rulesBySection, routes]) => {
            setGroup(group);
            setRoutes(routes);
            setRulesBySection(rulesBySection.reduce((acc, rule) => {
                if (acc[rule.section]) {
                    return {
                        ...acc,
                        [rule.section]: [...acc[rule.section], rule]
                    }
                } else {
                    return {
                        ...acc,
                        [rule.section]: [rule]
                    }
                }
            }, {}));
        });
    }, []);

    const getRulesBySection = () => fetch('/bo/api/proxy/api/extensions/green-score/template', {
        credentials: 'include',
        headers: {
            Accept: 'application/json',
        },
    })
        .then((r) => r.json());

    const flow = [
        '_loc',
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ['id', 'name', 'description']
        },
        {
            type: 'group',
            name: 'Routes.',
            collapsed: false,
            collapsable: false,
            fields: ['routes']
        },
        {
            type: 'group',
            name: 'Misc.',
            collapsed: true,
            fields: ['metadata', 'tags'],
        }
    ];
    const schema = {
        id: {
            type: 'string',
            label: 'Id',
            disabled: true
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
            type: 'location'
        },
        metadata: {
            type: 'object',
            label: 'Metadata',
        },
        tags: {
            type: 'string',
            label: 'Tags',
            array: true
        },
        routes: {
            renderer: props => <GroupRoutes {...props}
                allRoutes={routes}
                rulesBySection={rulesBySection}
            />
        }
    };

    if (!group || !routes || !rulesBySection)
        return null;

    return <div style={{ position: 'relative' }}>
        <FeedbackButton
            className="ms-2"
            text="Save"
            style={{
                position: 'absolute',
                top: 0,
                marginTop: 'calc(-2.5rem - 34px)',
                right: 0,
                maxHeight: 32,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 57,
                padding: 0,
                borderRadius: 6
            }}
            onPress={() => {
                client.update(group)
                    .then(() => {
                        history.push('/extensions/green-score/groups')
                    })
            }}
        />
        <NgForm
            flow={flow}
            schema={schema}
            value={group}
            onChange={setGroup}
        />
    </div>
}