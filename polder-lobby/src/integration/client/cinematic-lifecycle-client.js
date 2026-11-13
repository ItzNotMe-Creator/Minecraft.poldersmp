const mineflayer=require('mineflayer'),assert=require('node:assert/strict'),fs=require('node:fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const bot=mineflayer.createBot({host:'127.0.0.1',port:25578,username:process.env.POLDER_LOBBY_USER||'LobbyStandalone',version:'1.21.11',auth:'offline'});
bot.on('error',e=>{throw e});bot.on('messagestr',m=>console.log(m));let spectator=false,samples=0;
bot.on('move',()=>{if(bot.game?.gameMode==='spectator'){spectator=true;samples++}});
async function until(fn,timeout=20000){const end=Date.now()+timeout;while(Date.now()<end){if(fn())return;await sleep(50)}throw new Error('Lifecycle condition timeout')}
async function main(){await new Promise(r=>bot.once('spawn',r));await until(()=>bot.game.gameMode==='spectator');spectator=true;if(process.env.POLDER_CINEMATIC_STOP_FILE){fs.writeFileSync(process.env.POLDER_CINEMATIC_STOP_FILE,'ready');console.log('ACTIVE INTRO SHUTDOWN READY');await new Promise(resolve=>bot.once('end',resolve));console.log('CONTROLLED SERVER DISCONNECT OBSERVED');return;}await until(()=>bot.game.gameMode==='survival');assert(spectator);assert(samples>=20);assert(Math.abs(bot.entity.position.y-180)<.2);assert(Math.abs(bot.entity.position.x-.5)<.2);console.log('STANDALONE CINEMATIC PATH AND SAFE RESTORATION PASS samples='+samples);bot.quit();}
main().then(()=>setTimeout(()=>process.exit(0),300)).catch(e=>{console.error(e.stack);bot.quit?.();setTimeout(()=>process.exit(1),300)});setTimeout(()=>process.exit(1),60000).unref();
