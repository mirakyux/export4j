// SPDX-License-Identifier: Apache-2.0
import {build} from 'esbuild';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
await mkdir('../build/frontend',{recursive:true});
await build({entryPoints:['src/studio.tsx'],bundle:true,format:'iife',globalName:'DesignerStudio',
  target:'es2022',minify:true,legalComments:'eof',outfile:'../build/frontend/studio.js'});
const packages=['react','react-dom','scheduler','prosemirror-model','prosemirror-state','prosemirror-view',
  'prosemirror-history','prosemirror-keymap','prosemirror-commands','prosemirror-transform','orderedmap','rope-sequence','w3c-keyname'];
let notices='Export4j Designer browser dependencies\n\n';
for(const name of packages){
  const base='node_modules/'+name+'/';const meta=JSON.parse(await readFile(base+'package.json','utf8'));
  let license;for(const file of ['LICENSE','LICENSE.txt']){try{license=await readFile(base+file,'utf8');break;}catch(error){if(error.code!=='ENOENT')throw error;}}
  if(!license)throw new Error('Missing license for '+name);
  notices+=name+' '+meta.version+' ('+meta.license+')\n'+license+'\n\n';
}
await writeFile('../build/frontend/THIRD-PARTY-NOTICES.txt',notices);
