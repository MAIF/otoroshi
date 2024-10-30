import React, { useEffect, useRef } from 'react'
import {
    useQuery,
    useMutation,
    QueryClient,
    QueryClientProvider,
} from 'react-query'
import { withRouter, useLocation } from 'react-router-dom'
import { useSignalValue } from 'signals-react-safe'

import { nextClient } from '../../services/BackOfficeServices'
import { draftSignal, draftVersionSignal, entityContentSignal, resetDraftSignal, updateEntityURLSignal } from './DraftEditorSignal'
import { PillButton } from '../PillButton'
import JsonViewCompare from './Compare'
import { Button } from '../Button'

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
            draftSignal.value = {
                draft: data.content,
                rawDraft: data
            }
            draftVersionSignal.value = {
                version: 'draft',
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

        if (this.props.updateEntityURL)
            this.props.updateEntityURL()

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
            console.log('[DraftStateDaemon] : componentDidUpdate')
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



function PublisDraftModalContent() {
    const draftContext = useSignalValue(draftSignal)
    const entityContent = useSignalValue(entityContentSignal)

    return <div className='mt-3 d-flex flex-column' style={{ flex: 1 }}>
        <JsonViewCompare oldData={entityContent} newData={draftContext.draft} />
    </div>
}

export function PublisDraftButton(props) {
    const publish = useSignalValue(draftVersionSignal)
    const { pathname } = useLocation()

    const isFirstRender = useRef(true)

    useEffect(() => {
        if (isFirstRender.current) {
            isFirstRender.current = false
        } else {
            console.log('reset after pathname changed', pathname)
            resetDraftSignal()
        }
    }, [pathname])

    if (publish.version === 'published')
        return null

    return <Button text="Publish draft" className={`btn-sm ${props.className ? props.className : 'ms-auto'}`} type="primaryColor" style={{
        borderColor: 'var(--color-primary)'
    }} onClick={() => {
        window.wizard(
            'Publish this draft',
            () => <PublisDraftModalContent />,
            {
                style: { width: '100%' },
                noCancel: false,
                okClassName: "ms-2",
                okLabel: 'I want to publish this route'
            }
        )
            .then((ok) => {
                if (ok) {
                    if (updateEntityURLSignal && typeof updateEntityURLSignal.value === 'function') {
                        try {
                            updateEntityURLSignal.value()
                                .then(() => window.location.reload())
                        } catch (err) {
                            console.log(err)
                            alert("Something bad happened.")
                        }
                    }
                }
            });
    }} />
}
