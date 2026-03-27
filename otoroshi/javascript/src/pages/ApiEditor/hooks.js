import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { useSignalValue } from 'signals-react-safe';
import { signalVersion } from './VersionSignal';
import { nextClient } from '../../services/BackOfficeServices';

export function useDraftOfAPI() {
  const params = useParams();
  const version = useSignalValue(signalVersion);

  const [draft, setDraft] = useState();
  const [api, setAPI] = useState();

  const [draftWrapper, setDraftWrapper] = useState();

  const draftClient = nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS);

  const isDraft = version && (version === 'Draft' || version === 'staging');

  useQuery(
    ['getAPI', params.apiId],
    () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId),
    {
      enabled: !api,
      onSuccess: setAPI,
    }
  );

  useQuery(
    ['findDraftsById', params.apiId, version],
    () => nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).findById(params.apiId),
    {
      retry: 0,
      enabled: !draft && !!api,
      onSuccess: (data) => {
        if (data.error) {
          Promise.all([
            nextClient.forEntityNext(nextClient.ENTITIES.APIS).template(),
            draftClient.template(),
          ]).then(([rawTemplate, template]) => {
            const apiTemplate = api ? api : rawTemplate;

            const draftApi = {
              ...apiTemplate,
              id: params.apiId,
            };

            const newDraft = {
              ...template,
              kind: apiTemplate.id.split('_')[0],
              id: params.apiId,
              name: apiTemplate.name,
              content: draftApi,
            };

            draftClient.create(newDraft).then(() => {
              setDraft(draftApi);
              setDraftWrapper(newDraft);
            });
          });
        } else {
          setDraftWrapper(data);
          setDraft(data.content);
        }
      },
    }
  );

  const updateDraft = (optDraft) => {
    return nextClient
      .forEntityNext(nextClient.ENTITIES.DRAFTS)
      .update({
        ...draftWrapper,
        content: {
          ...(optDraft ? optDraft : draft),
          enabled: true,
        },
      })
      .then(() => setDraft(optDraft ? optDraft : draft));
  };

  const updateAPI = (optAPI) => {
    return nextClient
      .forEntityNext(nextClient.ENTITIES.APIS)
      .update(optAPI ? optAPI : api)
      .then(() => setAPI(optAPI ? optAPI : api));
  };

  return {
    api,
    item: isDraft ? draft : api,
    draft,
    draftWrapper,
    version,
    tag: version === 'Published' ? 'PROD' : 'DEV',
    setItem: isDraft ? setDraft : setAPI,
    updateItem: isDraft ? updateDraft : updateAPI,
    updateDraft,
    updateAPI,
    isDraft,
  };
}

export function historyPush(history, location, link) {
  history.push({
    pathname: link,
    search: location.search,
  });
}

export function linkWithQuery(link) {
  return `${link}${window.location.search}`;
}
