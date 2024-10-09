import React from 'react'
import {
    useQuery,
    useMutation,
    QueryClient,
    QueryClientProvider,
} from 'react-query'
import { nextClient } from '../../services/BackOfficeServices'
import { Button } from '../Button'
import { draftSignal } from './DraftEditorSignal'
import { useSignalValue } from 'signals-react-safe'
import { PillButton } from '../PillButton'

const queryClient = new QueryClient()

function findDraftByEntityId(id) {
    return nextClient
        .forEntityNext(nextClient.ENTITIES.DRAFTS)
        .findById(id)
}

function getTemplate() {
    return nextClient
        .forEntityNext(nextClient.ENTITIES.DRAFTS)
        .template()
}

function createDraft(newDraft) {
    return nextClient
        .forEntityNext(nextClient.ENTITIES.DRAFTS)
        .create(newDraft)
}

function updateSignalFromQuery(response) {
    if (!response.error)
        draftSignal.value = {
            version: 'latest',
            content: response.content,
            rawDraft: response
        }
    else {
        draftSignal.value = {
            version: 'latest',
            content: undefined,
            rawDraft: undefined
        }
    }
}

function DraftEditor({ entityId, value }) {
    const context = useSignalValue(draftSignal)

    const query = useQuery(['findDraftById', entityId], () => findDraftByEntityId(entityId), {
        retry: 0,
        enabled: !context.content,
        onSuccess: updateSignalFromQuery
    })

    const hasDraft = context.content

    const templateQuery = useQuery(['getTemplate', hasDraft], getTemplate, {
        retry: 0,
        enabled: !query.isLoading && !hasDraft
    })

    const mutation = useMutation(createDraft, {
        onSuccess: (data) => {
            draftSignal.value = {
                version: 'draft',
                content: data.content,
                rawDraft: data
            }
        },
    })

    const onVersionChange = newVersion => {
        if (newVersion !== context.version)
            draftSignal.value = {
                ...draftSignal.value,
                version: newVersion,
            };
    }

    if (hasDraft) {
        return <>
            <PillButton
                className='me-3'
                rightEnabled={context.version !== 'draft'}
                leftText="Latest"
                rightText="Draft"
                onChange={isLatest => onVersionChange(isLatest ? 'latest' : 'draft')} />
        </>
    }
    else {
        return <Button
            type="primary"
            className="btn-sm ms-1"
            onClick={() => {
                mutation.mutate({
                    ...templateQuery.data,
                    kind: entityId.split("_")[1],
                    id: entityId,
                    name: entityId,
                    content: value
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
