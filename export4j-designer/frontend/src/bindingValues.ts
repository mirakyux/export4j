// SPDX-License-Identifier: Apache-2.0
import {bindingContext} from './bindings';
import type {Snapshot} from './types';

export interface BindingValue {
  status:'value'|'repeated'|'missing'|'invalid';
  type?:string;
  raw?:string;
  /** Numeric values remain their original JSON token, avoiding Number precision loss. */
  value?:unknown;
}

type JsonKind='object'|'array'|'string'|'number'|'boolean'|'null';
interface Token {
  kind:JsonKind;
  start:number;
  end:number;
  value?:string|boolean;
  members?:Map<string,Token>;
}
type DataSnapshot=Snapshot & {data?:string};
type Lookup={status:'value';token:Token;data:string}|{status:'missing'}|{status:'repeated'};
const MAX_TEXT=1_000_000,MAX_WORK=20_000,MAX_DEPTH=48;
const PATH=/^[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*$/;

/** Validates all input while retaining token spans, never round-tripping numeric values. */
function parseToken(source:string):Token {
  if(source.length>MAX_TEXT)throw new Error('测试数据超出编辑预算。');
  let position=0,work=0;
  const number=/-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;
  const invalid=():never=>{throw new Error('测试数据不是有效的 JSON。');};
  const consume=()=>{if(++work>MAX_WORK)throw new Error('测试数据超出编辑预算。');};
  const whitespace=()=>{while(position<source.length&&/[ \t\r\n]/.test(source[position]))position++;};
  const string=():Token=>{
    const start=position++;
    while(position<source.length){
      const code=source.charCodeAt(position++);
      if(code===34){
        return {kind:'string',start,end:position,value:JSON.parse(source.slice(start,position)) as string};
      }
      if(code<32)invalid();
      if(code!==92)continue;
      if(position===source.length)invalid();
      const escape=source[position++];
      if(escape==='u'){
        if(!/^[0-9a-fA-F]{4}$/.test(source.slice(position,position+4)))invalid();
        position+=4;
      }else if(!'"\\/bfnrt'.includes(escape))invalid();
    }
    return invalid();
  };
  const value=(depth:number):Token=>{
    consume();
    if(depth>MAX_DEPTH)throw new Error('测试数据嵌套超出编辑预算。');
    whitespace();
    const start=position,char=source[position];
    if(char==='"')return string();
    if(char==='{'||char==='['){
      const object=char==='{',end=object?'}':']',members=object?new Map<string,Token>():undefined;
      position++;whitespace();
      if(source[position]!==end){
        while(true){
          let key:string|undefined;
          if(object){
            consume();
            if(source[position]!=='"')invalid();
            key=string().value as string;
            if(members!.has(key))throw new Error('测试数据包含重复字段，请先修正数据。');
            whitespace();if(source[position++]!==':')invalid();
          }
          const child=value(depth+1);
          if(object)members!.set(key!,child);
          whitespace();
          if(source[position]===end)break;
          if(source[position++]!==',')invalid();
          whitespace();
        }
      }
      position++;
      return {kind:object?'object':'array',start,end:position,members};
    }
    for(const [literal,kind,decoded] of [['true','boolean',true],['false','boolean',false],['null','null',undefined]] as const){
      if(!source.startsWith(literal,position))continue;
      position+=literal.length;
      return {kind,start,end:position,value:decoded};
    }
    number.lastIndex=position;
    const match=number.exec(source);
    if(!match)return invalid();
    position=number.lastIndex;
    return {kind:'number',start,end:position};
  };
  const result=value(0);
  whitespace();if(position!==source.length)invalid();
  return result;
}

function lookup(snapshot:DataSnapshot,selectedPath:string,fieldPath:string):Lookup {
  if(typeof snapshot.source!=='string'||snapshot.source.length>MAX_TEXT||typeof snapshot.data!=='string'||snapshot.data.length>MAX_TEXT){
    throw new Error('源码或测试数据超出编辑预算，或测试数据不可用。');
  }
  if(fieldPath.length>256||!PATH.test(fieldPath))throw new Error('变量路径无效，请使用点分隔的字段名。');
  const parts=fieldPath.split('.'),context=bindingContext(snapshot,selectedPath);
  if(context.scopes.some(scope=>scope.kind!=='root'&&scope.alias===parts[0]))return {status:'repeated'};
  let token=parseToken(snapshot.data);
  if(token.kind!=='object')throw new Error('测试数据必须是 JSON 对象。');
  for(const part of parts){
    const child=token.members?.get(part);
    if(!child)return {status:'missing'};
    token=child;
  }
  return {status:'value',token,data:snapshot.data};
}

/** Reads an existing root scalar; loop aliases never fall through to same-named root data. */
export function bindingValue(snapshot:DataSnapshot,selectedPath:string,fieldPath:string):BindingValue {
  try{
    const result=lookup(snapshot,selectedPath,fieldPath);
    if(result.status!=='value')return result;
    const {token,data}=result;
    if(!['string','number','boolean'].includes(token.kind))return {status:'invalid',type:token.kind};
    const raw=data.slice(token.start,token.end);
    return {status:'value',type:token.kind,raw,value:token.kind==='number'?raw:token.value};
  }catch{return {status:'invalid'};}
}

/** Replaces one existing scalar token, preserving all other JSON characters and number tokens. */
export function editBindingValue(snapshot:DataSnapshot,selectedPath:string,fieldPath:string,newRaw:string):string {
  const result=lookup(snapshot,selectedPath,fieldPath);
  if(result.status==='repeated')throw new Error('循环变量对应多条数据，请在测试数据中编辑具体项。');
  if(result.status==='missing')throw new Error('测试数据中没有该字段，请先在测试数据中添加。');
  const {token,data}=result;
  if(!['string','number','boolean'].includes(token.kind))throw new Error('这里只能编辑已有的字符串、布尔值或数值。');
  const candidate=parseToken(newRaw);
  if(candidate.kind!==token.kind)throw new Error('新值必须保持原有数据类型；字符串使用 JSON 引号，布尔值使用 true 或 false。');
  const replacement=newRaw.slice(candidate.start,candidate.end);
  if(data.length-(token.end-token.start)+replacement.length>MAX_TEXT)throw new Error('修改后的测试数据超出编辑预算。');
  return data.slice(0,token.start)+replacement+data.slice(token.end);
}
