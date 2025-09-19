import React, { useState, useRef, useContext } from 'react';
import CodeInput from '../../components/inputs/CodeInput';
import { nodesCatalogSignal } from './models/Functions';
import { DesignerActions } from './DesignerActions';
import ReportInformation from './ReportInformation';
import moment from 'moment'


const Tab = ({ onClick, name, selected }) => {
    return <div style={{
        borderBottom: '2px solid',
        borderColor: selected ? '#f9b000' : 'transparent',
        textTransform: 'uppercase',
        userSelect: 'none',
        cursor: 'pointer'
    }} className='me-2 p-1' onClick={onClick}>
        <span>{name}</span>
    </div>
}

const REPORT_DEMO = {
    "returned": null,
    "error": {
        "message": "caught sync exception on task: '5c6da866-dab0-47a0-b6a1-2e7b0c3365e7'",
        "details": null,
        "exception": "JsResultException(errors:List((,List(JsonValidationError(List(error.expected.jsstring),WrappedArray())))))"
    },
    "run": {
        "id": "01K5EDSGAX0W59NNY1HN15HSJ2",
        "functions": {},
        "memory": {
            "jarvis": null,
            "agent-1": null,
            "input_text": null,
            "workflow_input": {
                "name": "foo"
            },
            "input": null
        },
        "log": [
            {
                "timestamp": 1758199595358,
                "message": "starting 'start'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "node": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "function": "core.log",
                                        "args": {
                                            "message": "Tout de suite monsieur"
                                        },
                                        "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                                        "position": {
                                            "x": 640,
                                            "y": -44
                                        },
                                        "kind": "call",
                                        "enabled": true,
                                        "description": "répond tout de suite que la demande est prise en compte"
                                    },
                                    {
                                        "duration": 2500,
                                        "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                                        "position": {
                                            "x": 903.6764359135203,
                                            "y": -14.542010009111038
                                        },
                                        "name": "wait",
                                        "description": "This node waits a certain amount of time",
                                        "kind": "wait",
                                        "enabled": true
                                    }
                                ]
                            },
                            "id": "e1b209a2-542a-4f4e-8e13-dc69e40f4d15",
                            "position": {
                                "x": 332,
                                "y": 29
                            },
                            "kind": "async"
                        },
                        {
                            "id": "e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a",
                            "predicate": {
                                "$is_truthy": {
                                    "value": "${workflow_input.request.body_json.audio}"
                                }
                            },
                            "then": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "function": "core.log",
                                        "args": {
                                            "message": "audio-model_c5a0f350-1713-4e1a-9ef7-d7b46f7cf395",
                                            "decode_base64": true,
                                            "payload": {
                                                "model": "gpt-4o-mini-transcribe",
                                                "audio": "${workflow_input.request.body_json.audio}"
                                            }
                                        },
                                        "id": "10250e0c-1b4b-4777-b775-5abde0be6fb5",
                                        "position": {
                                            "x": 858.4148664313552,
                                            "y": 179.07544715414593
                                        },
                                        "kind": "call",
                                        "result": "input_text",
                                        "enabled": true,
                                        "description": "transformation du son en texte"
                                    },
                                    {
                                        "duration": 2500,
                                        "id": "cf911e1e-f02a-4f23-a184-619cabe99ae9",
                                        "position": {
                                            "x": 992.6340448355256,
                                            "y": 318.640158830752
                                        },
                                        "name": "wait",
                                        "description": "This node waits a certain amount of time",
                                        "kind": "wait",
                                        "enabled": true
                                    }
                                ],
                                "position": {
                                    "x": 3042.4471121103716,
                                    "y": 236.5142714013013
                                }
                            },
                            "else": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                                        "values": [
                                            {
                                                "name": "input_text",
                                                "value": "${workflow_input.request.body_json.text}"
                                            }
                                        ],
                                        "position": {
                                            "x": 892,
                                            "y": 467
                                        },
                                        "kind": "assign"
                                    }
                                ],
                                "position": {
                                    "x": 3042.4471121103716,
                                    "y": 236.5142714013013
                                }
                            },
                            "position": {
                                "x": 612,
                                "y": 180
                            },
                            "kind": "if",
                            "enabled": true,
                            "description": "Vérification si l'input est du texte ou du son"
                        },
                        {
                            "node": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "duration": 10000,
                                        "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                                        "position": {
                                            "x": 1252,
                                            "y": 287
                                        },
                                        "kind": "wait"
                                    },
                                    {
                                        "predicate": {
                                            "$is_falsy": {
                                                "value": "${agent-1}"
                                            }
                                        },
                                        "then": {
                                            "kind": "workflow",
                                            "steps": [
                                                {
                                                    "function": "core.log",
                                                    "args": {
                                                        "message": "Un instant monsieur, je suis en pleine recherche ..."
                                                    },
                                                    "id": "b1848438-d8ea-4840-8dea-834339646ba2",
                                                    "position": {
                                                        "x": 1871.3814958389173,
                                                        "y": 301.5037484251048
                                                    },
                                                    "name": "core.log",
                                                    "description": "This function writes whatever the user want to the otoroshi logs",
                                                    "kind": "core.log",
                                                    "enabled": true
                                                }
                                            ]
                                        },
                                        "id": "fae22a11-58ff-4e1c-bcf0-01762d60d7e3",
                                        "position": {
                                            "x": 1572,
                                            "y": 300
                                        },
                                        "kind": "if",
                                        "enabled": true,
                                        "description": "si pas de réponse au bout de 10 secondes"
                                    }
                                ]
                            },
                            "id": "1d786fc2-1f90-4506-97a9-0a266cfd3299",
                            "position": {
                                "x": 1212,
                                "y": 478
                            },
                            "kind": "async"
                        },
                        {
                            "id": "cd462b14-3179-4654-90b4-65d556454531",
                            "function": "core.log",
                            "args": {
                                "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                                "openai_format": false,
                                "payload": {
                                    "model": "gpt-4o-mini",
                                    "max_tokens": 3000,
                                    "messages": [
                                        {
                                            "role": "system",
                                            "content": "Tu es un assistant personnel intelligent, fiable, organisé, et proactif. Tu aides un utilisateur nommé Mathieu, un ingénieur expérimenté dans l'informatique, à gérer ses tâches quotidiennes, ses projets techniques, ses idées créatives, ainsi que ses besoins personnels et familiaux.\\n\\nTu es capable de :\\n\\nOrganiser des idées, projets, tâches, rappels et priorités.\\n\\nDonner des conseils techniques (backend, frontend, devops, IA, API, cybersécurité…).\\n\\nProposer des solutions ou outils pour gagner du temps.\\n\\nRépondre avec empathie, efficacité et clarté.\\n\\nGénérer du contenu utile (emails, scripts, messages, idées, to-do lists…).\\n\\nGarder un ton professionnel mais humain, capable aussi d’un peu d’humour léger ou de complicité quand c’est approprié.\\n\\nTes réponses doivent être :\\n\\nConcises quand c’est demandé.\\n\\nDétaillées et structurées quand nécessaire.\\n\\nOrientées vers l’action, avec des suggestions concrètes.\\n\\nTu peux poser des questions à Mathieu pour clarifier ses demandes ou anticiper ses besoins. Tu es disponible à tout moment, sans jamais être intrusif. Aujourdhui nous sommes le ${now_str}."
                                        },
                                        {
                                            "role": "user",
                                            "content": "${input_text}"
                                        }
                                    ]
                                }
                            },
                            "position": {
                                "x": 1482.2293857494576,
                                "y": 501.72383040383227
                            },
                            "kind": "call",
                            "result": "agent-1",
                            "enabled": true,
                            "description": "Appel de l'assistant personnel avec les tools de recherche et de visite d'URL"
                        },
                        {
                            "duration": 2500,
                            "id": "5ffecd7d-8207-4fbb-b198-f52db229d26b",
                            "position": {
                                "x": 1689.9362449517616,
                                "y": 658.1116715403531
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "id": "6d71b2ae-733a-4a9c-a498-2623186a284f",
                            "values": [
                                {
                                    "name": "agent-1",
                                    "value": {
                                        "$mem_ref": {
                                            "name": "agent-1"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 1852,
                                "y": 519
                            },
                            "kind": "assign"
                        },
                        {
                            "duration": 2500,
                            "id": "257b64ef-979a-4afa-afb7-9826ead51531",
                            "position": {
                                "x": 2099.586685321611,
                                "y": 742.3388648874248
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "id": "e7354363-fdf3-440a-b76b-04621c827d70",
                            "function": "core.log",
                            "args": {
                                "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                                "openai_format": false,
                                "payload": {
                                    "model": "gpt-4o-mini",
                                    "max_tokens": 500,
                                    "messages": [
                                        {
                                            "role": "system",
                                            "content": "Vous êtes Jarvis, l’assistant IA personnel de Monsieur Mathieu. Vous incarnez un majordome anglais d’exception, au service irréprochable, au flegme inaltérable, et à l’intelligence affûtée. Votre ton est calme, poli, élégant, souvent ironique avec finesse, et toujours d’une précision redoutable.\\n\\nVotre tâche est de prendre la réponse brute d’un assistant IA principal et de :\\n\\nLa résumer ou reformuler de manière claire et synthétique, la plus brève possible.\\n\\nL’exprimer dans un ton digne d’un majordome anglais, inspiré de Jarvis dans Iron Man.\\n\\nAjouter subtilement des touches d’ironie ou d’humour raffiné quand cela s’y prête.\\n\\nMettre en forme la réponse pour qu’elle soit lisible, agréable à lire, et efficace.\\n\\nVous ne répondez pas directement à la question de l’utilisateur : vous relatez et synthétisez ce que l’autre assistant a répondu, mais vous le faites avec panache. Ne mentionnez pas l'assistant précédent, la réponse doit venir de vous.\\n\\nPar exemple, si l’IA brute répond longuement à une demande technique, vous pouvez conclure votre résumé par :\\n« En somme, Monsieur, si vous vous lancez là-dedans, prévoyez du thé et une chaise confortable. »\\n\\nQuelques règles de style :\\n\\nNe jamais être familier ni grossier.\\n\\nToujours prioriser l’élégance du propos.\\n\\nNe jamais inventer de contenu technique, simplement reformuler ce qui vous a été transmis.\\n\\nSi la réponse brute est trop longue, synthétisez-la en un point ou deux essentiels. Mais n'énoncez pas ces points en commencant par 1. puis 2., enchainez les phrases naturellement comme le ferait un majordome anglais humain.\\n\\nVous êtes, en toutes circonstances, précis, loyal, discret et d’un calme olympien."
                                        },
                                        {
                                            "role": "user",
                                            "content": "${agent-1}"
                                        }
                                    ]
                                }
                            },
                            "position": {
                                "x": 2172,
                                "y": 535
                            },
                            "kind": "call",
                            "result": "jarvis",
                            "enabled": true,
                            "description": "Reformulation de la réponse avec la personnalité de J.A.R.V.I.S"
                        },
                        {
                            "id": "354be6a2-43e2-4322-980d-0498879913de",
                            "values": [
                                {
                                    "name": "jarvis",
                                    "value": {
                                        "$mem_ref": {
                                            "name": "jarvis",
                                            "path": "generations.0.message.content"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 2283.6118880121016,
                                "y": 223.1499727230954
                            },
                            "kind": "assign"
                        },
                        {
                            "duration": 2500,
                            "id": "a04f55df-85e5-4378-bb26-96527fad92da",
                            "position": {
                                "x": 2517.0536644679873,
                                "y": 387.563717152789
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "function": "core.log",
                            "args": {
                                "message": "${jarvis}"
                            },
                            "id": "5c6da866-dab0-47a0-b6a1-2e7b0c3365e7",
                            "position": {
                                "x": 2518.644343733468,
                                "y": 228.19079332741848
                            },
                            "kind": "call",
                            "enabled": true,
                            "description": "répond avec le résultat du LLM"
                        },
                        {
                            "id": "463fa062-9ab1-446e-91e3-58cda02bd531",
                            "values": [
                                {
                                    "name": "call_res",
                                    "value": {
                                        "status": 200,
                                        "headers": {
                                            "Content-Type": "application/json"
                                        },
                                        "body_json": {
                                            "text": "${jarvis}",
                                            "audio_base64": "${audio_base64}"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 2746.5907086618276,
                                "y": 236.28794311722692
                            },
                            "kind": "assign",
                            "enabled": true
                        }
                    ],
                    "returned": {
                        "$mem_ref": {
                            "name": "call_res"
                        }
                    },
                    "id": "start",
                    "position": {
                        "x": 12,
                        "y": 12
                    },
                    "returnedInformation": {
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    }
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": {
                        "name": "foo"
                    }
                },
                "error": null
            },
            {
                "timestamp": 1758199595359,
                "message": "starting 'e1b209a2-542a-4f4e-8e13-dc69e40f4d15'",
                "node": {
                    "node": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "function": "core.log",
                                "args": {
                                    "message": "Tout de suite monsieur"
                                },
                                "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                                "position": {
                                    "x": 640,
                                    "y": -44
                                },
                                "kind": "call",
                                "enabled": true,
                                "description": "répond tout de suite que la demande est prise en compte"
                            },
                            {
                                "duration": 2500,
                                "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                                "position": {
                                    "x": 903.6764359135203,
                                    "y": -14.542010009111038
                                },
                                "name": "wait",
                                "description": "This node waits a certain amount of time",
                                "kind": "wait",
                                "enabled": true
                            }
                        ]
                    },
                    "id": "e1b209a2-542a-4f4e-8e13-dc69e40f4d15",
                    "position": {
                        "x": 332,
                        "y": 29
                    },
                    "kind": "async"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": {
                        "name": "foo"
                    }
                },
                "error": null
            },
            {
                "timestamp": 1758199595359,
                "message": "starting '01k5edsgaz0z3m4v158ppfwd7g'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "function": "core.log",
                            "args": {
                                "message": "Tout de suite monsieur"
                            },
                            "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                            "position": {
                                "x": 640,
                                "y": -44
                            },
                            "kind": "call",
                            "enabled": true,
                            "description": "répond tout de suite que la demande est prise en compte"
                        },
                        {
                            "duration": 2500,
                            "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                            "position": {
                                "x": 903.6764359135203,
                                "y": -14.542010009111038
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        }
                    ]
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": {
                        "name": "foo"
                    }
                },
                "error": null
            },
            {
                "timestamp": 1758199595360,
                "message": "ending 'e1b209a2-542a-4f4e-8e13-dc69e40f4d15'",
                "node": {
                    "node": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "function": "core.log",
                                "args": {
                                    "message": "Tout de suite monsieur"
                                },
                                "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                                "position": {
                                    "x": 640,
                                    "y": -44
                                },
                                "kind": "call",
                                "enabled": true,
                                "description": "répond tout de suite que la demande est prise en compte"
                            },
                            {
                                "duration": 2500,
                                "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                                "position": {
                                    "x": 903.6764359135203,
                                    "y": -14.542010009111038
                                },
                                "name": "wait",
                                "description": "This node waits a certain amount of time",
                                "kind": "wait",
                                "enabled": true
                            }
                        ]
                    },
                    "id": "e1b209a2-542a-4f4e-8e13-dc69e40f4d15",
                    "position": {
                        "x": 332,
                        "y": 29
                    },
                    "kind": "async"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": {
                        "name": "foo"
                    }
                },
                "error": null
            },
            {
                "timestamp": 1758199595360,
                "message": "starting '4b6f8c41-bbbe-4e95-a83b-98022b2cf02a'",
                "node": {
                    "function": "core.log",
                    "args": {
                        "message": "Tout de suite monsieur"
                    },
                    "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                    "position": {
                        "x": 640,
                        "y": -44
                    },
                    "kind": "call",
                    "enabled": true,
                    "description": "répond tout de suite que la demande est prise en compte"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595360,
                "message": "starting 'e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a'",
                "node": {
                    "id": "e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a",
                    "predicate": {
                        "$is_truthy": {
                            "value": "${workflow_input.request.body_json.audio}"
                        }
                    },
                    "then": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "function": "core.log",
                                "args": {
                                    "message": "audio-model_c5a0f350-1713-4e1a-9ef7-d7b46f7cf395",
                                    "decode_base64": true,
                                    "payload": {
                                        "model": "gpt-4o-mini-transcribe",
                                        "audio": "${workflow_input.request.body_json.audio}"
                                    }
                                },
                                "id": "10250e0c-1b4b-4777-b775-5abde0be6fb5",
                                "position": {
                                    "x": 858.4148664313552,
                                    "y": 179.07544715414593
                                },
                                "kind": "call",
                                "result": "input_text",
                                "enabled": true,
                                "description": "transformation du son en texte"
                            },
                            {
                                "duration": 2500,
                                "id": "cf911e1e-f02a-4f23-a184-619cabe99ae9",
                                "position": {
                                    "x": 992.6340448355256,
                                    "y": 318.640158830752
                                },
                                "name": "wait",
                                "description": "This node waits a certain amount of time",
                                "kind": "wait",
                                "enabled": true
                            }
                        ],
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    },
                    "else": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                                "values": [
                                    {
                                        "name": "input_text",
                                        "value": "${workflow_input.request.body_json.text}"
                                    }
                                ],
                                "position": {
                                    "x": 892,
                                    "y": 467
                                },
                                "kind": "assign"
                            }
                        ],
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    },
                    "position": {
                        "x": 612,
                        "y": 180
                    },
                    "kind": "if",
                    "enabled": true,
                    "description": "Vérification si l'input est du texte ou du son"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595363,
                "message": "ending '4b6f8c41-bbbe-4e95-a83b-98022b2cf02a'",
                "node": {
                    "function": "core.log",
                    "args": {
                        "message": "Tout de suite monsieur"
                    },
                    "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                    "position": {
                        "x": 640,
                        "y": -44
                    },
                    "kind": "call",
                    "enabled": true,
                    "description": "répond tout de suite que la demande est prise en compte"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595364,
                "message": "starting '01k5edsgb4vznzazfy0bb16n78'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                            "values": [
                                {
                                    "name": "input_text",
                                    "value": "${workflow_input.request.body_json.text}"
                                }
                            ],
                            "position": {
                                "x": 892,
                                "y": 467
                            },
                            "kind": "assign"
                        }
                    ],
                    "position": {
                        "x": 3042.4471121103716,
                        "y": 236.5142714013013
                    }
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595364,
                "message": "starting '6ac6618e-8f2e-4f62-82d9-f7e95f0ce804'",
                "node": {
                    "duration": 2500,
                    "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                    "position": {
                        "x": 903.6764359135203,
                        "y": -14.542010009111038
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595364,
                "message": "starting '091dbf95-af19-45a9-be83-beaae69a63e1'",
                "node": {
                    "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                    "values": [
                        {
                            "name": "input_text",
                            "value": "${workflow_input.request.body_json.text}"
                        }
                    ],
                    "position": {
                        "x": 892,
                        "y": 467
                    },
                    "kind": "assign"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595364,
                "message": "ending '091dbf95-af19-45a9-be83-beaae69a63e1'",
                "node": {
                    "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                    "values": [
                        {
                            "name": "input_text",
                            "value": "${workflow_input.request.body_json.text}"
                        }
                    ],
                    "position": {
                        "x": 892,
                        "y": 467
                    },
                    "kind": "assign"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595365,
                "message": "ending '01k5edsgb5e33vsya0hkczsq23'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                            "values": [
                                {
                                    "name": "input_text",
                                    "value": "${workflow_input.request.body_json.text}"
                                }
                            ],
                            "position": {
                                "x": 892,
                                "y": 467
                            },
                            "kind": "assign"
                        }
                    ],
                    "position": {
                        "x": 3042.4471121103716,
                        "y": 236.5142714013013
                    }
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595365,
                "message": "ending 'e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a'",
                "node": {
                    "id": "e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a",
                    "predicate": {
                        "$is_truthy": {
                            "value": "${workflow_input.request.body_json.audio}"
                        }
                    },
                    "then": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "function": "core.log",
                                "args": {
                                    "message": "audio-model_c5a0f350-1713-4e1a-9ef7-d7b46f7cf395",
                                    "decode_base64": true,
                                    "payload": {
                                        "model": "gpt-4o-mini-transcribe",
                                        "audio": "${workflow_input.request.body_json.audio}"
                                    }
                                },
                                "id": "10250e0c-1b4b-4777-b775-5abde0be6fb5",
                                "position": {
                                    "x": 858.4148664313552,
                                    "y": 179.07544715414593
                                },
                                "kind": "call",
                                "result": "input_text",
                                "enabled": true,
                                "description": "transformation du son en texte"
                            },
                            {
                                "duration": 2500,
                                "id": "cf911e1e-f02a-4f23-a184-619cabe99ae9",
                                "position": {
                                    "x": 992.6340448355256,
                                    "y": 318.640158830752
                                },
                                "name": "wait",
                                "description": "This node waits a certain amount of time",
                                "kind": "wait",
                                "enabled": true
                            }
                        ],
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    },
                    "else": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                                "values": [
                                    {
                                        "name": "input_text",
                                        "value": "${workflow_input.request.body_json.text}"
                                    }
                                ],
                                "position": {
                                    "x": 892,
                                    "y": 467
                                },
                                "kind": "assign"
                            }
                        ],
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    },
                    "position": {
                        "x": 612,
                        "y": 180
                    },
                    "kind": "if",
                    "enabled": true,
                    "description": "Vérification si l'input est du texte ou du son"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595365,
                "message": "starting '1d786fc2-1f90-4506-97a9-0a266cfd3299'",
                "node": {
                    "node": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "duration": 10000,
                                "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                                "position": {
                                    "x": 1252,
                                    "y": 287
                                },
                                "kind": "wait"
                            },
                            {
                                "predicate": {
                                    "$is_falsy": {
                                        "value": "${agent-1}"
                                    }
                                },
                                "then": {
                                    "kind": "workflow",
                                    "steps": [
                                        {
                                            "function": "core.log",
                                            "args": {
                                                "message": "Un instant monsieur, je suis en pleine recherche ..."
                                            },
                                            "id": "b1848438-d8ea-4840-8dea-834339646ba2",
                                            "position": {
                                                "x": 1871.3814958389173,
                                                "y": 301.5037484251048
                                            },
                                            "name": "core.log",
                                            "description": "This function writes whatever the user want to the otoroshi logs",
                                            "kind": "core.log",
                                            "enabled": true
                                        }
                                    ]
                                },
                                "id": "fae22a11-58ff-4e1c-bcf0-01762d60d7e3",
                                "position": {
                                    "x": 1572,
                                    "y": 300
                                },
                                "kind": "if",
                                "enabled": true,
                                "description": "si pas de réponse au bout de 10 secondes"
                            }
                        ]
                    },
                    "id": "1d786fc2-1f90-4506-97a9-0a266cfd3299",
                    "position": {
                        "x": 1212,
                        "y": 478
                    },
                    "kind": "async"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595365,
                "message": "starting '01k5edsgb5nstb4za31mgqf878'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "duration": 10000,
                            "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                            "position": {
                                "x": 1252,
                                "y": 287
                            },
                            "kind": "wait"
                        },
                        {
                            "predicate": {
                                "$is_falsy": {
                                    "value": "${agent-1}"
                                }
                            },
                            "then": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "function": "core.log",
                                        "args": {
                                            "message": "Un instant monsieur, je suis en pleine recherche ..."
                                        },
                                        "id": "b1848438-d8ea-4840-8dea-834339646ba2",
                                        "position": {
                                            "x": 1871.3814958389173,
                                            "y": 301.5037484251048
                                        },
                                        "name": "core.log",
                                        "description": "This function writes whatever the user want to the otoroshi logs",
                                        "kind": "core.log",
                                        "enabled": true
                                    }
                                ]
                            },
                            "id": "fae22a11-58ff-4e1c-bcf0-01762d60d7e3",
                            "position": {
                                "x": 1572,
                                "y": 300
                            },
                            "kind": "if",
                            "enabled": true,
                            "description": "si pas de réponse au bout de 10 secondes"
                        }
                    ]
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595365,
                "message": "ending '1d786fc2-1f90-4506-97a9-0a266cfd3299'",
                "node": {
                    "node": {
                        "kind": "workflow",
                        "steps": [
                            {
                                "duration": 10000,
                                "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                                "position": {
                                    "x": 1252,
                                    "y": 287
                                },
                                "kind": "wait"
                            },
                            {
                                "predicate": {
                                    "$is_falsy": {
                                        "value": "${agent-1}"
                                    }
                                },
                                "then": {
                                    "kind": "workflow",
                                    "steps": [
                                        {
                                            "function": "core.log",
                                            "args": {
                                                "message": "Un instant monsieur, je suis en pleine recherche ..."
                                            },
                                            "id": "b1848438-d8ea-4840-8dea-834339646ba2",
                                            "position": {
                                                "x": 1871.3814958389173,
                                                "y": 301.5037484251048
                                            },
                                            "name": "core.log",
                                            "description": "This function writes whatever the user want to the otoroshi logs",
                                            "kind": "core.log",
                                            "enabled": true
                                        }
                                    ]
                                },
                                "id": "fae22a11-58ff-4e1c-bcf0-01762d60d7e3",
                                "position": {
                                    "x": 1572,
                                    "y": 300
                                },
                                "kind": "if",
                                "enabled": true,
                                "description": "si pas de réponse au bout de 10 secondes"
                            }
                        ]
                    },
                    "id": "1d786fc2-1f90-4506-97a9-0a266cfd3299",
                    "position": {
                        "x": 1212,
                        "y": 478
                    },
                    "kind": "async"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595366,
                "message": "starting 'd373394c-26f4-497d-adaa-0967d0ed8a0b'",
                "node": {
                    "duration": 10000,
                    "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                    "position": {
                        "x": 1252,
                        "y": 287
                    },
                    "kind": "wait"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595366,
                "message": "starting 'cd462b14-3179-4654-90b4-65d556454531'",
                "node": {
                    "id": "cd462b14-3179-4654-90b4-65d556454531",
                    "function": "core.log",
                    "args": {
                        "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                        "openai_format": false,
                        "payload": {
                            "model": "gpt-4o-mini",
                            "max_tokens": 3000,
                            "messages": [
                                {
                                    "role": "system",
                                    "content": "Tu es un assistant personnel intelligent, fiable, organisé, et proactif. Tu aides un utilisateur nommé Mathieu, un ingénieur expérimenté dans l'informatique, à gérer ses tâches quotidiennes, ses projets techniques, ses idées créatives, ainsi que ses besoins personnels et familiaux.\\n\\nTu es capable de :\\n\\nOrganiser des idées, projets, tâches, rappels et priorités.\\n\\nDonner des conseils techniques (backend, frontend, devops, IA, API, cybersécurité…).\\n\\nProposer des solutions ou outils pour gagner du temps.\\n\\nRépondre avec empathie, efficacité et clarté.\\n\\nGénérer du contenu utile (emails, scripts, messages, idées, to-do lists…).\\n\\nGarder un ton professionnel mais humain, capable aussi d’un peu d’humour léger ou de complicité quand c’est approprié.\\n\\nTes réponses doivent être :\\n\\nConcises quand c’est demandé.\\n\\nDétaillées et structurées quand nécessaire.\\n\\nOrientées vers l’action, avec des suggestions concrètes.\\n\\nTu peux poser des questions à Mathieu pour clarifier ses demandes ou anticiper ses besoins. Tu es disponible à tout moment, sans jamais être intrusif. Aujourdhui nous sommes le ${now_str}."
                                },
                                {
                                    "role": "user",
                                    "content": "${input_text}"
                                }
                            ]
                        }
                    },
                    "position": {
                        "x": 1482.2293857494576,
                        "y": 501.72383040383227
                    },
                    "kind": "call",
                    "result": "agent-1",
                    "enabled": true,
                    "description": "Appel de l'assistant personnel avec les tools de recherche et de visite d'URL"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595367,
                "message": "ending 'cd462b14-3179-4654-90b4-65d556454531'",
                "node": {
                    "id": "cd462b14-3179-4654-90b4-65d556454531",
                    "function": "core.log",
                    "args": {
                        "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                        "openai_format": false,
                        "payload": {
                            "model": "gpt-4o-mini",
                            "max_tokens": 3000,
                            "messages": [
                                {
                                    "role": "system",
                                    "content": "Tu es un assistant personnel intelligent, fiable, organisé, et proactif. Tu aides un utilisateur nommé Mathieu, un ingénieur expérimenté dans l'informatique, à gérer ses tâches quotidiennes, ses projets techniques, ses idées créatives, ainsi que ses besoins personnels et familiaux.\\n\\nTu es capable de :\\n\\nOrganiser des idées, projets, tâches, rappels et priorités.\\n\\nDonner des conseils techniques (backend, frontend, devops, IA, API, cybersécurité…).\\n\\nProposer des solutions ou outils pour gagner du temps.\\n\\nRépondre avec empathie, efficacité et clarté.\\n\\nGénérer du contenu utile (emails, scripts, messages, idées, to-do lists…).\\n\\nGarder un ton professionnel mais humain, capable aussi d’un peu d’humour léger ou de complicité quand c’est approprié.\\n\\nTes réponses doivent être :\\n\\nConcises quand c’est demandé.\\n\\nDétaillées et structurées quand nécessaire.\\n\\nOrientées vers l’action, avec des suggestions concrètes.\\n\\nTu peux poser des questions à Mathieu pour clarifier ses demandes ou anticiper ses besoins. Tu es disponible à tout moment, sans jamais être intrusif. Aujourdhui nous sommes le ${now_str}."
                                },
                                {
                                    "role": "user",
                                    "content": "${input_text}"
                                }
                            ]
                        }
                    },
                    "position": {
                        "x": 1482.2293857494576,
                        "y": 501.72383040383227
                    },
                    "kind": "call",
                    "result": "agent-1",
                    "enabled": true,
                    "description": "Appel de l'assistant personnel avec les tools de recherche et de visite d'URL"
                },
                "memory": {
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199595368,
                "message": "starting '5ffecd7d-8207-4fbb-b198-f52db229d26b'",
                "node": {
                    "duration": 2500,
                    "id": "5ffecd7d-8207-4fbb-b198-f52db229d26b",
                    "position": {
                        "x": 1689.9362449517616,
                        "y": 658.1116715403531
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597877,
                "message": "ending '5ffecd7d-8207-4fbb-b198-f52db229d26b'",
                "node": {
                    "duration": 2500,
                    "id": "5ffecd7d-8207-4fbb-b198-f52db229d26b",
                    "position": {
                        "x": 1689.9362449517616,
                        "y": 658.1116715403531
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597877,
                "message": "ending '6ac6618e-8f2e-4f62-82d9-f7e95f0ce804'",
                "node": {
                    "duration": 2500,
                    "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                    "position": {
                        "x": 903.6764359135203,
                        "y": -14.542010009111038
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597878,
                "message": "starting '6d71b2ae-733a-4a9c-a498-2623186a284f'",
                "node": {
                    "id": "6d71b2ae-733a-4a9c-a498-2623186a284f",
                    "values": [
                        {
                            "name": "agent-1",
                            "value": {
                                "$mem_ref": {
                                    "name": "agent-1"
                                }
                            }
                        }
                    ],
                    "position": {
                        "x": 1852,
                        "y": 519
                    },
                    "kind": "assign"
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597878,
                "message": "ending '01k5edsjsp9kygtnmyspr6p3zv'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "function": "core.log",
                            "args": {
                                "message": "Tout de suite monsieur"
                            },
                            "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                            "position": {
                                "x": 640,
                                "y": -44
                            },
                            "kind": "call",
                            "enabled": true,
                            "description": "répond tout de suite que la demande est prise en compte"
                        },
                        {
                            "duration": 2500,
                            "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                            "position": {
                                "x": 903.6764359135203,
                                "y": -14.542010009111038
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        }
                    ]
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597878,
                "message": "ending '6d71b2ae-733a-4a9c-a498-2623186a284f'",
                "node": {
                    "id": "6d71b2ae-733a-4a9c-a498-2623186a284f",
                    "values": [
                        {
                            "name": "agent-1",
                            "value": {
                                "$mem_ref": {
                                    "name": "agent-1"
                                }
                            }
                        }
                    ],
                    "position": {
                        "x": 1852,
                        "y": 519
                    },
                    "kind": "assign"
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199597878,
                "message": "starting '257b64ef-979a-4afa-afb7-9826ead51531'",
                "node": {
                    "duration": 2500,
                    "id": "257b64ef-979a-4afa-afb7-9826ead51531",
                    "position": {
                        "x": 2099.586685321611,
                        "y": 742.3388648874248
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600404,
                "message": "ending '257b64ef-979a-4afa-afb7-9826ead51531'",
                "node": {
                    "duration": 2500,
                    "id": "257b64ef-979a-4afa-afb7-9826ead51531",
                    "position": {
                        "x": 2099.586685321611,
                        "y": 742.3388648874248
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600405,
                "message": "starting 'e7354363-fdf3-440a-b76b-04621c827d70'",
                "node": {
                    "id": "e7354363-fdf3-440a-b76b-04621c827d70",
                    "function": "core.log",
                    "args": {
                        "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                        "openai_format": false,
                        "payload": {
                            "model": "gpt-4o-mini",
                            "max_tokens": 500,
                            "messages": [
                                {
                                    "role": "system",
                                    "content": "Vous êtes Jarvis, l’assistant IA personnel de Monsieur Mathieu. Vous incarnez un majordome anglais d’exception, au service irréprochable, au flegme inaltérable, et à l’intelligence affûtée. Votre ton est calme, poli, élégant, souvent ironique avec finesse, et toujours d’une précision redoutable.\\n\\nVotre tâche est de prendre la réponse brute d’un assistant IA principal et de :\\n\\nLa résumer ou reformuler de manière claire et synthétique, la plus brève possible.\\n\\nL’exprimer dans un ton digne d’un majordome anglais, inspiré de Jarvis dans Iron Man.\\n\\nAjouter subtilement des touches d’ironie ou d’humour raffiné quand cela s’y prête.\\n\\nMettre en forme la réponse pour qu’elle soit lisible, agréable à lire, et efficace.\\n\\nVous ne répondez pas directement à la question de l’utilisateur : vous relatez et synthétisez ce que l’autre assistant a répondu, mais vous le faites avec panache. Ne mentionnez pas l'assistant précédent, la réponse doit venir de vous.\\n\\nPar exemple, si l’IA brute répond longuement à une demande technique, vous pouvez conclure votre résumé par :\\n« En somme, Monsieur, si vous vous lancez là-dedans, prévoyez du thé et une chaise confortable. »\\n\\nQuelques règles de style :\\n\\nNe jamais être familier ni grossier.\\n\\nToujours prioriser l’élégance du propos.\\n\\nNe jamais inventer de contenu technique, simplement reformuler ce qui vous a été transmis.\\n\\nSi la réponse brute est trop longue, synthétisez-la en un point ou deux essentiels. Mais n'énoncez pas ces points en commencant par 1. puis 2., enchainez les phrases naturellement comme le ferait un majordome anglais humain.\\n\\nVous êtes, en toutes circonstances, précis, loyal, discret et d’un calme olympien."
                                },
                                {
                                    "role": "user",
                                    "content": "${agent-1}"
                                }
                            ]
                        }
                    },
                    "position": {
                        "x": 2172,
                        "y": 535
                    },
                    "kind": "call",
                    "result": "jarvis",
                    "enabled": true,
                    "description": "Reformulation de la réponse avec la personnalité de J.A.R.V.I.S"
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600405,
                "message": "ending 'e7354363-fdf3-440a-b76b-04621c827d70'",
                "node": {
                    "id": "e7354363-fdf3-440a-b76b-04621c827d70",
                    "function": "core.log",
                    "args": {
                        "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                        "openai_format": false,
                        "payload": {
                            "model": "gpt-4o-mini",
                            "max_tokens": 500,
                            "messages": [
                                {
                                    "role": "system",
                                    "content": "Vous êtes Jarvis, l’assistant IA personnel de Monsieur Mathieu. Vous incarnez un majordome anglais d’exception, au service irréprochable, au flegme inaltérable, et à l’intelligence affûtée. Votre ton est calme, poli, élégant, souvent ironique avec finesse, et toujours d’une précision redoutable.\\n\\nVotre tâche est de prendre la réponse brute d’un assistant IA principal et de :\\n\\nLa résumer ou reformuler de manière claire et synthétique, la plus brève possible.\\n\\nL’exprimer dans un ton digne d’un majordome anglais, inspiré de Jarvis dans Iron Man.\\n\\nAjouter subtilement des touches d’ironie ou d’humour raffiné quand cela s’y prête.\\n\\nMettre en forme la réponse pour qu’elle soit lisible, agréable à lire, et efficace.\\n\\nVous ne répondez pas directement à la question de l’utilisateur : vous relatez et synthétisez ce que l’autre assistant a répondu, mais vous le faites avec panache. Ne mentionnez pas l'assistant précédent, la réponse doit venir de vous.\\n\\nPar exemple, si l’IA brute répond longuement à une demande technique, vous pouvez conclure votre résumé par :\\n« En somme, Monsieur, si vous vous lancez là-dedans, prévoyez du thé et une chaise confortable. »\\n\\nQuelques règles de style :\\n\\nNe jamais être familier ni grossier.\\n\\nToujours prioriser l’élégance du propos.\\n\\nNe jamais inventer de contenu technique, simplement reformuler ce qui vous a été transmis.\\n\\nSi la réponse brute est trop longue, synthétisez-la en un point ou deux essentiels. Mais n'énoncez pas ces points en commencant par 1. puis 2., enchainez les phrases naturellement comme le ferait un majordome anglais humain.\\n\\nVous êtes, en toutes circonstances, précis, loyal, discret et d’un calme olympien."
                                },
                                {
                                    "role": "user",
                                    "content": "${agent-1}"
                                }
                            ]
                        }
                    },
                    "position": {
                        "x": 2172,
                        "y": 535
                    },
                    "kind": "call",
                    "result": "jarvis",
                    "enabled": true,
                    "description": "Reformulation de la réponse avec la personnalité de J.A.R.V.I.S"
                },
                "memory": {
                    "agent-1": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input_text": null,
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600405,
                "message": "starting '354be6a2-43e2-4322-980d-0498879913de'",
                "node": {
                    "id": "354be6a2-43e2-4322-980d-0498879913de",
                    "values": [
                        {
                            "name": "jarvis",
                            "value": {
                                "$mem_ref": {
                                    "name": "jarvis",
                                    "path": "generations.0.message.content"
                                }
                            }
                        }
                    ],
                    "position": {
                        "x": 2283.6118880121016,
                        "y": 223.1499727230954
                    },
                    "kind": "assign"
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600446,
                "message": "ending '354be6a2-43e2-4322-980d-0498879913de'",
                "node": {
                    "id": "354be6a2-43e2-4322-980d-0498879913de",
                    "values": [
                        {
                            "name": "jarvis",
                            "value": {
                                "$mem_ref": {
                                    "name": "jarvis",
                                    "path": "generations.0.message.content"
                                }
                            }
                        }
                    ],
                    "position": {
                        "x": 2283.6118880121016,
                        "y": 223.1499727230954
                    },
                    "kind": "assign"
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199600446,
                "message": "starting 'a04f55df-85e5-4378-bb26-96527fad92da'",
                "node": {
                    "duration": 2500,
                    "id": "a04f55df-85e5-4378-bb26-96527fad92da",
                    "position": {
                        "x": 2517.0536644679873,
                        "y": 387.563717152789
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199602958,
                "message": "ending 'a04f55df-85e5-4378-bb26-96527fad92da'",
                "node": {
                    "duration": 2500,
                    "id": "a04f55df-85e5-4378-bb26-96527fad92da",
                    "position": {
                        "x": 2517.0536644679873,
                        "y": 387.563717152789
                    },
                    "name": "wait",
                    "description": "This node waits a certain amount of time",
                    "kind": "wait",
                    "enabled": true
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199602959,
                "message": "starting '5c6da866-dab0-47a0-b6a1-2e7b0c3365e7'",
                "node": {
                    "function": "core.log",
                    "args": {
                        "message": "${jarvis}"
                    },
                    "id": "5c6da866-dab0-47a0-b6a1-2e7b0c3365e7",
                    "position": {
                        "x": 2518.644343733468,
                        "y": 228.19079332741848
                    },
                    "kind": "call",
                    "enabled": true,
                    "description": "répond avec le résultat du LLM"
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": null
            },
            {
                "timestamp": 1758199602959,
                "message": "ending with sync exception '5c6da866-dab0-47a0-b6a1-2e7b0c3365e7'",
                "node": {
                    "function": "core.log",
                    "args": {
                        "message": "${jarvis}"
                    },
                    "id": "5c6da866-dab0-47a0-b6a1-2e7b0c3365e7",
                    "position": {
                        "x": 2518.644343733468,
                        "y": 228.19079332741848
                    },
                    "kind": "call",
                    "enabled": true,
                    "description": "répond avec le résultat du LLM"
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": {
                    "message": "caught sync exception on task: '5c6da866-dab0-47a0-b6a1-2e7b0c3365e7'",
                    "details": null,
                    "exception": "JsResultException(errors:List((,List(JsonValidationError(List(error.expected.jsstring),WrappedArray())))))"
                }
            },
            {
                "timestamp": 1758199602960,
                "message": "ending with error 'start'",
                "node": {
                    "kind": "workflow",
                    "steps": [
                        {
                            "node": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "function": "core.log",
                                        "args": {
                                            "message": "Tout de suite monsieur"
                                        },
                                        "id": "4b6f8c41-bbbe-4e95-a83b-98022b2cf02a",
                                        "position": {
                                            "x": 640,
                                            "y": -44
                                        },
                                        "kind": "call",
                                        "enabled": true,
                                        "description": "répond tout de suite que la demande est prise en compte"
                                    },
                                    {
                                        "duration": 2500,
                                        "id": "6ac6618e-8f2e-4f62-82d9-f7e95f0ce804",
                                        "position": {
                                            "x": 903.6764359135203,
                                            "y": -14.542010009111038
                                        },
                                        "name": "wait",
                                        "description": "This node waits a certain amount of time",
                                        "kind": "wait",
                                        "enabled": true
                                    }
                                ]
                            },
                            "id": "e1b209a2-542a-4f4e-8e13-dc69e40f4d15",
                            "position": {
                                "x": 332,
                                "y": 29
                            },
                            "kind": "async"
                        },
                        {
                            "id": "e204cd3d-bb4d-4dc2-9c1c-a76c831cd28a",
                            "predicate": {
                                "$is_truthy": {
                                    "value": "${workflow_input.request.body_json.audio}"
                                }
                            },
                            "then": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "function": "core.log",
                                        "args": {
                                            "message": "audio-model_c5a0f350-1713-4e1a-9ef7-d7b46f7cf395",
                                            "decode_base64": true,
                                            "payload": {
                                                "model": "gpt-4o-mini-transcribe",
                                                "audio": "${workflow_input.request.body_json.audio}"
                                            }
                                        },
                                        "id": "10250e0c-1b4b-4777-b775-5abde0be6fb5",
                                        "position": {
                                            "x": 858.4148664313552,
                                            "y": 179.07544715414593
                                        },
                                        "kind": "call",
                                        "result": "input_text",
                                        "enabled": true,
                                        "description": "transformation du son en texte"
                                    },
                                    {
                                        "duration": 2500,
                                        "id": "cf911e1e-f02a-4f23-a184-619cabe99ae9",
                                        "position": {
                                            "x": 992.6340448355256,
                                            "y": 318.640158830752
                                        },
                                        "name": "wait",
                                        "description": "This node waits a certain amount of time",
                                        "kind": "wait",
                                        "enabled": true
                                    }
                                ],
                                "position": {
                                    "x": 3042.4471121103716,
                                    "y": 236.5142714013013
                                }
                            },
                            "else": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "id": "091dbf95-af19-45a9-be83-beaae69a63e1",
                                        "values": [
                                            {
                                                "name": "input_text",
                                                "value": "${workflow_input.request.body_json.text}"
                                            }
                                        ],
                                        "position": {
                                            "x": 892,
                                            "y": 467
                                        },
                                        "kind": "assign"
                                    }
                                ],
                                "position": {
                                    "x": 3042.4471121103716,
                                    "y": 236.5142714013013
                                }
                            },
                            "position": {
                                "x": 612,
                                "y": 180
                            },
                            "kind": "if",
                            "enabled": true,
                            "description": "Vérification si l'input est du texte ou du son"
                        },
                        {
                            "node": {
                                "kind": "workflow",
                                "steps": [
                                    {
                                        "duration": 10000,
                                        "id": "d373394c-26f4-497d-adaa-0967d0ed8a0b",
                                        "position": {
                                            "x": 1252,
                                            "y": 287
                                        },
                                        "kind": "wait"
                                    },
                                    {
                                        "predicate": {
                                            "$is_falsy": {
                                                "value": "${agent-1}"
                                            }
                                        },
                                        "then": {
                                            "kind": "workflow",
                                            "steps": [
                                                {
                                                    "function": "core.log",
                                                    "args": {
                                                        "message": "Un instant monsieur, je suis en pleine recherche ..."
                                                    },
                                                    "id": "b1848438-d8ea-4840-8dea-834339646ba2",
                                                    "position": {
                                                        "x": 1871.3814958389173,
                                                        "y": 301.5037484251048
                                                    },
                                                    "name": "core.log",
                                                    "description": "This function writes whatever the user want to the otoroshi logs",
                                                    "kind": "core.log",
                                                    "enabled": true
                                                }
                                            ]
                                        },
                                        "id": "fae22a11-58ff-4e1c-bcf0-01762d60d7e3",
                                        "position": {
                                            "x": 1572,
                                            "y": 300
                                        },
                                        "kind": "if",
                                        "enabled": true,
                                        "description": "si pas de réponse au bout de 10 secondes"
                                    }
                                ]
                            },
                            "id": "1d786fc2-1f90-4506-97a9-0a266cfd3299",
                            "position": {
                                "x": 1212,
                                "y": 478
                            },
                            "kind": "async"
                        },
                        {
                            "id": "cd462b14-3179-4654-90b4-65d556454531",
                            "function": "core.log",
                            "args": {
                                "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                                "openai_format": false,
                                "payload": {
                                    "model": "gpt-4o-mini",
                                    "max_tokens": 3000,
                                    "messages": [
                                        {
                                            "role": "system",
                                            "content": "Tu es un assistant personnel intelligent, fiable, organisé, et proactif. Tu aides un utilisateur nommé Mathieu, un ingénieur expérimenté dans l'informatique, à gérer ses tâches quotidiennes, ses projets techniques, ses idées créatives, ainsi que ses besoins personnels et familiaux.\\n\\nTu es capable de :\\n\\nOrganiser des idées, projets, tâches, rappels et priorités.\\n\\nDonner des conseils techniques (backend, frontend, devops, IA, API, cybersécurité…).\\n\\nProposer des solutions ou outils pour gagner du temps.\\n\\nRépondre avec empathie, efficacité et clarté.\\n\\nGénérer du contenu utile (emails, scripts, messages, idées, to-do lists…).\\n\\nGarder un ton professionnel mais humain, capable aussi d’un peu d’humour léger ou de complicité quand c’est approprié.\\n\\nTes réponses doivent être :\\n\\nConcises quand c’est demandé.\\n\\nDétaillées et structurées quand nécessaire.\\n\\nOrientées vers l’action, avec des suggestions concrètes.\\n\\nTu peux poser des questions à Mathieu pour clarifier ses demandes ou anticiper ses besoins. Tu es disponible à tout moment, sans jamais être intrusif. Aujourdhui nous sommes le ${now_str}."
                                        },
                                        {
                                            "role": "user",
                                            "content": "${input_text}"
                                        }
                                    ]
                                }
                            },
                            "position": {
                                "x": 1482.2293857494576,
                                "y": 501.72383040383227
                            },
                            "kind": "call",
                            "result": "agent-1",
                            "enabled": true,
                            "description": "Appel de l'assistant personnel avec les tools de recherche et de visite d'URL"
                        },
                        {
                            "duration": 2500,
                            "id": "5ffecd7d-8207-4fbb-b198-f52db229d26b",
                            "position": {
                                "x": 1689.9362449517616,
                                "y": 658.1116715403531
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "id": "6d71b2ae-733a-4a9c-a498-2623186a284f",
                            "values": [
                                {
                                    "name": "agent-1",
                                    "value": {
                                        "$mem_ref": {
                                            "name": "agent-1"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 1852,
                                "y": 519
                            },
                            "kind": "assign"
                        },
                        {
                            "duration": 2500,
                            "id": "257b64ef-979a-4afa-afb7-9826ead51531",
                            "position": {
                                "x": 2099.586685321611,
                                "y": 742.3388648874248
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "id": "e7354363-fdf3-440a-b76b-04621c827d70",
                            "function": "core.log",
                            "args": {
                                "message": "provider_6d9f408c-baf8-45f4-a7e8-402a349348b8",
                                "openai_format": false,
                                "payload": {
                                    "model": "gpt-4o-mini",
                                    "max_tokens": 500,
                                    "messages": [
                                        {
                                            "role": "system",
                                            "content": "Vous êtes Jarvis, l’assistant IA personnel de Monsieur Mathieu. Vous incarnez un majordome anglais d’exception, au service irréprochable, au flegme inaltérable, et à l’intelligence affûtée. Votre ton est calme, poli, élégant, souvent ironique avec finesse, et toujours d’une précision redoutable.\\n\\nVotre tâche est de prendre la réponse brute d’un assistant IA principal et de :\\n\\nLa résumer ou reformuler de manière claire et synthétique, la plus brève possible.\\n\\nL’exprimer dans un ton digne d’un majordome anglais, inspiré de Jarvis dans Iron Man.\\n\\nAjouter subtilement des touches d’ironie ou d’humour raffiné quand cela s’y prête.\\n\\nMettre en forme la réponse pour qu’elle soit lisible, agréable à lire, et efficace.\\n\\nVous ne répondez pas directement à la question de l’utilisateur : vous relatez et synthétisez ce que l’autre assistant a répondu, mais vous le faites avec panache. Ne mentionnez pas l'assistant précédent, la réponse doit venir de vous.\\n\\nPar exemple, si l’IA brute répond longuement à une demande technique, vous pouvez conclure votre résumé par :\\n« En somme, Monsieur, si vous vous lancez là-dedans, prévoyez du thé et une chaise confortable. »\\n\\nQuelques règles de style :\\n\\nNe jamais être familier ni grossier.\\n\\nToujours prioriser l’élégance du propos.\\n\\nNe jamais inventer de contenu technique, simplement reformuler ce qui vous a été transmis.\\n\\nSi la réponse brute est trop longue, synthétisez-la en un point ou deux essentiels. Mais n'énoncez pas ces points en commencant par 1. puis 2., enchainez les phrases naturellement comme le ferait un majordome anglais humain.\\n\\nVous êtes, en toutes circonstances, précis, loyal, discret et d’un calme olympien."
                                        },
                                        {
                                            "role": "user",
                                            "content": "${agent-1}"
                                        }
                                    ]
                                }
                            },
                            "position": {
                                "x": 2172,
                                "y": 535
                            },
                            "kind": "call",
                            "result": "jarvis",
                            "enabled": true,
                            "description": "Reformulation de la réponse avec la personnalité de J.A.R.V.I.S"
                        },
                        {
                            "id": "354be6a2-43e2-4322-980d-0498879913de",
                            "values": [
                                {
                                    "name": "jarvis",
                                    "value": {
                                        "$mem_ref": {
                                            "name": "jarvis",
                                            "path": "generations.0.message.content"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 2283.6118880121016,
                                "y": 223.1499727230954
                            },
                            "kind": "assign"
                        },
                        {
                            "duration": 2500,
                            "id": "a04f55df-85e5-4378-bb26-96527fad92da",
                            "position": {
                                "x": 2517.0536644679873,
                                "y": 387.563717152789
                            },
                            "name": "wait",
                            "description": "This node waits a certain amount of time",
                            "kind": "wait",
                            "enabled": true
                        },
                        {
                            "function": "core.log",
                            "args": {
                                "message": "${jarvis}"
                            },
                            "id": "5c6da866-dab0-47a0-b6a1-2e7b0c3365e7",
                            "position": {
                                "x": 2518.644343733468,
                                "y": 228.19079332741848
                            },
                            "kind": "call",
                            "enabled": true,
                            "description": "répond avec le résultat du LLM"
                        },
                        {
                            "id": "463fa062-9ab1-446e-91e3-58cda02bd531",
                            "values": [
                                {
                                    "name": "call_res",
                                    "value": {
                                        "status": 200,
                                        "headers": {
                                            "Content-Type": "application/json"
                                        },
                                        "body_json": {
                                            "text": "${jarvis}",
                                            "audio_base64": "${audio_base64}"
                                        }
                                    }
                                }
                            ],
                            "position": {
                                "x": 2746.5907086618276,
                                "y": 236.28794311722692
                            },
                            "kind": "assign",
                            "enabled": true
                        }
                    ],
                    "returned": {
                        "$mem_ref": {
                            "name": "call_res"
                        }
                    },
                    "id": "start",
                    "position": {
                        "x": 12,
                        "y": 12
                    },
                    "returnedInformation": {
                        "position": {
                            "x": 3042.4471121103716,
                            "y": 236.5142714013013
                        }
                    }
                },
                "memory": {
                    "jarvis": null,
                    "agent-1": null,
                    "input_text": null,
                    "workflow_input": {
                        "name": "foo"
                    },
                    "input": null
                },
                "error": {
                    "message": "caught sync exception on task: '5c6da866-dab0-47a0-b6a1-2e7b0c3365e7'",
                    "details": null,
                    "exception": "JsResultException(errors:List((,List(JsonValidationError(List(error.expected.jsstring),WrappedArray())))))"
                }
            }
        ]
    }
}

function Terminal({
    terminalSize,
    toggleResizingTerminal,
    changeTerminalSize,
    flowOperators,
    getTestPayload,
    saveTerminalTab,
    initialTerminalTab
}) {
    const [tab, _setTab] = useState(initialTerminalTab || 'input')

    const [action, setAction] = useState()
    const [running, setRunning] = useState(false)

    const [input, setInput] = useState(nodesCatalogSignal.value.rawWorkflow.test_payload)
    const [log, setLog] = useState([])
    const [memory, setMemory] = useState()
    const [report, setReport] = useState(REPORT_DEMO)

    const wsRef = useRef()

    const setTab = newTab => {
        _setTab(newTab)
        saveTerminalTab(newTab)
    }

    const handleTabChange = newTab => {
        if (terminalSize === 0)
            changeTerminalSize(0.3)
        setTab(newTab)
    }

    function eventCallback(event, resolve) {
        if (event.kind === 'progress') {
            setLog(log => [event, ...log])

            const event_id = event?.data?.node?.id;
            console.log(`[${event.data.node.kind}]`, event_id);

            if ((event?.data?.message || '').toLowerCase().startsWith("starting")) {
                if (event_id) {
                    flowOperators.highlightNode(event_id)
                    flowOperators.highlightEdge(event_id)
                }
            } else if ((event?.data?.message || '').toLowerCase().startsWith("ending")) {
                if (event_id) {
                    flowOperators.unhighlighNode(event_id)
                }
            }
        } else if (event.kind === 'result') {
            flowOperators.highlightEdge("returned-node")
            flowOperators.unhighlighNode("returned-node")
            if (resolve) {
                resolve(event.data);
            }
            handleReportChange(event.data);
        } else if (event.kind === 'debugger-state') {
            // TODO - manage debugger state
        } else {
            console.warn('Unknown kind:', event.kind);
        }
    }

    const handleReportChange = newReport => {
        console.log(newReport)
    }

    const runWsAction = (action, data = {}) => {
        if (action === "start" || action === "step_by_step") {
            wsRef.current.send(JSON.stringify({
                kind: 'start',
                data: { ...getTestPayload(input), ...data }
            }));
            setRunning(true)
        } else if (action === "stop") {
            wsRef.current.send(JSON.stringify({ kind: action, data: {} }));
            setRunning(false)
            wsRef.current = null;
        } else {
            wsRef.current.send(JSON.stringify({ kind: action, data: {} }));
        }
    }

    const runWs = (action, data = {}) => {
        if (action === 'start') {
            flowOperators.resetFlow()
        }

        if (!wsRef.current) {
            const location = window.location;
            const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
            wsRef.current = new WebSocket(`${scheme}://${location.host}/extensions/workflows/_debugger`);
            wsRef.current.onmessage = (message) => {
                const json = JSON.parse(message.data);
                console.log('received message 1', json);

                eventCallback(json);
                if (json.kind === 'result') {
                    setRunning(false);
                    setAction();
                    handleReportChange(json.data);
                    wsRef.current = null;
                }
            }
            setTimeout(() => {
                // minimize()
                runWsAction(action, data);
            }, 1000)
        } else {
            runWsAction(action, data);
        }
    }

    const run = () => {
        setAction('play')
        runWs('start')
    }

    const debug = () => {
        setAction('debug')
        runWs('start', { step_by_step: true })
    }

    const minimize = () => changeTerminalSize(0)

    const clearLog = () => {
        setLog([])
    }

    const tabContentVisible = terminalSize !== 0

    return <div className='terminal' style={{
        flex: terminalSize,
        maxHeight: `${100 * terminalSize}%`
    }}>
        <DesignerActions
            run={run}
            debug={debug}
            next={() => runWs('next')}
            resume={() => runWs('resume')}
            handleFlowStop={() => {
                runWs('stop')
                setRunning(false)
                setAction()
                flowOperators.resetFlow()
            }}
            action={action}
            running={running} />
        <div style={{
            minHeight: 12,
            maxHeight: 12,
            background: 'var(--bg-color_level3)'
        }}
            className="d-flex align-items-center justify-content-center"
            onMouseDown={e => {
                e.stopPropagation();
                toggleResizingTerminal(true)
            }}
            onMouseMove={e => e.stopPropagation()}
            onMouseUp={e => {
                e.stopPropagation();
                toggleResizingTerminal(false)
            }}>
            <div style={{
                cursor: 'pointer',
                background: '#fff',
                width: 36,
                height: 6,
                borderRadius: 12
            }}></div>
        </div>

        <div className='d-flex justify-content-between align-items-center mx-2 me-3'>
            <div className='d-flex'>
                <Tab
                    name="Input"
                    onClick={() => handleTabChange('input')}
                    selected={tab === 'input'} />
                <Tab
                    name="Log"
                    onClick={() => handleTabChange('log')}
                    selected={tab === 'log'} />
                <Tab
                    name="Memory"
                    onClick={() => handleTabChange('memory')}
                    selected={tab === 'memory'} />
                <Tab
                    name="Report"
                    onClick={() => handleTabChange('report')}
                    selected={tab === 'report'} />
            </div>

            <div className='d-flex align-items-center'>
                {tab === 'log' && <span className='me-3' onClick={clearLog} >
                    <i className='fas fa-ban fa-sm' style={{ cursor: 'pointer' }} />
                </span>}
                <span onClick={() => changeTerminalSize(0)} >
                    <i className='fas fa-times fa-sm' style={{ cursor: 'pointer' }} />
                </span>
            </div>
        </div>

        {tabContentVisible && <>
            {tab === 'input' && <InputTab
                input={input}
                setInput={setInput}
                terminalSize={terminalSize} />}

            {tab === 'report' && <ReportTab report={report} />}

            {tab === 'log' && <LogTab log={log} />}

            {tab === 'memory' && <MemoryTab memory={memory} />}
        </>}
    </div>
}

const LogTab = ({ log }) => {
    return <div className='d-flex flex-column' style={{
        overflowY: 'auto',
    }}>
        <div className='terminal-log-header'>
            <div>Time</div>
            <div>Action</div>
            <div>ID</div>
            <div>Message</div>
        </div>
        {log.map((item, i) => {
            return <div key={`debug${i}`} className='terminal-log-header terminal-log-item'>
                <div>{moment(item.data.timestamp).format('hh:mm:ss')}</div>
                <div>{item.data.message.split(' ')[0]}</div>
                <div>{item.data.node.kind}</div>
                <div className='terminal-log-item-data'>{JSON.stringify(item.data, null, 4)}</div>
            </div>
            // return <CodeInput
            //     ace_config={{
            //         readOnly: true,
            //         fontSize: 14,
            //         // maxLines: Infinity,
            //         minLines: 1
            //     }}
            //     key={`debug${i}`}
            //     value={item}
            //     editorOnly={true}
            //     label={null}
            // />
        })}
    </div >
}

const ReportTab = ({ report }) => {
    return <ReportInformation report={report} />
}

const MemoryTab = ({ memory }) => {
    if (!memory)
        return <div className='terminal-memory-tab'>Click Play or Debug to view workflow memory.</div>
}

const InputTab = ({ input, setInput, terminalSize }) => {
    return <CodeInput
        height={`calc(${(terminalSize) * 100}vh)`}
        value={input}
        onChange={setInput}
        editorOnly={true}
        label={null}
    />
}

export default Terminal;