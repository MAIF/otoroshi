import React, { useContext } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { SidebarContext } from '../../apps/BackOfficeApp';
import { NgSelectRenderer } from '../../components/nginputs';
import Select, { components } from 'react-select';
import { signalVersion } from './VersionSignal';
import { useSignalValue } from 'signals-react-safe';

const LINKS = (id) =>
    [
        {
            to: `/apis/${id}`,
            icon: 'fa-file-alt',
            title: 'Overview',
            tab: 'overview',
            tooltip: { ...createTooltip(`Show overview tab`) },
        },
        {
            to: `/apis/${id}/informations`,
            icon: 'fa-file-alt',
            title: 'Informations',
            tab: 'informations',
            tooltip: { ...createTooltip(`Show informations tab`) },
        },
        {
            to: `/apis/${id}/routes`,
            icon: 'fa-road',
            title: 'Routes',
            tab: 'routes',
            tooltip: { ...createTooltip(`Show routes tab`) },
        },
        {
            to: `/apis/${id}/backends`,
            icon: 'fa-server',
            title: 'Backends',
            tab: 'backends',
            tooltip: { ...createTooltip(`Show backends tab`) },
        },
        {
            to: `/apis/${id}/flows`,
            icon: 'fa-project-diagram',
            title: 'Flows',
            tab: 'flows',
            tooltip: { ...createTooltip(`Show flows tab`) },
        },
        {
            to: `/apis/${id}/consumers`,
            icon: 'fa-list',
            title: 'Consumers',
            tab: 'Consumers',
            tooltip: { ...createTooltip(`Show consumers tab`) },
        },
        {
            to: `/apis/${id}/subscriptions`,
            icon: 'fa-key',
            title: 'Subscriptions',
            tab: 'Subscriptions',
            tooltip: { ...createTooltip(`Show subscriptions tab`) },
        },
        // {
        //     to: `/apis/${id}/playground`,
        //     icon: 'fa-play',
        //     title: 'API Playground',
        //     tab: 'playground',
        //     tooltip: { ...createTooltip(`Show playground tab`) },
        // },
        {
            to: `/apis/${id}/deployments`,
            icon: 'fa-server',
            title: 'Deployments',
            tab: 'deployments',
            tooltip: { ...createTooltip(`Show deployments tab`) },
        },
        {
            to: `/apis/${id}/testing`,
            icon: 'fa-play',
            title: 'Testing',
            tab: 'testing',
            tooltip: { ...createTooltip(`Show testing tab`) },
        }
    ].filter((link) => !link.enabled);

export default (props) => {
    const location = useLocation();

    const params = props.params

    const { openedSidebar } = useContext(SidebarContext);

    const currentTab = location.pathname.split('/').slice(-1)[0];

    const noneTabIsActive = !LINKS().find(r => r.tab === currentTab)

    const isActive = (tab) => {
        if (tab === 'overview' && noneTabIsActive)
            return 'active'

        return currentTab === tab ? 'active' : null
    };

    const isOnApisHome = location.pathname.endsWith("/apis")

    const version = useSignalValue(signalVersion)

    return (
        <div style={{
            padding: openedSidebar ? 'inherit' : '12px 0 6px',
        }}>
            <ul className="nav flex-column nav-sidebar">
                <li className={`nav-item mb-3 ${openedSidebar ? 'nav-item--open' : ''}`} key="APIs">
                    <Link
                        to={`/apis`}
                        {...createTooltip(`apis - All your apis`)}
                        className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-3' : ''} m-0`}>
                        <div style={{ width: '20px' }} className="d-flex justify-content-center">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="size-6">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M9.53 16.122a3 3 0 0 0-5.78 1.128 2.25 2.25 0 0 1-2.4 2.245 4.5 4.5 0 0 0 8.4-2.245c0-.399-.078-.78-.22-1.128Zm0 0a15.998 15.998 0 0 0 3.388-1.62m-5.043-.025a15.994 15.994 0 0 1 1.622-3.395m3.42 3.42a15.995 15.995 0 0 0 4.764-4.648l3.876-5.814a1.151 1.151 0 0 0-1.597-1.597L14.146 6.32a15.996 15.996 0 0 0-4.649 4.763m3.42 3.42a6.776 6.776 0 0 0-3.42-3.42" />
                            </svg>

                        </div>
                        <div className="title"> {openedSidebar ? 'APIs' : ''}</div>
                    </Link>
                </li>
                {!isOnApisHome && <>
                    {openedSidebar && <p className="sidebar-title mt-3">General</p>}
                    <div className='me-1 my-2'>
                        {openedSidebar && version && version !== 'staging' && <Select
                            value={{ value: version, label: version }}
                            onChange={item => {
                                const queryParams = new URLSearchParams(window.location.search);
                                queryParams.set("version", item.value);
                                history.replaceState(null, null, "?" + queryParams.toString());
                                window.location.reload()
                            }}
                            isClearable={false}
                            isSearchable={false}
                            components={{
                                IndicatorSeparator: () => null,
                                SingleValue: ({ children, ...props }) => {
                                    return <div className='d-flex align-items-center m-0' style={{
                                        gap: '.5rem'
                                    }}>
                                        <span className={`badge ${props.data.value === 'Draft' ? 'bg-warning' : 'bg-danger'}`}>
                                            {props.data.label === 'Published' ? 'PROD' : 'DEV'}
                                        </span>{props.data.label}
                                    </div>
                                }
                            }}
                            options={['Published', 'Draft'].map(r => ({ value: r, label: r }))}
                            styles={{
                                control: (baseStyles) => ({
                                    ...baseStyles,
                                    border: '1px solid var(--bg-color_level3)',
                                    color: 'var(--text)',
                                    backgroundColor: 'var(--bg-color_level2)',
                                    boxShadow: 'none',
                                }),
                                valueContainer: (baseStyles) => ({
                                    ...baseStyles,
                                    display: 'flex'
                                }),
                                menu: (baseStyles) => ({
                                    ...baseStyles,
                                    margin: 0,
                                    borderTopLeftRadius: 0,
                                    borderTopRightRadius: 0,
                                    backgroundColor: 'var(--bg-color_level2)',
                                    color: 'var(--text)',
                                }),
                                option: (provided, { isFocused }) => ({
                                    ...provided,
                                    backgroundColor: isFocused ? 'var(--bg-color_level2)' : 'var(--bg-color_level3)',
                                }),
                                MenuList: (provided) => ({
                                    ...provided,
                                    background: 'red',
                                })
                            }}
                        />}
                    </div>
                    {LINKS(params.apiId).map(({ to, icon, title, tooltip, tab }) => (
                        <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={title}>
                            <Link
                                to={{
                                    pathname: to,
                                    search: location.search
                                }}
                                {...(tooltip || {})}
                                className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''
                                    } m-0 ${isActive(tab)}`}>
                                <div style={{ width: '20px' }} className="d-flex justify-content-center">
                                    <i className={`fas ${icon}`} />
                                </div>
                                <div className="title"> {openedSidebar ? title : ''}</div>
                            </Link>
                        </li>
                    ))}
                    {openedSidebar && <p className="sidebar-title mt-3">Monitoring</p>}
                </>}
            </ul>
        </div>
    );
};
