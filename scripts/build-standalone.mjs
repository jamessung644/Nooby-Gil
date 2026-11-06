// A portable offline deliverable built from exactly the same first-party modules.
// This is not a hosting or live-provider deployment.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import path from 'node:path';
const web=path.resolve('web');
const assets=new Map();
for(const name of await readdir(path.join(web,'assets'))){
  if(!/\.svg$/.test(name))continue;
  const b=await readFile(path.join(web,'assets',name));
  assets.set(`./assets/${name}`,`data:image/svg+xml;base64,${b.toString('base64')}`);
  assets.set(`assets/${name}`,`data:image/svg+xml;base64,${b.toString('base64')}`);
}
function inlineAssets(s){for(const [from,to] of assets)s=s.split(from).join(to);return s;}
const font=await readFile(path.join(web,'assets/fonts/AstaSans.ttf'));
assets.set('./assets/fonts/AstaSans.ttf',`data:font/ttf;base64,${font.toString('base64')}`);
const modules=new Map();
async function bundle(name){
  if(modules.has(name))return;
  let code=await readFile(path.join(web,name),'utf8');
  const imports=[...code.matchAll(/import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"];?/g)];
  for(const [, ,relative] of imports)await bundle(path.posix.normalize(path.posix.join(path.posix.dirname(name),relative)));
  code=code.replace(/import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"];?/g,(_all,names,relative)=>`const {${names.replace(/\bas\b/g,':')}} = __modules[${JSON.stringify(path.posix.normalize(path.posix.join(path.posix.dirname(name),relative)))}];`);
  const names=[...code.matchAll(/export\s+(?:async\s+)?(?:function|const|let|class)\s+(\w+)/g)].map(m=>m[1]);
  code=code.replace(/\bexport\s+(?=(?:async\s+)?(?:function|const|let|class)\b)/g,'');
  if(/^\s*(?:import|export)\b/m.test(code))throw new Error(`Unsupported module syntax in ${name}`);
  if(name==='app.js')code=code.replace(/^const art =[^\n]+/m,"const art = (scene) => __assetMap['./assets/'+(['lake','coast','forest','city'].includes(scene)?scene:'lake')+'.svg'];");
  code=inlineAssets(code);
  modules.set(name,`__modules[${JSON.stringify(name)}]=(()=>{\n${code}\nreturn {${names.join(',')}};\n})();`);
}
await bundle('app.js');
const css=inlineAssets(await readFile(path.join(web,'styles.css'),'utf8'));
let html=await readFile(path.join(web,'index.html'),'utf8');
html=html.replace(/<link[^>]+(?:rel="manifest"|unpkg\.com)[^>]*>\s*/g,'').replace(/<script[^>]+unpkg\.com[^>]*><\/script>\s*/g,'');
html=html.replace('<link rel="stylesheet" href="./styles.css">',`<style>${css}</style>`);
html=inlineAssets(html);
const fontLicense=await readFile(path.join(web,'assets/fonts/OFL.txt'),'utf8');
html=html.replace('</body>',`<script type="text/plain" id="asta-sans-license">${fontLicense}</script></body>`);
const script=`const __assetMap=${JSON.stringify(Object.fromEntries([...assets].filter(([name])=>!name.endsWith(".ttf"))))};\nconst __modules=Object.create(null);\n${[...modules.values()].join('\n')}`.replace(/<\/script/gi,'<\\/script');
html=html.replace('<script type="module" src="./app.js"></script>',`<script type="module">\n${script}\n</script>`);
await mkdir('dist-offline',{recursive:true});
await writeFile('dist-offline/SCENIC_Demo.html',html);
console.log(`Standalone HTML ready: ${Buffer.byteLength(html)} bytes, ${modules.size} first-party modules`);
