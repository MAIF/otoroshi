import React, { Component } from 'react';
import { Table } from '../components/inputs';
import { UserRightsEditor } from '../components/UserRightsEditor';
import * as BackOfficeServices from '../services/BackOfficeServices';

const client = BackOfficeServices.apisClient('security.otoroshi.io', 'v1', 'user-rights-policies');

function describeRights(rights) {
  return (rights || [])
    .map((r) => {
      const kinds =
        r.authorized_entities_read !== undefined || r.authorized_entities_write !== undefined
          ? ` (write: ${(r.authorized_entities_write || []).join(', ') || 'nothing'})`
          : '';
      return `${r.tenant} / ${(r.teams || []).join(', ')}${kinds}`;
    })
    .join(' + ');
}

export class UserRightsPoliciesPage extends Component {
  columns = [
    {
      title: 'Email',
      filterId: 'email',
      content: (item) => item.email,
    },
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Mode',
      filterId: 'mode',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.mode,
    },
    {
      title: 'Auth module',
      notFilterable: true,
      style: { textAlign: 'center', width: 200 },
      content: (item) => item.auth_module_ref || 'backoffice auth module',
    },
    {
      title: 'Rights',
      notFilterable: true,
      content: (item) => describeRights(item.rights),
    },
  ];

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    email: {
      type: 'string',
      props: {
        label: 'User email',
        placeholder: 'jane.doe@oto.tools',
        help: 'The email the user logs in with. It identifies the policy: a user has one policy at most',
      },
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'Jane Doe rights' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'What the user is allowed to do' },
    },
    mode: {
      type: 'select',
      props: {
        label: 'Mode',
        help: 'replace: the rights below are the rights of the users of the auth module below, the ones the module gives them are ignored. The other users with the same email (local admins, users of another auth module) keep their own rights, capped by the rights below. restrict: every user with the email keeps its own rights, capped by the rights below',
        possibleValues: [
          { label: 'Replace the rights of the auth module', value: 'replace' },
          { label: 'Restrict the rights of the auth module', value: 'restrict' },
        ],
      },
    },
    auth_module_ref: {
      type: 'select',
      props: {
        label: 'Auth module',
        placeholder: 'The backoffice auth module',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/api/auths',
        transformer: (a) => ({ value: a.id, label: a.name }),
        help: 'The auth module whose users get the rights of the policy in replace mode. Empty for the backoffice auth module. An email alone is not an identity: the local admins and the users of the other auth modules with the same email are only restricted by the policy',
      },
    },
    rights: {
      type: UserRightsEditor,
      props: { label: 'Rights' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
  };

  formFlow = [
    '_loc',
    'email',
    'name',
    'description',
    'mode',
    'auth_module_ref',
    '<<<Rights',
    'rights',
    '>>>Misc.',
    'tags',
    'metadata',
  ];

  componentDidMount() {
    this.props.setTitle('User rights policies');
  }

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <Table
        parentProps={this.props}
        selfUrl="user-rights-policies"
        defaultTitle="User rights policies"
        defaultValue={() => client.template()}
        itemName="User rights policy"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={(paginationState) => client.findAllWithPagination(paginationState)}
        // a policy is identified by the email of its user
        updateItem={(item) => client.update({ ...item, id: item.email })}
        deleteItem={(item) => client.deleteById(item.email)}
        createItem={client.create}
        navigateTo={(item) => {
          this.props.history.push({
            pathname: `/user-rights-policies/edit/${item.email}`,
          });
        }}
        itemUrl={(item) => `/bo/dashboard/user-rights-policies/edit/${item.email}`}
        showActions={true}
        showLink={false}
        rowNavigation={true}
        firstSort={0}
        extractKey={(item) => item.email}
        export={true}
        kubernetesKind="security.otoroshi.io/UserRightsPolicy"
      />
    );
  }
}
