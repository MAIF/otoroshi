import React, { useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useSignalValue } from 'signals-react-safe';
import moment from 'moment';
import { Table } from '../../components/inputs';
import { PublisDraftModalContent } from '../../components/Drafts/DraftEditor';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI } from './hooks';
import { VersionBadge } from './DraftOnly';
import { signalVersion } from './VersionSignal';

export function Deployments(props) {
  const params = useParams();
  const version = useSignalValue(signalVersion);

  const columns = [
    {
      title: 'Version',
      filterId: 'version',
      content: (item) => item.version,
    },
    {
      title: 'Deployed At',
      filterId: 'at',
      content: (item) => moment(item.at).format('YYYY-MM-DD HH:mm:ss.SSS'),
    },
    {
      title: 'Owner',
      filterId: 'owner',
      content: (item) => item.owner,
    },
  ];

  const { item } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'Deployments',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  if (!item) return <SimpleLoader />;

  if (version !== 'Published')
    return <div className="alert alert-warning">Deployments are only available in production.</div>;

  return (
    <Table
      navigateTo={(item) =>
        window.wizard(
          'Version',
          () => <PublisDraftModalContent draft={item} currentItem={item} />,
          {
            noCancel: true,
            okLabel: 'Close',
          }
        )
      }
      parentProps={{ params }}
      selfUrl="deployments"
      defaultTitle="Deployment"
      itemName="Deployment"
      columns={columns}
      fetchTemplate={() => Promise.resolve({})}
      fetchItems={() => Promise.resolve(item.deployments || [])}
      defaultSort="version"
      defaultSortDesc="true"
      showActions={false}
      extractKey={(item) => item.id}
      rowNavigation={true}
      hideAddItemAction={true}
    />
  );
}
