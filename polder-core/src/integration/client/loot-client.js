const mineflayer=require('mineflayer');
const {Vec3}=require('vec3');
const fs=require('fs');
const assert=require('node:assert/strict');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const bot=mineflayer.createBot({host:'127.0.0.1',port:25575,username:'LootProbe',auth:'offline',version:'1.21.11'});
bot.on('messagestr',m=>console.log('CHAT',m));
bot.on('error',e=>{console.error('CLIENT ERROR',e);process.exit(1)});
bot.on('kicked',e=>{console.error('KICKED',JSON.stringify(e));process.exit(1)});
function message(fragment,action,timeout=30000){return new Promise((resolve,reject)=>{let timer;const listener=m=>{if(m.includes(fragment)){clearTimeout(timer);bot.removeListener('messagestr',listener);resolve(m)}};bot.on('messagestr',listener);timer=setTimeout(()=>{bot.removeListener('messagestr',listener);reject(new Error('Missing message '+fragment))},timeout);if(action)Promise.resolve().then(action).catch(reject)})}
function window(action){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Window did not open')),20000);bot.once('windowOpen',w=>{clearTimeout(timer);resolve(w)});Promise.resolve().then(action).catch(reject)})}
async function until(fn,timeout=20000){const end=Date.now()+timeout;while(Date.now()<end){const value=fn();if(value)return value;await sleep(200)}throw new Error('Condition timeout')}
const inventory=()=>JSON.stringify(bot.inventory.items().map(i=>[i.name,i.count,i.slot]));
async function close(){if(bot.currentWindow)bot.closeWindow(bot.currentWindow);await sleep(300)}
async function activateAt(x,y,z){const block=await until(()=>bot.blockAt(new Vec3(x,y,z)));await bot.lookAt(block.position.offset(.5,.5,.5),true);await bot.activateBlock(block)}
async function run(){if(process.env.POLDER_LOOT_START_FILE)await until(()=>fs.existsSync(process.env.POLDER_LOOT_START_FILE),120000);await message('LIVE SETUP PASS',()=>bot.chat('/loottest setup'),60000);await sleep(2000);
 let w=await window(()=>bot.chat('/crates'));assert.equal(w.slots.length,90);w=await window(()=>bot.clickWindow(20,0,0));assert.equal(w.slots.length,90);const before=inventory();await bot.clickWindow(0,0,1);await sleep(500);assert.equal(inventory(),before);await close();console.log('LIVE GUI 54-SLOT PREVIEW AND SHIFT-EXTRACTION PASS');
 for(const [i,tier] of ['COMMON','RARE','EPIC','LEGENDARY','MYTHIC'].entries()){
  await message('Crate location registered.',()=>bot.chat('/loottest select '+tier));await message('LIVE KEY QUEUED',()=>bot.chat('/loottest key '+tier));const key=await until(()=>bot.inventory.items().find(i=>i.name==='tripwire_hook'));await sleep(1500);await bot.equip(key,'hand');await message('LIVE KEY SNAPSHOT PASS',()=>bot.chat('/loottest copy'));
  w=await window(()=>activateAt(2+i*3,101,2));assert.equal(w.slots.length,90);await sleep(5000);await close();await message('LIVE CRATE AUDIT PASS',()=>bot.chat('/loottest audit'));
  await message('LIVE REPLAY INSERTED',()=>bot.chat('/loottest replay'));const replay=await until(()=>bot.inventory.items().find(i=>i.name==='tripwire_hook'));await bot.equip(replay,'hand');await message('invalid or already spent',()=>activateAt(2+i*3,101,2));await message('LIVE CRATE AUDIT PASS',()=>bot.chat('/loottest audit'));await message('LIVE SPENT COPY CLEARED',()=>bot.chat('/loottest clearreplay'));console.log('LIVE CRATE TIER PASS',tier);
 }
 for(const tier of ['COMMON','RARE','EPIC','LEGENDARY','MYTHIC']){
  bot.chat('/loottest vault '+tier);let ready;for(let tries=0;tries<30;tries++){await sleep(1500);const result=await message('LIVE VAULT',()=>bot.chat('/loottest visit'));if(result.includes('LIVE VAULT READY')){ready=result;break;}}
  if(!ready)throw new Error('No '+tier+' vault found');const coords=ready.match(/READY (-?\d+) (-?\d+) (-?\d+)/);assert(coords);await sleep(2000);await message('discovered a '+tier+' secret vault',()=>activateAt(+coords[1],+coords[2],+coords[3]));await sleep(2500);await message('LIVE VAULT AUDIT PASS',()=>bot.chat('/loottest vaultaudit'));console.log('LIVE VAULT TIER AND RESTORATION PASS',tier);
 }
 await message('LIVE CAPACITY QUEUED',()=>bot.chat('/loottest fill'));await sleep(2000);await message('LIVE CAPACITY STATE WAITING',()=>bot.chat('/loottest capacity'));await message('LIVE CAPACITY SLOT FREED',()=>bot.chat('/loottest free'));await sleep(2500);await message('LIVE CAPACITY STATE DELIVERED',()=>bot.chat('/loottest capacity'));await message('LIVE INVENTORY RESTORED',()=>bot.chat('/loottest restore'));console.log('LIVE INVENTORY CAPACITY PASS');console.log('ALL LOOT CLIENT CHECKS PASS');bot.quit();setTimeout(()=>process.exit(0),500);
}
bot.once('spawn',()=>{console.log('CLIENT SPAWNED',bot.version);run().catch(e=>{console.error('PROBE FAILED',e.stack);bot.quit();setTimeout(()=>process.exit(1),500)})});
setTimeout(()=>{console.error('CLIENT GLOBAL TIMEOUT');process.exit(1)},480000).unref();
