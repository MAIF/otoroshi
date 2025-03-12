import React from 'react';
import { Button } from '../../components/Button';
import { NgForm } from '../../components/nginputs';
import { findJwtVerifierById } from '../../services/BackOfficeServices';
import JwtVerifierForm from '../entities/JwtVerifier';

export class JwtVerifierLauncher extends React.Component {
  state = {
    verifier: undefined,
  };

  componentDidMount() {
    this.loadVerifier(this.props.value);
  }

  componentDidUpdate(prevProps) {
    if (prevProps.value !== this.props.value) {
      this.loadVerifier(this.props.value);
    }
  }

  loadVerifier = (value) => {
    if (value && typeof value === 'string') {
      findJwtVerifierById(value).then((verifier) => {
        this.setState({ verifier });
      });
    } else
      this.setState({
        verifier: undefined,
      });
  };

  render() {
    const { verifier } = this.state;
    const { openComponent, onChange } = this.props;

    if (!verifier) {
      return (
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>Verifier</label>
          <div className="col-sm-10">
            <Button
              type="info"
              text="Select a verifier"
              onClick={openComponent}
              className="w-100"
            />
          </div>
        </div>
      );
    } else {
      return (
        <div style={{ flex: 1 }}>
          <NgForm
            style={{
              position: 'relative',
            }}
            key={verifier.id}
            readOnly={true}
            value={verifier}
            schema={{
              ...JwtVerifierForm.config_schema,
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
                            jwtVerifier: verifier,
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
                  if (!props.value) return 'Selected verifier';
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
