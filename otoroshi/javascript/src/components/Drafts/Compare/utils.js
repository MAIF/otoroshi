const isArray = (item) => {
    if (item === 'array') {
        return true;
    }
    return Object.prototype.toString.call(item) === '[object Array]';
};

const isObject = (item) => {
    return Object.prototype.toString.call(item) === '[object Object]';
};

const needFormat = (type) => {
    return type === 'array' || type === 'object';
};

const getIndent = (level) => {
    if (level === 1) {
        return { textIndent: '20px' };
    }
    return { textIndent: `${level * 20}px` };
};

const getType = (item) => {
    let t = Object.prototype.toString.call(item);
    let match = /(?!\[).+(?=\])/g;
    t = t.match(match)[0].split(' ')[1];
    return t.toLowerCase();
};

const isComplexType = (param) => {
    return isObject(param) || isArray(param);
};

const isTheSametype = (a, b) => {
    return (
        Object.prototype.toString.call(a) === Object.prototype.toString.call(b)
    );
};

const mergeData = (_old, _new) => {
    let result = [];
    let start = 1;

    let changed = false

    // convert array or object to Array<object> [{}]
    const convertObject = (param, lineType) => {
        let list = [];
        if (isComplexType(param)) {
            let showIndex = getType(param) === 'object';
            let keys = Object.keys(param);
            let length = keys.length;
            keys.forEach((key, index) => {
                let type = getType(param[key]);
                list.push({
                    name: key,
                    line: start++,
                    value: convertObject(param[key], lineType),
                    type: type,
                    showIndex: showIndex,
                    needComma: length !== index + 1,
                    lineType: lineType,
                    lastLineType: lineType,
                    lastLine: isComplexType(param[key]) ? start++ : null,
                });
            });
            return list;
        } else {
            switch (getType(param)) {
                case 'number':
                case 'boolean':
                case 'regexp':
                    return param.toString();
                case 'null':
                    return 'null';
                case 'undefined':
                    return 'undefined';
                case 'function':
                    return ' ƒ() {...}';
                default:
                    return `"${param.toString()}"`;
            }
        }
    };

    // return parsed data
    const parseValue = (key, value, showIndex, needComma, lineType) => {
        return {
            name: key,
            line: start++,
            value: convertObject(value, lineType),
            type: getType(value),
            showIndex: showIndex,
            needComma: needComma,
            lineType: lineType,
            lastLineType: lineType,
            lastLine: isComplexType(value) ? start++ : null,
        };
    };

    // merge two vars to target,target type Array<object>[{}]
    const parseData = (a, b, target) => {
        let _ar = Object.keys(a);
        let _br = Object.keys(b);
        let showIndex = isObject(b);
        // deleted keys
        let _del = _ar.filter((ak) => !_br.some((bk) => bk === ak));
        // not removed keys
        let _stl = _ar.filter((ak) => _br.some((bk) => bk === ak));
        // new added keys
        let _add = _br.filter((bk) => !_ar.some((ak) => ak === bk));
        // push deleted keys
        _del.forEach((key, index) => {
            let needComma = true;
            if (_stl.length === 0 && _add.length === 0 && index === _del.length - 1) {
                needComma = false;
            }
            changed = true
            target.push(parseValue(key, a[key], showIndex, needComma, 'del'));
        });
        // The core function: compare
        _stl.forEach((key, index) => {
            let needComma = true;
            if (_add.length === 0 && index === _stl.length - 1) {
                needComma = false;
            }
            if (a[key] === b[key]) {
                target.push(parseValue(key, b[key], showIndex, needComma, 'none'));
            } else if (isTheSametype(a[key], b[key])) {
                if (isComplexType(b[key])) {
                    let _target = parseValue(
                        key,
                        isArray(a[key]) ? [] : {},
                        showIndex,
                        needComma,
                        'none'
                    );
                    target.push(_target);
                    // back one step
                    start -= 1;
                    // go inside
                    parseData(a[key], b[key], _target.value);
                    // rewrite lastline
                    _target.lastLine = start++;
                } else {
                    changed = true
                    target.push(parseValue(key, a[key], showIndex, true, 'del'));
                    target.push(parseValue(key, b[key], showIndex, needComma, 'add'));
                }
            } else {
                changed = true
                target.push(parseValue(key, a[key], showIndex, true, 'del'));
                target.push(parseValue(key, b[key], showIndex, needComma, 'add'));
            }
        });
        // push new keys
        _add.forEach((key, index) => {
            changed = true
            target.push(
                parseValue(key, b[key], showIndex, _add.length !== index + 1, 'add')
            );
        });
    };

    if (isTheSametype(_old, _new) && isComplexType(_new)) {
        parseData(_old, _new, result);
    } else {
        if (_old === _new) {
            result.push(parseValue(0, _new, false, false, 'none'));
        } else {
            changed = true
            result.push(parseValue(0, _old, false, true, 'del'));
            result.push(parseValue(1, _new, false, false, 'add'));
        }
    }
    return { result, changed };
};

export {
    isArray,
    isObject,
    needFormat,
    getIndent,
    getType,
    isComplexType,
    isTheSametype,
    mergeData,
};