import React from 'react'

import { NgCodeRenderer, NgJsonRenderer, NgObjectRenderer, NgSelectRenderer } from "../../components/nginputs";
import { Row } from '../../components/Row';

export function ArgsInput(props) {
    return <>
        {/* <NgSelectRenderer
            value={props.value?.kind}
            label="Argument kind"
            onChange={kind => props.onChange({
                ...props.value,
                kind
            })}
            options={['Object', 'Array', 'String', 'Raw']}
        /> */}

        <Row title="Arguments">
            {/* {props.value?.kind === 'Object' &&  */}
            <NgCodeRenderer
                ngOptions={{ spread: true }}
                rawSchema={{
                    props: {
                        ace_config: {
                            maxLines: Infinity,
                            fontSize: 14,
                        },
                        editorOnly: true,
                        height: '100%',
                        mode: 'json',
                    },
                }}
                onChange={argument => props.onChange({
                    ...props.value,
                    argument
                })}
                value={props.value?.argument} />
            {/* } */}
        </Row>
    </>
}

// case JsObject(map) if map.size == 1 && map.head._1.startsWith("$")                    => {
//       operators.get(map.head._1) match {
//         case None           => value
//         case Some(operator) => {
//           val opts = WorkflowOperator.processOperators(map.head._2.asObject, wfr, env)
//           operator.process(opts, wfr, env)
//         }
//       }
//     }
//     case JsArray(arr)                                                                     => JsArray(arr.map(v => processOperators(v, wfr, env)))
//     case JsObject(map)                                                                    => JsObject(map.mapValues(v => processOperators(v, wfr, env)))
//     case JsString("${now}")                                                               => System.currentTimeMillis().json
//     case JsString(str) if str.startsWith("${") && str.endsWith("}") && !str.contains(".") => {
//       val name = str.substring(2).init
//       wfr.memory.get(name) match {
//         case None        => JsNull
//         case Some(value) => value
//       }
//     }
//     case JsString(str) if str.startsWith("${") && str.endsWith("}") && str.contains(".")  => {
//       val parts = str.substring(2).init.split("\\.")
//       val name  = parts.head
//       val path  = parts.tail.mkString(".")
//       wfr.memory.get(name) match {
//         case None        => JsNull
//         case Some(value) => value.at(path).asOpt[JsValue].getOrElse(JsNull)
//       }
//     }
//     case JsString(str) if str.contains("${now_str}")                                      => JsString(str.replace("${now_str}", DateTime.now().toString))
//     case JsString(str) if str.contains("${now}")                                          => JsString(str.replace("${now}", System.currentTimeMillis().toString))
//     case _                                                                                => value
//   }