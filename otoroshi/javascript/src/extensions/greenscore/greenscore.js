import React, { Component } from 'react';
import {
  NgBooleanRenderer
} from '../../components/nginputs'
import { Table } from '../../components/inputs/Table';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { v4 as uuid } from 'uuid';

export const MAX_GREEN_SCORE_NOTE = 6000;
const GREEN_SCORE_GRADES = {
  "#2ecc71": rank => rank >= MAX_GREEN_SCORE_NOTE,
  "#27ae60": rank => rank < MAX_GREEN_SCORE_NOTE && rank >= 3000,
  "#f1c40f": rank => rank < 3000 && rank >= 2000,
  "#d35400": rank => rank < 2000 && rank >= 1000,
  "#c0392b": rank => rank < 1000
}

export function calculateGreenScore(routeRules) {
  const { sections } = routeRules;

  const score = sections.reduce((acc, item) => {
    return acc + item.rules.reduce((acc, rule) => {
      return acc += (rule.enabled ? MAX_GREEN_SCORE_NOTE * (rule.section_weight / 100) * (rule.weight / 100) : 0)
    }, 0)
  }, 0);

  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex(grade => grade[1](score))

  return {
    score,
    rank: rankIdx === -1 ? "Not evaluated" : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx)
  }
}

export function GreenScoreForm(props) {
  const rootObject = props.rootValue?.green_score_rules;
  const sections = rootObject?.sections || [];

  const onChange = (checked, currentSectionIdx, currentRuleIdx) => {
    props.rootOnChange({
      ...props.rootValue,
      green_score_rules: {
        ...props.rootValue.green_score_rules,
        sections: sections.map((section, sectionIdx) => {
          if (currentSectionIdx !== sectionIdx)
            return section

          return {
            ...section,
            rules: section.rules.map((rule, ruleIdx) => {
              if (ruleIdx !== currentRuleIdx)
                return rule;


              console.log('changed')
              return {
                ...rule,
                enabled: checked
              }
            })
          }
        })
      }
    })
  }

  return <div>
    {sections.map(({ id, rules }, currentSectionIdx) => {
      return <div key={id} className='p-3'>
        <h4 className='mb-3' style={{ textTransform: 'capitalize' }}>{id}</h4>
        {rules.map(({ id, description, enabled, advice }, currentRuleIdx) => {
          return <div key={id}
                      className='d-flex align-items-center'
                      style={{
                        cursor: 'pointer'
                      }}
                      onClick={e => {
                        e.stopPropagation();
                        onChange(!enabled, currentSectionIdx, currentRuleIdx)
                      }}>
            <div className='flex'>
              <p className='offset-1 mb-0' style={{ fontWeight: 'bold' }}>{description}</p>
              <p className='offset-1'>{advice}</p>
            </div>
            <div style={{ minWidth: 52 }}>
              <NgBooleanRenderer
                value={enabled}
                onChange={checked => onChange(checked, currentSectionIdx, currentRuleIdx)}
                schema={{}}
                ngOptions={{
                  spread: true
                }}
              />
            </div>
          </div>
        })}
      </div>
    })}
  </div>
}

class GreenScoreConfigsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome Green Score' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the Green Score config' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    routes: {
      type: 'array',
      props: { label: 'Routes' }
    },
    // TODO: display the score
    config: {
      // TODO: use a custom form with all flags
      type: 'jsonobjectcode',
      props: {
        label: 'raw config.'
      }
    }
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    { title: 'Description', filterId: 'description', content: (item) => item.description },
  ];

  formFlow = ['_loc', 'id', 'name', 'description', 'tags', 'metadata', 'routes', 'config'];

  componentDidMount() {
    this.props.setTitle(`All Green Score configs.`);
  }

  client = BackOfficeServices.apisClient('green-score.extensions.otoroshi.io', 'v1', 'green-scores');

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="extensions/green-score/green-score-configs"
        defaultTitle="All Green Score configs."
        defaultValue={() => ({
          id: 'green-score-config_' + uuid(),
          name: 'My Green Score',
          description: 'An awesome Green Score',
          tags: [],
          metadata: {},
          routes: [],
          config: {

          }
        })}
        itemName="Green Score config"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={(paginationState) => this.client.findAll()}
        updateItem={this.client.update}
        deleteItem={this.client.delete}
        createItem={this.client.create}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`
        }}
        itemUrl={(item) => `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind={"GreenScore"}
      />
    );
  }
}

const GreenScoreExtensionId = "otoroshi.extensions.GreenScore"
const GreenScoreExtension = (ctx) => {
  return {
    id: GreenScoreExtensionId,
    sidebarItems: [
      // TODO: add here if we want icon in sidebar
    ],
    creationItems: [],
    dangerZoneParts: [],
    features: [
      {
        title: 'Green Score configs.',
        description: 'All your Green Score configs.',
        img: 'private-apps', // TODO: change image
        link: '/extensions/green-score/green-score-configs',
        display: () => true,
        icon: () => 'fa-cubes', // TODO: change icon
      },
    ],
    searchItems: [
      {
        action: () => {
          window.location.href = `/bo/dashboard/green-score/green-score-configs`
        },
        env: <span className="fas fa-cubes" />,
        label: 'Green Score configs.',
        value: 'green-score-configs',
      }
    ],
    routes: [
      // TODO: add more route here if needed
      {
        path: '/extensions/green-score/green-score-configs/:taction/:titem',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs/:taction',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      }
    ],
  }
}

export function setupGreenScoreExtension(registerExtensionThunk) {
  registerExtensionThunk(GreenScoreExtensionId, GreenScoreExtension);
}