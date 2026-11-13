const mineflayer=require('mineflayer'),fs=require('fs'),assert=require('node:assert/strict');
const bot=mineflayer.createBot({host:'127.0.0.1',port:25576,username:'NetherProbe',version:'1.21.11',auth:'offline'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
bot.on('messagestr',m=>console.log('CHAT',m));bot.on('error',e=>{console.error(e);process.exit(1)});
function msg(part,action,timeout=30000){return new Promise((resolve,reject)=>{const listen=m=>{if(m.includes(part)){clearTimeout(timer);bot.off('messagestr',listen);resolve(m)}};const timer=setTimeout(()=>{bot.off('messagestr',listen);reject(new Error('Missing '+part))},timeout);bot.on('messagestr',listen);action()})}
bot.once('spawn',async()=>{try{
console.log('NETHER CLIENT SPAWN');if(process.env.POLDER_NETHER_START_FILE)while(!fs.existsSync(process.env.POLDER_NETHER_START_FILE))await sleep(250);
const mode=process.env.POLDER_NETHER_MODE||'verify';
if(mode==='automatic'){await msg('NETHER PHYSICAL PORTAL READY',()=>bot.chat('/netherprobe physical'));
let arrived=false;for(let i=0;i<30;i++){const place=await msg('NETHER LOCATION',()=>bot.chat('/netherprobe where'));if(place.includes('polder_nether')){arrived=true;break;}await sleep(1000)}assert(arrived,'Vanilla portal did not reach managed Nether');console.log('NETHER PHYSICAL PORTAL PASS');
await msg('NETHER ENTER PASS',()=>bot.chat('/netherprobe enter'));await msg('Nether reset scheduled now',()=>bot.chat('/netheradmin reset'));
await msg('controlled SMP shutdown',()=>{},30000);console.log('NETHER CONTROLLED AUTOMATIC SHUTDOWN PASS');bot.quit();setTimeout(()=>process.exit(0),300);
}else if(mode==='evacuate'){await msg('NETHER ENTER PASS',()=>bot.chat('/netherprobe enter'));await msg('NETHER PORTAL ROUTING PASS',()=>bot.chat('/netherprobe portal'));
await msg('Nether reset scheduled now',()=>bot.chat('/netheradmin resume'),30000);await sleep(2000);
await msg('NETHER EVACUATION PASS',()=>bot.chat('/netherprobe evacuated'));console.log('NETHER CLIENT EVACUATION PASS');bot.quit();setTimeout(()=>process.exit(0),300);
}else{await msg('NETHER REGENERATION PASS',()=>bot.chat('/netherprobe fresh'));await msg('NETHER PORTAL ROUTING PASS',()=>bot.chat('/netherprobe portal'));console.log('NETHER NEW SEED AND PORTAL PASS');bot.quit();setTimeout(()=>process.exit(0),300);
}}catch(e){console.error(e);bot.quit();setTimeout(()=>process.exit(1),300)}});
setTimeout(()=>process.exit(1),180000).unref();
