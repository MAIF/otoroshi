import React from 'react';
import { Link } from 'react-router-dom';
import { firstLetterUppercase } from '../../util';
import { GREEN_SCORE_GRADES, getColor, getLetter } from './util';
import * as BackOfficeServices from '../../services/BackOfficeServices';

function Heading({ title, onClick, textCenter }) {
  return (
    <div
      style={{
        textTransform: 'uppercase',
        fontWeight: 600,
        color: 'var(--text)',
        whiteSpace: 'nowrap',
        cursor: 'pointer',
      }}
      className={textCenter ? 'text-center' : ''}
      onClick={onClick}>
      {title}
    </div>
  );
}

function Headings({ sort }) {
  return (
    <div
      className="mb-2"
      style={{
        padding: '0rem 1rem',
        display: 'grid',
        gridTemplateColumns: '62px 1fr 2fr repeat(4, 1fr) 64px',
      }}>
      <div />
      <Heading title="Name" onClick={() => sort('name')} />
      <Heading title="Description" onClick={() => sort('description')} />
      <Heading title="Notation" onClick={() => sort('notation')} textCenter />
      <Heading title="Net Score" onClick={() => sort('net')} textCenter />
      <Heading title="Data" onClick={() => sort('data')} textCenter />
      <Heading title="Dynamic Score" onClick={() => sort('dynamic')} textCenter />
    </div>
  );
}

export default class CustomTable extends React.Component {
  formatGroups = (items) => items.map((item, i) => this.formatGroup(item, i));

  formatGroup = (group, i) => {
    const entity = this.props.scores[i] || {
      score: 0,
      dynamic_values: 0,
    };
    return {
      ...group,
      opened: false,
      name: firstLetterUppercase(group.name),
      description: firstLetterUppercase(group.description),
      notation: getLetter(entity.score),
      color: getColor(entity.score),
      net: entity.score,
      data: String.fromCharCode(65 + Math.min(Math.floor((1 - entity.dynamic_values) * 5))),
      dynamicLetter: Object.keys(GREEN_SCORE_GRADES)[
        Math.min(Math.floor((1 - entity.dynamic_values) * 5))
      ],
      dynamic: parseFloat(entity.dynamic_values * 100, 2).toFixed(2),
    };
  };

  state = {
    columns: {},
    items: this.formatGroups(this.props.items),
  };

  componentDidMount() {
    this.client = BackOfficeServices.apisClient(
      'green-score.extensions.otoroshi.io',
      'v1',
      'green-scores'
    );
  }

  componentDidUpdate(prevProps) {
    if (prevProps.items.length === 0 && this.props.items.length > 0)
      this.setState({
        items: this.formatGroups(this.props.items),
      });
  }

  openScore = (group) => {
    this.setState({
      items: this.state.items.map((g) => {
        if (g.id === group.id) {
          return {
            ...g,
            opened: !g.opened,
          };
        }
        return g;
      }),
    });
  };

  openActions = (i) => {
    this.setState({
      items: this.state.items.map((g, j) => {
        if (j === i) {
          return {
            ...g,
            openedActions: !g.openedActions,
          };
        }
        return {
          ...g,
          openedActions: false,
        };
      }),
    });
  };

  deleteGroup = (id) => {
    window
      .newConfirm('Delete this group ?', {
        title: 'Validation required',
      })
      .then((ok) => {
        if (ok) {
          this.client.deleteById(id).then(() => {
            window.location.reload();
          });
        }
      });
  };

  render() {
    const { items } = this.state;

    return (
      <div>
        <Headings
          sort={(column) => {
            const columnIncr =
              this.state.columns[column] === undefined ? false : !this.state.columns[column];
            this.setState({
              columns: {
                ...this.state.columns,
                [column]: columnIncr,
              },
              items: items.sort((a, b) => {
                try {
                  return this.state.columns[column]
                    ? a[column].localeCompare(b[column])
                    : b[column].localeCompare(a[column]);
                } catch (err) {
                  return this.state.columns[column] ? a[column] - b[column] : b[column] - a[column];
                }
              }),
            });
          }}
        />

        {items.length === 0 && (
          <div className="d-flex justify-content-center mt-3">
            <Link
              to="/extensions/green-score/groups/new"
              className="btn btn-sm d-flex align-items-center"
              style={{
                borderRadius: 6,
                backgroundColor: 'var(--color-primary)',
                boxShadow: `0 0 0 1px var(--color-primary,transparent)`,
                color: 'var(--text)',
              }}>
              Start New Group
            </Link>
          </div>
        )}
        {items.map((group, i) => {
          return (
            <div
              key={group.id}
              style={{
                marginBottom: '.2rem',
                background: 'var(--bg-color_level2)',
                borderRadius: '.25rem',
                cursor: 'pointer',
              }}
              onClick={() => this.openScore(group)}>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: '62px 1fr 2fr repeat(4, 1fr) 64px',
                  alignItems: 'center',
                  padding: '.5rem 1rem',
                }}>
                {/* <div>#{i}</div> */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'var(--bg-color_level1)',
                    borderRadius: '10%',
                    width: 32,
                    height: 32,
                    cursor: 'pointer',
                    marginRight: 12,
                  }}
                  onClick={() => this.openScore(group)}>
                  <i className={`fas fa-chevron-${group.opened ? 'up' : 'down'}`} />
                </div>
                <span style={{ minWidth: '30%', color: 'var(--text)' }}>{group.name}</span>

                <span
                  style={{
                    minWidth: '30%',
                    color: 'var(--text)',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                  }}>
                  {group.description}
                </span>

                <span className="mx-3 text-center">
                  {group.notation}
                  <i
                    className="fa fa-leaf ms-1"
                    style={{ color: group.color, fontSize: '1.1rem' }}
                  />
                </span>
                <div className="text-center d-flex justify-content-center">
                  <div style={{ minWidth: 40 }}>
                    <span>{group.net}</span>
                  </div>
                  <span style={{ borderLeft: '2px solid var(--bg-color_level3)' }} className="ps-1">
                    6000
                  </span>
                </div>
                <span className="text-center">
                  {group.data}
                  <i
                    className="fa fa-leaf ms-1"
                    style={{ color: group.dynamicLetter, fontSize: '1.1rem' }}
                  />
                </span>
                <span className="text-center">{group.dynamic}%</span>

                <ItemActions
                  unfold={group.openedActions}
                  onDelete={() => this.deleteGroup(group.id)}
                  openAction={() => this.openActions(i)}
                  editLink={`/extensions/green-score/groups/${group.id}`}
                />
              </div>

              {group.opened && (
                <div style={{ background: 'var(--bg-color_level3)', padding: '.5rem 1rem' }}>
                  {this.props.scores[i].routes.map((_, routeIndex) => {
                    const routeInformations = this.props.routes.find(
                      (r) => r.id === items[i].routes[routeIndex]?.routeId
                    );

                    if (
                      !routeInformations ||
                      !this.props.scores[i].sectionsAtCurrentDate[routeIndex]
                    )
                      return null;

                    const sections = this.props.scores[i].sectionsAtCurrentDate[routeIndex]
                      .sections;

                    const score = sections.reduce((acc, item) => acc + item.score.score, 0);

                    const notation = getLetter(score);
                    const color = getColor(score);
                    const net = score;

                    const dynamicValues = this.props.getDynamicScore(
                      this.props.dynamicValuesByRoutes.find(
                        (f) => f.id === routeInformations.id && f.group_id === group.id
                      ).dynamic_values.scaling
                    );

                    const data = String.fromCharCode(
                      65 + Math.min(Math.floor((1 - dynamicValues) * 5))
                    );
                    const dynamicLetter = Object.keys(GREEN_SCORE_GRADES)[
                      Math.min(Math.floor((1 - dynamicValues) * 5))
                    ];
                    const dynamic = parseFloat(dynamicValues * 100, 2).toFixed(2);

                    return (
                      <div
                        className="mt-1"
                        key={routeInformations.id}
                        style={{
                          display: 'grid',
                          gridTemplateColumns: '62px 1fr 2fr repeat(4, 1fr) 64px',
                          alignItems: 'center',
                          padding: '0.5rem 0',
                        }}>
                        <div />
                        <span style={{ minWidth: '30%', color: 'var(--text)' }}>
                          {routeInformations.name}
                        </span>

                        <span
                          style={{
                            minWidth: '30%',
                            color: 'var(--text)',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                          }}>
                          {routeInformations.description}
                        </span>

                        <span className="mx-3 text-center">
                          {notation}
                          <i
                            className="fa fa-leaf ms-1"
                            style={{ color: color, fontSize: '1.1rem' }}
                          />
                        </span>
                        <div className="text-center d-flex justify-content-center">
                          <div style={{ minWidth: 40 }}>
                            <span>{net}</span>
                          </div>
                          <span
                            style={{ borderLeft: '2px solid var(--bg-color_level3)' }}
                            className="ps-1">
                            6000
                          </span>
                        </div>
                        <span className="text-center">
                          {data}
                          <i
                            className="fa fa-leaf ms-1"
                            style={{ color: dynamicLetter, fontSize: '1.1rem' }}
                          />
                        </span>
                        <span className="text-center">{dynamic}%</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    );
  }
}

function ItemActions({ unfold, openAction, editLink, onDelete }) {
  return (
    <div
      className="d-flex justify-content-center ml-auto"
      onClick={(e) => {
        e.stopPropagation();
        openAction();
      }}>
      {unfold ? (
        <div className="d-flex">
          <Link
            to={editLink}
            type="button"
            className="btn btn-sm btn-success me-1">
            <i className="fas fa-pencil-alt"  />
          </Link>
          <button
            type="button"
            className="btn btn-sm btn-danger"           
            onClick={onDelete}>
            <i className="fas fa-trash" />
          </button>
        </div>
      ) : (
        <i
          className="fas fa-ellipsis-vertical"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--bg-color_level1)',
            borderRadius: '10%',
            cursor: 'pointer',
            width: 32,
            height: 32,
          }}
        />
      )}
    </div>
  );
}
