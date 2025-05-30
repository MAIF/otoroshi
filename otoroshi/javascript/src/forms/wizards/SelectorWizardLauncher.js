import React from 'react';
import { Button } from '../../components/Button';
import { NgForm } from '../../components/nginputs';
import { Row } from '../../components/Row';

export class SelectorWizardLauncher extends React.Component {
  state = {
    entity: undefined,
  };

  componentDidMount() {
    this.loadEntity(this.props.value);
  }

  componentDidUpdate(prevProps) {
    if (prevProps.value !== this.props.value) {
      this.loadEntity(this.props.value);
    }
  }

  loadEntity = (value) => {
    if (value && typeof value === 'string') {
      if (this.props.findById) {
        this.props.findById(value).then((entity) => {
          this.setState({ entity });
        });
      }
    } else
      this.setState({
        entity: undefined,
      });
  };

  render() {
    const { entity } = this.state;
    const { openComponent, onChange, entityName, entityField } = this.props;

    if (!entity) {
      return (
        <Row title="Authentication module" className="col-sm-10 d-flex align-items-center">
          <Button
            type="info"
            text="Select a module"
            onClick={openComponent}
            className="btn-sm w-100"
          />
        </Row>
      );
    } else {
      return (
        <div style={{ flex: 1 }}>
          <NgForm
            style={{
              position: 'relative',
            }}
            key={entity.id}
            readOnly={true}
            value={entity}
            schema={{
              actions: {
                renderer: () => {
                  return (
                    <div className="d-flex justify-content-end mt-3">
                      <Button className="btn-sm" type="danger" onClick={() => onChange(undefined)}>
                        <i className="fas fa-times me-1" />
                        Unselect
                      </Button>
                      <Button type="info" onClick={() => openComponent({})} className="mx-1 btn-sm">
                        <i className="fas fa-key me-1" />
                        Choose another
                      </Button>
                      <Button
                        type="info"
                        className="btn-sm"
                        onClick={() =>
                          openComponent({
                            mode: 'update_in_wizard',
                            [entityField]: entity,
                          })
                        }
                      >
                        <i className="fas fa-pencil-alt me-1" />
                        Edit
                      </Button>
                    </div>
                  );
                },
              },
            }}
            flow={[
              {
                type: 'group',
                name: (props) => {
                  if (!props.value) return `Selected ${entityName}`;
                  else {
                    return `${props.value.name} - ${props.value.desc}`;
                  }
                },
                fields: ['actions'],
              },
            ]}
          />
        </div>
      );
    }
  }
}
