import React, { Component } from 'react';
import { nextClient } from '../services/BackOfficeServices';
import { Table } from '../components/inputs';

export class DraftsPage extends Component {
  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Description', filterId: 'description', content: (item) => item.description },
    { title: 'Kind', filterId: 'id', content: (item) => item['id'].split('_')[0] }
  ];

  componentDidMount() {
    this.props.setTitle(`Drafts`);
  }

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="drafts"
          defaultTitle="Drafts"
          defaultValue={() => ({})}
          itemName="draft"
          columns={this.columns}
          fetchItems={(paginationState) => nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
            .findAllWithPagination({
              ...paginationState,
              fields: ['name', 'description', 'id']
            })
          }
          deleteItem={item => nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
            .deleteById(item.id)}
          showActions
          hideEditButton
          showLink={false}
          extractKey={(item) => item.id}
          injectTopBar={() => {
            return <button
              type="button"
              className="btn btn-danger btn-sm ms-2"
              onClick={() => {
                window.newConfirm("Are you sure to delete all drafts ?")
                  .then(ok => {
                    if (ok)
                      nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
                        .deleteAll()
                        .then(() => window.location.reload())
                  })
              }}>
              <i className="fas fa-fire" /> Delete all drafts
            </button>
          }}
        />
      </div>
    );
  }
}