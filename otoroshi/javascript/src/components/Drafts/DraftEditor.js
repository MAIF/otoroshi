import React from 'react'
import {
    useQuery,
    useMutation,
    QueryClient,
    QueryClientProvider,
} from 'react-query'
import { nextClient } from '../../services/BackOfficeServices'
import { draftSignal, draftVersionSignal, entityContentSignal, resetDraftSignal } from './DraftEditorSignal'
import { useSignalValue } from 'signals-react-safe'
import { PillButton } from '../PillButton'
import { withRouter } from 'react-router-dom'

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

function updateSignalFromQuery(response, entityId) {

    if (!response.error) {
        draftSignal.value = {
            draft: response.content,
            rawDraft: response,
        }
        draftVersionSignal.value = {
            version: 'published',
            entityId
        }
    }
    else {
        draftSignal.value = {
            draft: undefined,
            rawDraft: undefined
        }
        draftVersionSignal.value = {
            version: 'published',
            notFound: true,
            entityId
        }
    }
}

function DraftEditor({ entityId, value, className = "" }) {
    const versionContext = useSignalValue(draftVersionSignal)
    const draftContext = useSignalValue(draftSignal)

    const query = useQuery(['findDraftById', entityId], () => findDraftByEntityId(entityId), {
        retry: 0,
        enabled: !draftContext.draft && !versionContext.notFound,
        onSuccess: data => updateSignalFromQuery(data, entityId)
    })

    const hasDraft = draftContext.draft

    const templateQuery = useQuery(['getTemplate', hasDraft], getTemplate, {
        retry: 0,
        enabled: !query.isLoading && !hasDraft
    })

    const mutation = useMutation(createDraft, {
        onSuccess: (data) => {
            draftVersionSignal.value = {
                version: 'draft',
            }
            draftSignal.value = {
                draft: data.content,
                rawDraft: data
            }
        },
    })

    const onVersionChange = newVersion => {
        if (!hasDraft) {
            mutation.mutate({
                ...templateQuery.data,
                kind: entityId.split("_")[1],
                id: entityId,
                name: entityId,
                content: value
            })
        } else {
            if (newVersion !== versionContext.version) {
                draftVersionSignal.value = {
                    ...draftVersionSignal.value,
                    version: newVersion,
                };
            }
        }
    }

    return <PillButton
        className={`mx-auto ${className}`}
        rightEnabled={versionContext.version !== 'draft'}
        leftText="Published"
        rightText="Draft"
        onChange={isPublished => onVersionChange(isPublished ? 'published' : 'draft')} />
}

export function DraftEditorContainer(props) {
    return <QueryClientProvider client={queryClient}>
        <DraftEditor {...props} />
    </QueryClientProvider>
}


export const DraftStateDaemon = withRouter(class _ extends React.Component {

    state = {
        initialized: false
    }

    componentDidMount() {
        resetDraftSignal(this.props)

        this.unsubscribe = draftVersionSignal.subscribe(() => {
            const { value, setValue } = this.props

            if (draftSignal.value.draft && value) {
                if (draftVersionSignal.value.version === 'draft') {
                    entityContentSignal.value = value

                    setValue(draftSignal.value.draft)
                } else {
                    if (this.state.initialized) {
                        draftSignal.value = {
                            ...draftSignal.value,
                            draft: value
                        }
                        setValue(entityContentSignal.value ? entityContentSignal.value : value)
                    } else {
                        this.setState({ initialized: true })
                    }
                }
            }
        })
    }

    componentDidUpdate(prevProps) {
        if (prevProps.value !== this.props.value) {
            if (draftVersionSignal.value.version === 'published') {
                entityContentSignal.value = this.props.value
            } else {
                draftSignal.value = {
                    ...draftSignal.value,
                    draft: this.props.value
                }
            }
        }
        if (prevProps.history.location.pathname !== this.props.history.location.pathname) {
            resetDraftSignal(this.props)
        }
    }

    componentWillUnmount() {
        if (this.unsubscribe)
            this.unsubscribe()
    }

    render() {
        return null
    }
})