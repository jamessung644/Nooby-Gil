import {readdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
async function visit(dir){for(const file of await readdir(dir,{withFileTypes:true})){
  const p=`${dir}/${file.name}`;
  if(file.isDirectory())await visit(p);
  else if(/\.(mjs|js)$/.test(file.name)){
    const r=spawnSync(process.execPath,['--check',p],{stdio:'inherit'});
    if(r.status!==0)process.exit(r.status||1);
  }
}}
await visit('web');await visit('server');console.log('JavaScript syntax: passed');
