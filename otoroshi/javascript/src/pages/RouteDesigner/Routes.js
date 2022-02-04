import React, { useEffect } from "react"
import { Link, useParams } from "react-router-dom";
import { Table } from "../../components/inputs";
import { findRoutes } from '../../services/BackOfficeServices'

export default ({ setTitle }) => {
    const params = useParams()

    useEffect(() => {

    }, [])

    const columns = [
        { title: 'Name', content: item => item.name }
    ];

    return <Table
        parentProps={{
            params
        }}
        selfUrl="routes"
        defaultTitle="Routes"
        itemName='Route'
        formSchema={null}
        formFlow={null}
        columns={columns}
        fetchItems={findRoutes}
        showActions={false}
        showLink={false}
        extractKey={item => item['@id']}
        injectTopBar={() => <Link
            className='btn btn-success'
            to={'routes/new'}>
            <i className="fas fa-plus-circle" /> Create new route
        </Link>}
    />
}