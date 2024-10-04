import React, { useState } from 'react'
import {
    useQuery,
    useMutation,
    useQueryClient,
    QueryClient,
    QueryClientProvider,
} from 'react-query'
import { nextClient } from '../services/BackOfficeServices'
import { NgSelectRenderer } from './nginputs'
import { Button } from './Button'

const queryClient = new QueryClient()

const findDraftByEntityId = id => nextClient
    .forEntityNext(nextClient.ENTITIES.DRAFTS)
    .fetch(`/entities/${id}`)

const getTemplate = () => nextClient
    .forEntityNext(nextClient.ENTITIES.DRAFTS)
    .template()

// id, kind, entityId, content, name, description, tags, metadata, location
const createDraft = newDraft => nextClient
    .forEntityNext(nextClient.ENTITIES.DRAFTS)
    .create(newDraft)

function DraftEditor({ entityId }) {
    // const queryClient = useQueryClient()

    const query = useQuery(['findDraftById', entityId], () => findDraftByEntityId(entityId), {
        retry: 0
    })

    const hasDraft = query.data

    const templateQuery = useQuery(['getTemplate', hasDraft], getTemplate, {
        retry: 0,
        enabled: !query.isLoading && !hasDraft
    })

    const [version, setVersion] = useState('latest')

    const mutation = useMutation(createDraft, {
        onSuccess: () => {
            queryClient.invalidateQueries('drafts')
            setVersion('draft')
        },
    })

    if (hasDraft)
        return <NgSelectRenderer
            id="select-version"
            value={version}
            label={' '}
            ngOptions={{ spread: true }}
            onChange={setVersion}
            options={['latest', 'draft']} />
    else {
        return <Button
            type="primary"
            className="btn-sm ms-1"
            onClick={() => {
                mutation.mutate({
                    ...templateQuery.data,
                    kind: entityId.split("_")[1],
                    id: entityId,
                    name: entityId
                })
            }}>
            <i className='fas fa-hammer me-1' />Create draft version
        </Button>
    }
}

export function DraftEditorContainer(props) {

    return <QueryClientProvider client={queryClient}>
        <DraftEditor {...props} />
    </QueryClientProvider>
}
