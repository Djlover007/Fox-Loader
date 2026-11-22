const path=require('path');
require('esbuild').build({entryPoints:['entry.js'],bundle:true,minify:true,platform:'browser',format:'iife',target:'es2019',outfile:'../gram.js',
 inject:['shims/buffer-global.js'],define:{'process.env.NODE_ENV':'"production"',global:'globalThis'},
 alias:{fs:'./shims/empty.js',net:'./shims/empty.js',socks:'./shims/empty.js','node-localstorage':'./shims/empty.js',os:'./shims/os.js',path:'./shims/path.js',util:'./shims/util.js',crypto:'./shims/empty.js',constants:'./shims/empty.js',stream:'./shims/empty.js'},
 plugins:[{name:'cryptofile',setup(b){b.onResolve({filter:/^\.\/CryptoFile$/},a=>({path:path.resolve('shims/cryptofile.js')}));}}],
 logLevel:'warning'}).then(()=>console.log('built',require('fs').statSync('../gram.js').size)).catch(e=>{console.error(e.message.slice(0,3000));process.exit(1)});
