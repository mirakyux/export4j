// SPDX-License-Identifier: Apache-2.0
const roles=[['Regular.ttf','Export4j body','400'],['Bold.ttf','Export4j body','700'],['Chinese.ttf','Export4j Chinese','400']] as const;

/** Replaces only the canvas fonts, and ignores loads from an older workspace snapshot. */
export function createFontLoader(load:(name:string)=>Promise<ArrayBuffer>,report:(error:unknown)=>void){
  let identity:string|undefined,session:number|undefined,version=0,pending=Promise.resolve();
  let installed:FontFace[]=[];
  return (nextIdentity:string|undefined,nextSession:number):Promise<void>=>{
    if(identity===nextIdentity&&session===nextSession)return pending;
    identity=nextIdentity;session=nextSession;
    const generation=++version;
    pending=Promise.allSettled(roles.map(async([name,family,weight])=>{
      const bytes=await load(name);
      return new FontFace(family,bytes,{weight}).load();
    })).then(results=>{
      if(generation!==version)return;
      for(const font of installed)document.fonts.delete(font);
      installed=[];
      for(const result of results)if(result.status==='fulfilled'){
        document.fonts.add(result.value);installed.push(result.value);
      }
      if(results[0].status==='rejected')report(results[0].reason);
    });
    return pending;
  };
}
